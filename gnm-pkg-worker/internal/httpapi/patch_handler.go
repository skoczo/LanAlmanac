package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/patcher"
	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/sshclient"
)

type PatchExecSpecDto struct {
	PreCheckCommand         *string           `json:"preCheckCommand,omitempty"`
	RefreshCommand          *string           `json:"refreshCommand,omitempty"`
	FullUpgradeCommand      string            `json:"fullUpgradeCommand"`
	SecurityUpgradeCommand  *string           `json:"securityUpgradeCommand,omitempty"`
	SelectedUpgradeTemplate *string           `json:"selectedUpgradeTemplate,omitempty"`
	RebootCheckCommand      *string           `json:"rebootCheckCommand,omitempty"`
	RebootRequiredExitCodes []int             `json:"rebootRequiredExitCodes,omitempty"`
	RequiresPrivilege       bool              `json:"requiresPrivilege"`
	NonInteractiveEnv       map[string]string `json:"nonInteractiveEnv,omitempty"`
	TimeoutSeconds          int               `json:"timeoutSeconds"`
	IdleTimeoutSeconds      int               `json:"idleTimeoutSeconds"`
}

type PatchExecuteRequestDto struct {
	ExecutionID     string            `json:"executionId"`
	Target          SshTargetDto      `json:"target"`
	EnvironmentVars map[string]string `json:"environmentVars,omitempty"`
	Mode            string            `json:"mode"`
	Packages        []string          `json:"packages,omitempty"`
	PatchExecSpec   PatchExecSpecDto  `json:"patchExecSpec"`
	PrivilegeSpec   *PrivilegeSpecDto `json:"privilegeSpec,omitempty"`
	LockSpec        *LockSpecDto      `json:"lockSpec,omitempty"`
}

type CancelResponseDto struct {
	ExecutionID string `json:"executionId"`
	Cancelled   bool   `json:"cancelled"`
}

type executionRegistry struct {
	mu      sync.Mutex
	cancels map[string]context.CancelFunc
}

func newExecutionRegistry() *executionRegistry {
	return &executionRegistry{
		cancels: make(map[string]context.CancelFunc),
	}
}

func (r *executionRegistry) register(id string, cancel context.CancelFunc) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.cancels[id] = cancel
}

func (r *executionRegistry) unregister(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.cancels, id)
}

func (r *executionRegistry) cancel(id string) bool {
	r.mu.Lock()
	fn, ok := r.cancels[id]
	r.mu.Unlock()
	if ok && fn != nil {
		fn()
		return true
	}
	return false
}

func (reg *executionRegistry) handlePatchExecute(w http.ResponseWriter, r *http.Request) {
	var req PatchExecuteRequestDto
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "Malformed JSON request body", nil, nil)
		return
	}
	execID := strings.TrimSpace(req.ExecutionID)
	if execID == "" || strings.TrimSpace(req.PatchExecSpec.FullUpgradeCommand) == "" {
		req.Target.Auth.Secret = ""
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "executionId and patchExecSpec.fullUpgradeCommand are required", nil, nil)
		return
	}

	rawUpgradeCmd, err := resolveModeUpgradeCommand(req)
	if err != nil {
		req.Target.Auth.Secret = ""
		var pkgErr *patcher.InvalidPackageNameError
		if errors.As(err, &pkgErr) {
			writeApiError(w, http.StatusBadRequest, "INVALID_PACKAGE_NAME", pkgErr.Message, nil, nil)
			return
		}
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", err.Error(), nil, nil)
		return
	}

	wrap := func(cmdPtr *string) (string, string) {
		if cmdPtr == nil || strings.TrimSpace(*cmdPtr) == "" {
			return "", ""
		}
		return WrapPrivilegeCommand(*cmdPtr, req.PatchExecSpec.RequiresPrivilege, req.PrivilegeSpec)
	}

	preCheckCmd, errCode := wrap(req.PatchExecSpec.PreCheckCommand)
	if errCode == "" {
		var refreshCmd string
		refreshCmd, errCode = wrap(req.PatchExecSpec.RefreshCommand)
		if errCode == "" {
			var upgradeCmd string
			upgradeCmd, errCode = WrapPrivilegeCommand(rawUpgradeCmd, req.PatchExecSpec.RequiresPrivilege, req.PrivilegeSpec)
			if errCode == "" {
				var rebootCmd string
				if req.PatchExecSpec.RebootCheckCommand != nil {
					rebootCmd = strings.TrimSpace(*req.PatchExecSpec.RebootCheckCommand)
				}
				reg.streamExecution(w, r, execID, req, preCheckCmd, refreshCmd, upgradeCmd, rebootCmd)
				return
			}
		}
	}

	req.Target.Auth.Secret = ""
	status := http.StatusBadRequest
	if errCode == "UNSUPPORTED_ELEVATION_MODE" {
		status = http.StatusBadGateway
	}
	writeApiError(w, status, errCode, "Unsupported or invalid elevationMode", nil, nil)
}

