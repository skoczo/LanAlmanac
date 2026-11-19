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
)

type PrivilegeSpecDto struct {
	ElevationMode           string  `json:"elevationMode"`
	WrapperTemplate         *string `json:"wrapperTemplate,omitempty"`
	PasswordWrapperTemplate *string `json:"passwordWrapperTemplate,omitempty"`
}

type InventorySpecDto struct {
	Command           string         `json:"command"`
	RequiresPrivilege bool           `json:"requiresPrivilege"`
	TimeoutSeconds    int            `json:"timeoutSeconds"`
	ParserType        string         `json:"parserType"`
	ParserConfig      map[string]any `json:"parserConfig"`
	PURLTemplate      string         `json:"purlTemplate"`
}

type InventoryRequestDto struct {
	ExecutionID        string            `json:"executionId"`
	Target             SshTargetDto      `json:"target"`
	EnvironmentVars    map[string]string `json:"environmentVars,omitempty"`
	OSFacts            map[string]string `json:"osFacts"`
	InventorySpec      InventorySpecDto  `json:"inventorySpec"`
	PrivilegeSpec      *PrivilegeSpecDto `json:"privilegeSpec,omitempty"`
	DryRunPreviewLimit *int              `json:"dryRunPreviewLimit,omitempty"`
}

type InventoryResponseDto struct {
	Packages           []parsers.NormalizedPackage `json:"packages"`
	TotalParsed        int                         `json:"totalParsed"`
	SkippedLines       int                         `json:"skippedLines"`
	SampleSkippedLines []string                    `json:"sampleSkippedLines,omitempty"`
	DurationMs         int64                       `json:"durationMs"`
}

// WrapPrivilegeCommand wraps a command according to PrivilegeSpec if privilege elevation is requested.
func WrapPrivilegeCommand(cmd string, requiresPrivilege bool, priv *PrivilegeSpecDto) (string, string) {
	if !requiresPrivilege || priv == nil {
		return cmd, ""
	}
	mode := strings.ToUpper(strings.TrimSpace(priv.ElevationMode))
	switch mode {
	case "", "NONE":
		return cmd, ""
	case "SUDO_NOPASSWD":
		tpl := "sudo -n {{command}}"
		if priv.WrapperTemplate != nil && strings.TrimSpace(*priv.WrapperTemplate) != "" {
			tpl = strings.TrimSpace(*priv.WrapperTemplate)
		}
		if strings.Contains(tpl, "{{command}}") {
			return strings.ReplaceAll(tpl, "{{command}}", cmd), ""
		}
		return tpl + " " + cmd, ""
	case "SUDO_PASSWORD":
		return "", "UNSUPPORTED_ELEVATION_MODE"
	default:
		return "", "INVALID_REQUEST"
	}
}

func isSudoPermissionError(stderr string) bool {
	lower := strings.ToLower(stderr)
	return strings.Contains(lower, "sudo: a password is required") ||
		strings.Contains(lower, "sudo: a terminal is required") ||
		strings.Contains(lower, "is not in the sudoers file") ||
		strings.Contains(lower, "not allowed to execute")
}

func handleInventory(w http.ResponseWriter, r *http.Request) {
	started := time.Now()
	var req InventoryRequestDto
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "Malformed JSON request body", nil, nil)
		return
	}
	if strings.TrimSpace(req.ExecutionID) == "" || strings.TrimSpace(req.InventorySpec.Command) == "" {
		req.Target.Auth.Secret = ""
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "executionId and inventorySpec.command are required", nil, nil)
		return
	}

	effectiveCmd, errCode := WrapPrivilegeCommand(req.InventorySpec.Command, req.InventorySpec.RequiresPrivilege, req.PrivilegeSpec)
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

	timeoutSec := req.InventorySpec.TimeoutSeconds
	if timeoutSec <= 0 || timeoutSec > 300 {
		timeoutSec = 60
	}
	cmdCtx, cancel := context.WithTimeout(r.Context(), time.Duration(timeoutSec)*time.Second)
	defer cancel()

	env := req.EnvironmentVars
	if len(env) == 0 {
		env = map[string]string{"LC_ALL": "C", "LANG": "C"}
	}

	execRes, err := conn.ExecBuffered(cmdCtx, effectiveCmd, env, sshclient.DefaultMaxOutputBytes)
	if err != nil {
		writeMappedSshError(w, err)
		return
	}

	if execRes.ExitCode != 0 {
		exitCode := execRes.ExitCode
		if isSudoPermissionError(execRes.Stderr) {
			writeApiError(w, http.StatusBadGateway, "SUDO_PERMISSION_REQUIRED", "sudo non-interactive permission denied", nil, &exitCode)
			return
		}
		writeApiError(w, http.StatusBadGateway, "INTERNAL_ERROR", "Inventory command exited with non-zero status", nil, &exitCode)
		return
	}

	previewLimit := 0
	if req.DryRunPreviewLimit != nil && *req.DryRunPreviewLimit > 0 {
		previewLimit = *req.DryRunPreviewLimit
	}

	parsed, err := parsers.ParseInventory(
		req.InventorySpec.ParserType,
		req.InventorySpec.ParserConfig,
		execRes.Stdout,
		req.InventorySpec.PURLTemplate,
		req.OSFacts,
		previewLimit,
	)
	if err != nil {
		var pErr *parsers.ParserError
		if errors.As(err, &pErr) {
			writeApiError(w, http.StatusBadGateway, "PARSER_ERROR", pErr.Message, nil, nil)
			return
		}
		writeApiError(w, http.StatusBadGateway, "PARSER_ERROR", err.Error(), nil, nil)
		return
	}

	writeJSON(w, http.StatusOK, InventoryResponseDto{
		Packages:           parsed.Packages,
		TotalParsed:        parsed.TotalParsed,
		SkippedLines:       parsed.SkippedLines,
		SampleSkippedLines: parsed.SampleSkippedLines,
		DurationMs:         time.Since(started).Milliseconds(),
	})
}

