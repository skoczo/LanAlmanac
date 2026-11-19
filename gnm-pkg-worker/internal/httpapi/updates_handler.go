package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/parsers"
	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/sshclient"
	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/updates"
)

type LockSpecDto struct {
	RetryableExitCodes []int  `json:"retryableExitCodes,omitempty"`
	LockErrorPattern   string `json:"lockErrorPattern,omitempty"`
	MaxRetries         int    `json:"maxRetries,omitempty"`
	BackoffSeconds     []int  `json:"backoffSeconds,omitempty"`
}

type UpdateCheckSpecDto struct {
	RefreshCommand            *string        `json:"refreshCommand,omitempty"`
	RefreshRequiresPrivilege  bool           `json:"refreshRequiresPrivilege"`
	CheckCommand              string         `json:"checkCommand"`
	CheckRequiresPrivilege    bool           `json:"checkRequiresPrivilege"`
	NoUpdatesExitCodes        []int          `json:"noUpdatesExitCodes,omitempty"`
	UpdatesAvailableExitCodes []int          `json:"updatesAvailableExitCodes,omitempty"`
	TimeoutSeconds            int            `json:"timeoutSeconds"`
	ParserType                string         `json:"parserType"`
	ParserConfig              map[string]any `json:"parserConfig"`
}

type UpdateCheckRequestDto struct {
	ExecutionID     string             `json:"executionId"`
	Target          SshTargetDto       `json:"target"`
	EnvironmentVars map[string]string  `json:"environmentVars,omitempty"`
	SkipRefresh     bool               `json:"skipRefresh"`
	UpdateCheckSpec UpdateCheckSpecDto `json:"updateCheckSpec"`
	PrivilegeSpec   *PrivilegeSpecDto  `json:"privilegeSpec,omitempty"`
	LockSpec        *LockSpecDto       `json:"lockSpec,omitempty"`
}

type UpdateCheckResponseDto struct {
	Updates     []updates.AvailableUpdate `json:"updates"`
	ExitCode    int                       `json:"exitCode"`
	RetriesUsed int                       `json:"retriesUsed"`
	DurationMs  int64                     `json:"durationMs"`
}

func handleUpdatesCheck(w http.ResponseWriter, r *http.Request) {
	started := time.Now()
	var req UpdateCheckRequestDto
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "Malformed JSON request body", nil, nil)
		return
	}
	if strings.TrimSpace(req.ExecutionID) == "" || strings.TrimSpace(req.UpdateCheckSpec.CheckCommand) == "" {
		req.Target.Auth.Secret = ""
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "executionId and updateCheckSpec.checkCommand are required", nil, nil)
		return
	}

	refreshCmd := ""
	if req.UpdateCheckSpec.RefreshCommand != nil && strings.TrimSpace(*req.UpdateCheckSpec.RefreshCommand) != "" {
		var errCode string
		refreshCmd, errCode = WrapPrivilegeCommand(*req.UpdateCheckSpec.RefreshCommand, req.UpdateCheckSpec.RefreshRequiresPrivilege, req.PrivilegeSpec)
		if errCode != "" {
			req.Target.Auth.Secret = ""
			status := http.StatusBadRequest
			if errCode == "UNSUPPORTED_ELEVATION_MODE" {
				status = http.StatusBadGateway
			}
			writeApiError(w, status, errCode, "Unsupported or invalid elevationMode", nil, nil)
			return
		}
	}

	checkCmd, errCode := WrapPrivilegeCommand(req.UpdateCheckSpec.CheckCommand, req.UpdateCheckSpec.CheckRequiresPrivilege, req.PrivilegeSpec)
	if errCode != "" {
		req.Target.Auth.Secret = ""
		status := http.StatusBadRequest
		if errCode == "UNSUPPORTED_ELEVATION_MODE" {
			status = http.StatusBadGateway
		}
		writeApiError(w, status, errCode, "Unsupported or invalid elevationMode", nil, nil)
		return
	}

	target := req.Target.toClientTarget()
	defer clear(target.Secret)

	conn, err := sshclient.Dial(r.Context(), target)
	if err != nil {
		writeMappedSshError(w, err)
		return
	}
	defer conn.Close()

	timeoutSec := req.UpdateCheckSpec.TimeoutSeconds
	if timeoutSec <= 0 || timeoutSec > 600 {
		timeoutSec = 120
	}
	env := req.EnvironmentVars
	if len(env) == 0 {
		env = map[string]string{"LC_ALL": "C", "LANG": "C"}
	}

	var lockSpec updates.LockSpec
	if req.LockSpec != nil {
		lockSpec = updates.LockSpec{
			RetryableExitCodes: req.LockSpec.RetryableExitCodes,
			LockErrorPattern:   req.LockSpec.LockErrorPattern,
			MaxRetries:         req.LockSpec.MaxRetries,
			BackoffSeconds:     req.LockSpec.BackoffSeconds,
		}
	}

	res, err := updates.RunCheck(
		r.Context(),
		func(ctx context.Context, cmd string) (string, string, int, error) {
			cmdCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutSec)*time.Second)
			defer cancel()
			execRes, execErr := conn.ExecBuffered(cmdCtx, cmd, env, sshclient.DefaultMaxOutputBytes)
			return execRes.Stdout, execRes.Stderr, execRes.ExitCode, execErr
		},
		updates.CheckSpec{
			RefreshCommand:            refreshCmd,
			CheckCommand:              checkCmd,
			NoUpdatesExitCodes:        req.UpdateCheckSpec.NoUpdatesExitCodes,
			UpdatesAvailableExitCodes: req.UpdateCheckSpec.UpdatesAvailableExitCodes,
			TimeoutSeconds:            timeoutSec,
			ParserType:                req.UpdateCheckSpec.ParserType,
			ParserConfig:              req.UpdateCheckSpec.ParserConfig,
		},
		lockSpec,
		req.SkipRefresh,
		nil,
	)
	if err != nil {
		var sudoErr *updates.SudoPermissionRequiredError
		if errors.As(err, &sudoErr) {
			code := sudoErr.ExitCode
			writeApiError(w, http.StatusBadGateway, "SUDO_PERMISSION_REQUIRED", sudoErr.Message, nil, &code)
			return
		}
		var lockErr *updates.PackageManagerLockedError
		if errors.As(err, &lockErr) {
			code := lockErr.ExitCode
			writeApiError(w, http.StatusBadGateway, "PACKAGE_MANAGER_LOCKED", lockErr.Message, nil, &code)
			return
		}
		var exitErr *updates.CheckExitCodeError
		if errors.As(err, &exitErr) {
			code := exitErr.ExitCode
			writeApiError(w, http.StatusBadGateway, "INTERNAL_ERROR", exitErr.Message, nil, &code)
			return
		}
		var pErr *parsers.ParserError
		if errors.As(err, &pErr) {
			writeApiError(w, http.StatusBadGateway, "PARSER_ERROR", pErr.Message, nil, nil)
			return
		}
		writeMappedSshError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, UpdateCheckResponseDto{
		Updates:     res.Updates,
		ExitCode:    res.ExitCode,
		RetriesUsed: res.RetriesUsed,
		DurationMs:  time.Since(started).Milliseconds(),
	})
}