func resolveModeUpgradeCommand(req PatchExecuteRequestDto) (string, error) {
	switch strings.ToUpper(strings.TrimSpace(req.Mode)) {
	case "", "FULL_UPGRADE":
		return req.PatchExecSpec.FullUpgradeCommand, nil
	case "SECURITY_ONLY":
		if req.PatchExecSpec.SecurityUpgradeCommand != nil && strings.TrimSpace(*req.PatchExecSpec.SecurityUpgradeCommand) != "" {
			return strings.TrimSpace(*req.PatchExecSpec.SecurityUpgradeCommand), nil
		}
		return req.PatchExecSpec.FullUpgradeCommand, nil
	case "SELECTED_PACKAGES":
		tpl := ""
		if req.PatchExecSpec.SelectedUpgradeTemplate != nil {
			tpl = *req.PatchExecSpec.SelectedUpgradeTemplate
		}
		return patcher.RenderSelectedUpgradeCommand(tpl, req.Packages)
	default:
		return "", errors.New("unsupported patch mode: " + req.Mode)
	}
}

func (reg *executionRegistry) streamExecution(
	w http.ResponseWriter,
	r *http.Request,
	execID string,
	req PatchExecuteRequestDto,
	preCheckCmd, refreshCmd, upgradeCmd, rebootCmd string,
) {
	target := req.Target.toClientTarget()
	defer clear(target.Secret)

	conn, err := sshclient.Dial(r.Context(), target)
	if err != nil {
		writeMappedSshError(w, err)
		return
	}
	defer conn.Close()

	timeoutSec := req.PatchExecSpec.TimeoutSeconds
	if timeoutSec <= 0 || timeoutSec > 3600 {
		timeoutSec = 1800
	}
	idleSec := req.PatchExecSpec.IdleTimeoutSeconds
	if idleSec <= 0 || idleSec > 1800 {
		idleSec = 300
	}

	env := make(map[string]string)
	env["LC_ALL"] = "C"
	env["LANG"] = "C"
	for k, v := range req.EnvironmentVars {
		env[k] = v
	}
	for k, v := range req.PatchExecSpec.NonInteractiveEnv {
		env[k] = v
	}

	execCtx, cancel := context.WithTimeout(r.Context(), time.Duration(timeoutSec)*time.Second)
	defer cancel()

	reg.register(execID, cancel)
	defer reg.unregister(execID)

	w.Header().Set("Content-Type", "application/x-ndjson")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)

	flusher, _ := w.(http.Flusher)
	enc := json.NewEncoder(w)

	runner := func(ctx context.Context, cmd string, onLine func(stream, line string)) (int, error) {
		exitCode, streamErr := conn.ExecStreaming(ctx, cmd, env, time.Duration(idleSec)*time.Second, onLine)
		if streamErr != nil {
			var lostErr *sshclient.ConnectionLostError
			if errors.As(streamErr, &lostErr) {
				return exitCode, patcher.ErrConnectionLost
			}
			var idleErr *sshclient.IdleTimeoutError
			if errors.As(streamErr, &idleErr) {
				return exitCode, patcher.ErrIdleTimeout
			}
			var cmdToErr *sshclient.CommandTimeoutError
			if errors.As(streamErr, &cmdToErr) {
				return exitCode, patcher.ErrCommandTimeout
			}
			return exitCode, streamErr
		}
		return exitCode, nil
	}

	patcher.Execute(
		execCtx,
		runner,
		patcher.ExecConfig{
			Mode:        req.Mode,
			Packages:    req.Packages,
			PreCheckCmd: preCheckCmd,
			RefreshCmd:  refreshCmd,
			UpgradeCmd:  upgradeCmd,
			RebootCmd:   rebootCmd,
			Spec: patcher.ExecSpec{
				RebootRequiredExitCodes: req.PatchExecSpec.RebootRequiredExitCodes,
				TimeoutSeconds:          timeoutSec,
				IdleTimeoutSeconds:      idleSec,
			},
		},
		func(frame patcher.StreamFrame) {
			_ = enc.Encode(frame)
			if flusher != nil {
				flusher.Flush()
			}
		},
	)
}

func (reg *executionRegistry) handleCancelExecution(w http.ResponseWriter, r *http.Request) {
	execID := strings.TrimSpace(r.PathValue("executionId"))
	if execID == "" {
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "executionId is required", nil, nil)
		return
	}
	if !reg.cancel(execID) {
		writeApiError(w, http.StatusNotFound, "INVALID_REQUEST", "Execution not found or already completed", nil, nil)
		return
	}
	writeJSON(w, http.StatusOK, CancelResponseDto{
		ExecutionID: execID,
		Cancelled:   true,
	})
}

