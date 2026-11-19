package httpapi

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/auth"
)

const ContractVersion = "1.0"

type Config struct {
	AuthSecret    []byte
	WorkerVersion string
	NowFn         func() time.Time
}

type ApiError struct {
	ErrorCode         string  `json:"errorCode"`
	Message           string  `json:"message"`
	ActualFingerprint *string `json:"actualFingerprint,omitempty"`
	ExitCode          *int    `json:"exitCode,omitempty"`
	Details           *string `json:"details,omitempty"`
}

type HealthResponse struct {
	Status                  string   `json:"status"`
	ContractVersion         string   `json:"contractVersion"`
	WorkerVersion           string   `json:"workerVersion"`
	Implementation          string   `json:"implementation"`
	SupportedTransports     []string `json:"supportedTransports"`
	SupportedParsers        []string `json:"supportedParsers"`
	SupportedElevationModes []string `json:"supportedElevationModes"`
}

// NewHandler constructs the authenticated HTTP handler implementing contract v1.
func NewHandler(cfg Config) (http.Handler, error) {
	authMW, err := auth.NewMiddleware(cfg.AuthSecret, cfg.NowFn)
	if err != nil {
		return nil, err
	}

	version := cfg.WorkerVersion
	if version == "" {
		version = "0.1.0-dev"
	}

	reg := newExecutionRegistry()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, HealthResponse{
			Status:              "OK",
			ContractVersion:     ContractVersion,
			WorkerVersion:       version,
			Implementation:      "go",
			SupportedTransports: []string{"SSH"},
			SupportedParsers: []string{
				"DELIMITED",
				"KEY_VALUE_BLOCKS",
				"JSON_MAPPING",
				"REGEX_LINE",
			},
			SupportedElevationModes: []string{
				"NONE",
				"SUDO_NOPASSWD",
			},
		})
	})
	mux.HandleFunc("POST /v1/ssh/fingerprint", handleSshFingerprint)
	mux.HandleFunc("POST /v1/probe", handleProbe)
	mux.HandleFunc("POST /v1/inventory", handleInventory)
	mux.HandleFunc("POST /v1/updates/check", handleUpdatesCheck)
	mux.HandleFunc("POST /v1/patch/execute", reg.handlePatchExecute)
	mux.HandleFunc("POST /v1/executions/{executionId}/cancel", reg.handleCancelExecution)

	return authMW(mux), nil
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeApiError(w http.ResponseWriter, status int, code, message string, actualFP *string, exitCode *int) {
	writeJSON(w, status, ApiError{
		ErrorCode:         code,
		Message:           message,
		ActualFingerprint: actualFP,
		ExitCode:          exitCode,
	})
}
