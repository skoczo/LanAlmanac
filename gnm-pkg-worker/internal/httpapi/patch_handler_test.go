package httpapi

import (
	"bufio"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/patcher"
)

func TestPatchExecuteAndCancelEndpoints(t *testing.T) {
	host, port, expectedFP := startHTTPTestSSHServerWithHandler(t, func(cmd string) (string, string, uint32) {
		if strings.Contains(cmd, "sleep-cancel") {
			time.Sleep(500 * time.Millisecond)
			return "", "", 0
		}
		if strings.Contains(cmd, "reboot-required") {
			return "", "", 0
		}
		return "Upgrading packages...\nDone.\n", "warning: minor\n", 0
	})

	fixedNow := time.Unix(1700000000, 0).UTC()
	handler, err := NewHandler(Config{
		AuthSecret: []byte(testWorkerSecret),
		NowFn:      func() time.Time { return fixedNow },
	})
	if err != nil {
		t.Fatalf("NewHandler: %v", err)
	}

	t.Run("rejects_command_injection_in_packages_with_400", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/patch/execute", map[string]any{
			"executionId": "8f2c1b0a-4444-4444-8444-444444444441",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
			"mode":     "SELECTED_PACKAGES",
			"packages": []string{"openssl", "; rm -rf /"},
			"patchExecSpec": map[string]any{
				"fullUpgradeCommand":      "apt-get -y upgrade",
				"selectedUpgradeTemplate": "apt-get -y install --only-upgrade -- {{packages}}",
			},
		}, fixedNow)

		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("expected 400 for command injection attempt, got %d (%s)", rec.Code, rec.Body.String())
		}
		var apiErr ApiError
		_ = json.Unmarshal(rec.Body.Bytes(), &apiErr)
		if apiErr.ErrorCode != "INVALID_PACKAGE_NAME" {
			t.Fatalf("errorCode = %q, want INVALID_PACKAGE_NAME", apiErr.ErrorCode)
		}
	})

	t.Run("streams_ndjson_frames_for_selected_upgrade", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/patch/execute", map[string]any{
			"executionId": "8f2c1b0a-4444-4444-8444-444444444442",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
			"mode":     "SELECTED_PACKAGES",
			"packages": []string{"openssl", "curl"},
			"patchExecSpec": map[string]any{
				"fullUpgradeCommand":      "apt-get -y upgrade",
				"selectedUpgradeTemplate": "apt-get -y install --only-upgrade -- {{packages}}",
				"rebootCheckCommand":      "test -f /var/run/reboot-required",
				"rebootRequiredExitCodes": []int{0},
				"requiresPrivilege":       true,
			},
			"privilegeSpec": map[string]any{
				"elevationMode": "SUDO_NOPASSWD",
			},
		}, fixedNow)

		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d (%s)", rec.Code, rec.Body.String())
		}

		var frames []patcher.StreamFrame
		scanner := bufio.NewScanner(rec.Body)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line == "" {
				continue
			}
			var f patcher.StreamFrame
			if err := json.Unmarshal([]byte(line), &f); err != nil {
				t.Fatalf("unmarshal frame %q: %v", line, err)
			}
			frames = append(frames, f)
		}
		if len(frames) < 4 {
			t.Fatalf("expected at least 4 NDJSON frames, got %d", len(frames))
		}
		last := frames[len(frames)-1]
		if last.Type != "RESULT" || last.Status != "SUCCEEDED" {
			t.Fatalf("unexpected final frame: %+v", last)
		}
	})

	t.Run("cancel_unknown_execution_returns_404", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/executions/8f2c1b0a-9999-4999-8999-999999999999/cancel", map[string]any{}, fixedNow)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusNotFound {
			t.Fatalf("expected 404, got %d", rec.Code)
		}
	})
}

