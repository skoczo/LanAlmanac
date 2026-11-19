package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestUpdatesCheckEndpoint_SuccessSudoFailureAndElevationMode(t *testing.T) {
	host, port, expectedFP := startHTTPTestSSHServerWithHandler(t, func(cmd string) (string, string, uint32) {
		if strings.Contains(cmd, "sudo -n") && strings.Contains(cmd, "fail-sudo") {
			return "", "sudo: a password is required\n", 1
		}
		if strings.Contains(cmd, "dnf -q check-update") {
			return "curl.x86_64 7.76.1-29.el9_4 baseos\n", "", 100
		}
		return "", "", 0
	})

	fixedNow := time.Unix(1700000000, 0).UTC()
	handler, err := NewHandler(Config{
		AuthSecret: []byte(testWorkerSecret),
		NowFn:      func() time.Time { return fixedNow },
	})
	if err != nil {
		t.Fatalf("NewHandler: %v", err)
	}

	t.Run("dnf_exit_code_100_returns_updates", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/updates/check", map[string]any{
			"executionId": "8f2c1b0a-3333-4333-8333-333333333331",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
			"updateCheckSpec": map[string]any{
				"checkCommand":              "dnf -q check-update",
				"noUpdatesExitCodes":        []int{0},
				"updatesAvailableExitCodes": []int{0, 100},
				"timeoutSeconds":            30,
				"parserType":                "REGEX_LINE",
				"parserConfig": map[string]any{
					"pattern": `^(?<packageName>[A-Za-z0-9._+-]+)\.(?<arch>x86_64|noarch)\s+(?<candidateVersion>\S+)\s+\S+$`,
				},
			},
		}, fixedNow)

		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d (%s)", rec.Code, rec.Body.String())
		}
		var resp UpdateCheckResponseDto
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if resp.ExitCode != 100 || len(resp.Updates) != 1 || resp.Updates[0].PackageName != "curl" {
			t.Fatalf("unexpected response: %+v", resp)
		}
	})

	t.Run("sudo_password_required_returns_502_sudo_permission_required", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/updates/check", map[string]any{
			"executionId": "8f2c1b0a-3333-4333-8333-333333333332",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
			"updateCheckSpec": map[string]any{
				"refreshCommand":           "apt-get update --fail-sudo",
				"refreshRequiresPrivilege": true,
				"checkCommand":             "apt-get -s upgrade",
				"parserType":               "REGEX_LINE",
				"parserConfig":             map[string]any{"pattern": `^Inst\s+(?<packageName>\S+)`},
			},
			"privilegeSpec": map[string]any{
				"elevationMode": "SUDO_NOPASSWD",
			},
		}, fixedNow)

		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadGateway {
			t.Fatalf("expected 502, got %d (%s)", rec.Code, rec.Body.String())
		}
		var apiErr ApiError
		_ = json.Unmarshal(rec.Body.Bytes(), &apiErr)
		if apiErr.ErrorCode != "SUDO_PERMISSION_REQUIRED" {
			t.Fatalf("errorCode = %q, want SUDO_PERMISSION_REQUIRED", apiErr.ErrorCode)
		}
	})

	t.Run("sudo_password_elevation_mode_returns_unsupported", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/updates/check", map[string]any{
			"executionId": "8f2c1b0a-3333-4333-8333-333333333333",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
			"updateCheckSpec": map[string]any{
				"refreshCommand":           "apt-get update",
				"refreshRequiresPrivilege": true,
				"checkCommand":             "apt-get -s upgrade",
				"parserType":               "REGEX_LINE",
				"parserConfig":             map[string]any{"pattern": `^Inst\s+(?<packageName>\S+)`},
			},
			"privilegeSpec": map[string]any{
				"elevationMode": "SUDO_PASSWORD",
			},
		}, fixedNow)

		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadGateway {
			t.Fatalf("expected 502, got %d (%s)", rec.Code, rec.Body.String())
		}
		var apiErr ApiError
		_ = json.Unmarshal(rec.Body.Bytes(), &apiErr)
		if apiErr.ErrorCode != "UNSUPPORTED_ELEVATION_MODE" {
			t.Fatalf("errorCode = %q, want UNSUPPORTED_ELEVATION_MODE", apiErr.ErrorCode)
		}
	})
}

