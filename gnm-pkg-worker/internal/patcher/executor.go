package patcher

import (
	"context"
	"errors"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	ErrConnectionLost = errors.New("SSH connection lost without exit status")
	ErrIdleTimeout    = errors.New("remote command exceeded idle timeout without output")
	ErrCommandTimeout = errors.New("remote command exceeded maximum execution duration")
)

type StreamFrame struct {
	Type           string  `json:"type"`
	Seq            int64   `json:"seq"`
	Ts             string  `json:"ts"`
	Phase          string  `json:"phase,omitempty"`
	Stream         string  `json:"stream,omitempty"`
	Line           string  `json:"line,omitempty"`
	Status         string  `json:"status,omitempty"`
	ExitCode       *int    `json:"exitCode,omitempty"`
	RebootRequired *bool   `json:"rebootRequired,omitempty"`
	ErrorCode      *string `json:"errorCode,omitempty"`
	ErrorMessage   *string `json:"errorMessage,omitempty"`
}

type ExecSpec struct {
	PreCheckCommand         *string
	RefreshCommand          *string
	FullUpgradeCommand      string
	SecurityUpgradeCommand  *string
	SelectedUpgradeTemplate *string
	RebootCheckCommand      *string
	RebootRequiredExitCodes []int
	TimeoutSeconds          int
	IdleTimeoutSeconds      int
}

type ExecConfig struct {
	Mode        string
	Packages    []string
	UpgradeCmd  string
	RefreshCmd  string
	PreCheckCmd string
	RebootCmd   string
	Spec        ExecSpec
}

type CommandRunner func(ctx context.Context, cmd string, onLine func(stream, line string)) (exitCode int, err error)

// Execute runs the patch state machine (PRECHECK -> REFRESH -> UPGRADE -> REBOOT_CHECK)
// and emits thread-safe, strictly monotonic NDJSON StreamFrames.
func Execute(ctx context.Context, runner CommandRunner, cfg ExecConfig, emit func(StreamFrame)) {
	var (
		seq    atomic.Int64
		emitMu sync.Mutex
	)

	send := func(f StreamFrame) {
		emitMu.Lock()
		defer emitMu.Unlock()
		f.Seq = seq.Add(1)
		f.Ts = time.Now().UTC().Format(time.RFC3339Nano)
		emit(f)
	}

	sendPhase := func(phase string) {
		send(StreamFrame{Type: "PHASE", Phase: phase})
	}

	sendLog := func(stream, line string) {
		send(StreamFrame{Type: "LOG", Stream: stream, Line: sanitizeLine(line)})
	}

	sendResult := func(status string, exitCode *int, rebootReq *bool, errCode, errMsg *string) {
		send(StreamFrame{
			Type:           "RESULT",
			Status:         status,
			ExitCode:       exitCode,
			RebootRequired: rebootReq,
			ErrorCode:      errCode,
			ErrorMessage:   errMsg,
		})
	}

	runPhase := func(phase, cmd string) (int, bool) {
		if strings.TrimSpace(cmd) == "" {
			return 0, true
		}
		sendPhase(phase)
		exitCode, err := runner(ctx, cmd, sendLog)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(ctx.Err(), context.Canceled) {
				code := "CANCELLED"
				msg := "Patch execution cancelled by operator"
				sendResult("CANCELLED", nil, nil, &code, &msg)
				return exitCode, false
			}
			if errors.Is(err, ErrConnectionLost) {
				code := "CONNECTION_LOST"
				msg := "SSH connection closed unexpectedly without exit status"
				sendResult("CONNECTION_LOST", nil, nil, &code, &msg)
				return exitCode, false
			}
			if errors.Is(err, ErrIdleTimeout) {
				code := "IDLE_TIMEOUT"
				msg := err.Error()
				sendResult("TIMEOUT", nil, nil, &code, &msg)
				return exitCode, false
			}
			if errors.Is(err, ErrCommandTimeout) || errors.Is(err, context.DeadlineExceeded) {
				code := "COMMAND_TIMEOUT"
				msg := "Patch execution exceeded timeout"
				sendResult("TIMEOUT", nil, nil, &code, &msg)
				return exitCode, false
			}
			code := "INTERNAL_ERROR"
			msg := err.Error()
			sendResult("FAILED", nil, nil, &code, &msg)
			return exitCode, false
		}
		if exitCode != 0 {
			ec := exitCode
			code := "INTERNAL_ERROR"
			msg := "Phase " + phase + " failed with non-zero exit status"
			sendResult("FAILED", &ec, nil, &code, &msg)
			return exitCode, false
		}
		return 0, true
	}

	preCheckCmd := cfg.PreCheckCmd
	if preCheckCmd == "" && cfg.Spec.PreCheckCommand != nil {
		preCheckCmd = *cfg.Spec.PreCheckCommand
	}
	if _, ok := runPhase("PRECHECK", preCheckCmd); !ok {
		return
	}

	refreshCmd := cfg.RefreshCmd
	if refreshCmd == "" && cfg.Spec.RefreshCommand != nil {
		refreshCmd = *cfg.Spec.RefreshCommand
	}
	if _, ok := runPhase("REFRESH", refreshCmd); !ok {
		return
	}

	upgradeCmd := cfg.UpgradeCmd
	if upgradeCmd == "" {
		upgradeCmd = cfg.Spec.FullUpgradeCommand
	}
	if _, ok := runPhase("UPGRADE", upgradeCmd); !ok {
		return
	}

	rebootRequired := false
	rebootCmd := cfg.RebootCmd
	if rebootCmd == "" && cfg.Spec.RebootCheckCommand != nil {
		rebootCmd = *cfg.Spec.RebootCheckCommand
	}
	if strings.TrimSpace(rebootCmd) != "" {
		sendPhase("REBOOT_CHECK")
		rExit, rErr := runner(ctx, rebootCmd, sendLog)
		if rErr == nil {
			triggerCodes := cfg.Spec.RebootRequiredExitCodes
			if len(triggerCodes) == 0 {
				triggerCodes = []int{0}
			}
			rebootRequired = slices.Contains(triggerCodes, rExit)
		}
	}

	zero := 0
	sendResult("SUCCEEDED", &zero, &rebootRequired, nil, nil)
}

func sanitizeLine(s string) string {
	s = strings.TrimRight(s, "\r\n")
	if len(s) > 4096 {
		s = s[:4096]
	}
	return s
}

