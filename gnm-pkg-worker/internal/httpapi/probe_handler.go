package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/sshclient"
)

type SshFingerprintRequest struct {
	Host                  string `json:"host"`
	Port                  int    `json:"port"`
	ConnectTimeoutSeconds int    `json:"connectTimeoutSeconds"`
}

type SshFingerprintResponse struct {
	FingerprintSHA256 string `json:"fingerprintSha256"`
	KeyAlgorithm      string `json:"keyAlgorithm"`
}

type AuthConfigDto struct {
	Type       string  `json:"type"`
	Secret     string  `json:"secret"`
	SudoSecret *string `json:"sudoSecret,omitempty"`
}

type SshTargetDto struct {
	Transport             string        `json:"transport"`
	Host                  string        `json:"host"`
	Port                  int           `json:"port"`
	Username              string        `json:"username"`
	ExpectedHostKeySHA256 string        `json:"expectedHostKeySha256"`
	Auth                  AuthConfigDto `json:"auth"`
	ConnectTimeoutSeconds int           `json:"connectTimeoutSeconds"`
}

func (t *SshTargetDto) toClientTarget() sshclient.Target {
	port := t.Port
	if port == 0 {
		port = 22
	}
	timeoutSec := t.ConnectTimeoutSeconds
	if timeoutSec <= 0 {
		timeoutSec = 10
	}
	secretBytes := []byte(t.Auth.Secret)
	t.Auth.Secret = ""
	return sshclient.Target{
		Host:                  strings.TrimSpace(t.Host),
		Port:                  port,
		Username:              strings.TrimSpace(t.Username),
		ExpectedHostKeySHA256: strings.TrimSpace(t.ExpectedHostKeySHA256),
		AuthType:              sshclient.AuthType(strings.ToUpper(strings.TrimSpace(t.Auth.Type))),
		Secret:                secretBytes,
		ConnectTimeout:        time.Duration(timeoutSec) * time.Second,
	}
}

type ProbeRequest struct {
	ExecutionID    string            `json:"executionId"`
	Target         SshTargetDto      `json:"target"`
	VerifyCommands map[string]string `json:"verifyCommands,omitempty"`
}

type ProbeResponse struct {
	OSFacts       map[string]string `json:"osFacts"`
	VerifyResults map[string]bool   `json:"verifyResults,omitempty"`
}

func handleSshFingerprint(w http.ResponseWriter, r *http.Request) {
	var req SshFingerprintRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "Malformed JSON request body", nil, nil)
		return
	}
	if req.Port == 0 {
		req.Port = 22
	}
	timeoutSec := req.ConnectTimeoutSeconds
	if timeoutSec <= 0 || timeoutSec > 60 {
		timeoutSec = 10
	}

	res, err := sshclient.FetchFingerprint(r.Context(), strings.TrimSpace(req.Host), req.Port, time.Duration(timeoutSec)*time.Second)
	if err != nil {
		writeMappedSshError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, SshFingerprintResponse{
		FingerprintSHA256: res.FingerprintSHA256,
		KeyAlgorithm:      res.KeyAlgorithm,
	})
}

func handleProbe(w http.ResponseWriter, r *http.Request) {
	var req ProbeRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "Malformed JSON request body", nil, nil)
		return
	}
	if strings.TrimSpace(req.ExecutionID) == "" {
		req.Target.Auth.Secret = ""
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", "executionId is required", nil, nil)
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

	res, err := sshclient.Probe(r.Context(), conn, req.VerifyCommands)
	if err != nil {
		writeMappedSshError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, ProbeResponse{
		OSFacts:       res.OSFacts,
		VerifyResults: res.VerifyResults,
	})
}

func writeMappedSshError(w http.ResponseWriter, err error) {
	var invalidErr *sshclient.InvalidTargetError
	if errors.As(err, &invalidErr) {
		writeApiError(w, http.StatusBadRequest, "INVALID_REQUEST", invalidErr.Message, nil, nil)
		return
	}

	var mismatchErr *sshclient.HostKeyMismatchError
	if errors.As(err, &mismatchErr) {
		actual := mismatchErr.Actual
		writeApiError(w, http.StatusBadGateway, "HOST_KEY_MISMATCH", "SSH host key fingerprint does not match trusted key", &actual, nil)
		return
	}

	var authErr *sshclient.AuthError
	if errors.As(err, &authErr) {
		writeApiError(w, http.StatusBadGateway, "AUTH_FAILED", authErr.Message, nil, nil)
		return
	}

	var refusedErr *sshclient.ConnectionRefusedError
	if errors.As(err, &refusedErr) {
		writeApiError(w, http.StatusBadGateway, "CONNECTION_REFUSED", refusedErr.Message, nil, nil)
		return
	}

	var connTimeoutErr *sshclient.ConnectionTimeoutError
	if errors.As(err, &connTimeoutErr) {
		writeApiError(w, http.StatusBadGateway, "CONNECTION_TIMEOUT", connTimeoutErr.Message, nil, nil)
		return
	}

	var cmdTimeoutErr *sshclient.CommandTimeoutError
	if errors.As(err, &cmdTimeoutErr) {
		writeApiError(w, http.StatusBadGateway, "COMMAND_TIMEOUT", cmdTimeoutErr.Message, nil, nil)
		return
	}

	writeApiError(w, http.StatusBadGateway, "INTERNAL_ERROR", "Remote SSH operation failed", nil, nil)
}

