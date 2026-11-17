package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/auth"
)

const testWorkerSecret = "0123456789abcdef0123456789abcdef"

func TestHealthEndpoint_RequiresAuth(t *testing.T) {
	fixedNow := time.Unix(1700000000, 0).UTC()
	handler, err := NewHandler(Config{
		AuthSecret:    []byte(testWorkerSecret),
		WorkerVersion: "0.1.0-test",
		NowFn:         func() time.Time { return fixedNow },
	})
	if err != nil {
		t.Fatalf("unexpected error building handler: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/v1/health", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for unauthenticated /v1/health, got %d", rec.Code)
	}
}

func TestHealthEndpoint_ReturnsContractMetadata(t *testing.T) {
	fixedNow := time.Unix(1700000000, 0).UTC()
	handler, err := NewHandler(Config{
		AuthSecret:    []byte(testWorkerSecret),
		WorkerVersion: "0.1.0-test",
		NowFn:         func() time.Time { return fixedNow },
	})
	if err != nil {
		t.Fatalf("unexpected error building handler: %v", err)
	}

	ts := strconv.FormatInt(fixedNow.Unix(), 10)
	sig := auth.ComputeSignature([]byte(testWorkerSecret), http.MethodGet, "/v1/health", ts, nil)

	req := httptest.NewRequest(http.MethodGet, "/v1/health", nil)
	req.Header.Set(auth.HeaderTimestamp, ts)
	req.Header.Set(auth.HeaderSignature, sig)

	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d (body: %s)", rec.Code, rec.Body.String())
	}

	var resp HealthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode HealthResponse: %v", err)
	}

	if resp.Status != "OK" {
		t.Errorf("status = %q, want OK", resp.Status)
	}
	if resp.ContractVersion != "1.0" {
		t.Errorf("contractVersion = %q, want 1.0", resp.ContractVersion)
	}
	if resp.WorkerVersion != "0.1.0-test" {
		t.Errorf("workerVersion = %q, want 0.1.0-test", resp.WorkerVersion)
	}
	if resp.Implementation != "go" {
		t.Errorf("implementation = %q, want go", resp.Implementation)
	}
	if len(resp.SupportedTransports) != 1 || resp.SupportedTransports[0] != "SSH" {
		t.Errorf("supportedTransports = %v, want [SSH]", resp.SupportedTransports)
	}
	if len(resp.SupportedParsers) != 4 {
		t.Errorf("supportedParsers = %v, want 4 parsers", resp.SupportedParsers)
	}
	if len(resp.SupportedElevationModes) != 2 ||
		resp.SupportedElevationModes[0] != "NONE" ||
		resp.SupportedElevationModes[1] != "SUDO_NOPASSWD" {
		t.Errorf("supportedElevationModes = %v, want [NONE SUDO_NOPASSWD]", resp.SupportedElevationModes)
	}
}

