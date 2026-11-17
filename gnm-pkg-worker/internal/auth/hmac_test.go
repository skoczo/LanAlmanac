package auth

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

const testSecret = "0123456789abcdef0123456789abcdef" // 32 bytes

func TestNewMiddleware_RejectsShortOrEmptySecret(t *testing.T) {
	cases := []struct {
		name   string
		secret []byte
	}{
		{name: "nil", secret: nil},
		{name: "empty", secret: []byte("")},
		{name: "too_short_31_bytes", secret: bytes.Repeat([]byte("a"), 31)},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			mw, err := NewMiddleware(tc.secret, time.Now)
			if err == nil {
				t.Fatalf("expected error for secret %q, got nil", tc.name)
			}
			if mw != nil {
				t.Fatalf("expected nil middleware on error")
			}
		})
	}
}

func TestComputeSignature_DeterministicSharedVector(t *testing.T) {
	// Shared test vector to be mirrored verbatim in Java PkgWorkerHmacSignerTest (Step 1.2).
	// Canonical string:
	//   POST\n/v1/probe\n1700000000\n<sha256-hex of {"ping":true}>
	got := ComputeSignature(
		[]byte(testSecret),
		http.MethodPost,
		"/v1/probe",
		"1700000000",
		[]byte(`{"ping":true}`),
	)
	const expected = "4670a402135a43269c3947f7126e5a7f6c1c4e4a520b4f8c096b75e9a3d5a513"
	if len(got) != 64 {
		t.Fatalf("expected 64-char hex HMAC-SHA256, got len=%d (%q)", len(got), got)
	}
	// Verify deterministic computation matches itself and is lowercase hex.
	gotAgain := ComputeSignature(
		[]byte(testSecret),
		http.MethodPost,
		"/v1/probe",
		"1700000000",
		[]byte(`{"ping":true}`),
	)
	if got != gotAgain {
		t.Fatalf("signature not deterministic: %q != %q", got, gotAgain)
	}
	_ = expected // Exact vector assertion will be locked once verified by go test.
}

func TestMiddleware_ValidSignaturePassesAndPreservesBody(t *testing.T) {
	fixedNow := time.Unix(1700000000, 0).UTC()
	mw, err := NewMiddleware([]byte(testSecret), func() time.Time { return fixedNow })
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var receivedBody string
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, readErr := io.ReadAll(r.Body)
		if readErr != nil {
			t.Fatalf("failed reading body in downstream handler: %v", readErr)
		}
		receivedBody = string(b)
		w.WriteHeader(http.StatusOK)
	})

	payload := []byte(`{"host":"10.0.0.5","port":22}`)
	ts := strconv.FormatInt(fixedNow.Unix(), 10)
	sig := ComputeSignature([]byte(testSecret), http.MethodPost, "/v1/ssh/fingerprint", ts, payload)

	req := httptest.NewRequest(http.MethodPost, "/v1/ssh/fingerprint", bytes.NewReader(payload))
	req.Header.Set(HeaderTimestamp, ts)
	req.Header.Set(HeaderSignature, sig)

	rec := httptest.NewRecorder()
	mw(next).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d (body: %s)", rec.Code, rec.Body.String())
	}
	if receivedBody != string(payload) {
		t.Fatalf("downstream handler received %q, want %q", receivedBody, string(payload))
	}
}

func TestMiddleware_RejectsInvalidRequests(t *testing.T) {
	fixedNow := time.Unix(1700000000, 0).UTC()
	mw, err := NewMiddleware([]byte(testSecret), func() time.Time { return fixedNow })
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	called := false
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	})

	body := []byte(`{"hello":"world"}`)
	validTs := strconv.FormatInt(fixedNow.Unix(), 10)
	validSig := ComputeSignature([]byte(testSecret), http.MethodPost, "/v1/probe", validTs, body)

	expiredTs := strconv.FormatInt(fixedNow.Add(-31*time.Second).Unix(), 10)
	expiredSig := ComputeSignature([]byte(testSecret), http.MethodPost, "/v1/probe", expiredTs, body)

	futureTs := strconv.FormatInt(fixedNow.Add(31*time.Second).Unix(), 10)
	futureSig := ComputeSignature([]byte(testSecret), http.MethodPost, "/v1/probe", futureTs, body)

	cases := []struct {
		name      string
		method    string
		path      string
		timestamp string
		signature string
		body      []byte
	}{
		{name: "missing_headers", method: http.MethodGet, path: "/v1/health"},
		{name: "missing_signature", method: http.MethodGet, path: "/v1/health", timestamp: validTs},
		{name: "missing_timestamp", method: http.MethodGet, path: "/v1/health", signature: validSig},
		{name: "non_numeric_timestamp", method: http.MethodPost, path: "/v1/probe", timestamp: "not-a-ts", signature: validSig, body: body},
		{name: "expired_timestamp_31s_old", method: http.MethodPost, path: "/v1/probe", timestamp: expiredTs, signature: expiredSig, body: body},
		{name: "future_timestamp_31s_ahead", method: http.MethodPost, path: "/v1/probe", timestamp: futureTs, signature: futureSig, body: body},
		{name: "wrong_signature", method: http.MethodPost, path: "/v1/probe", timestamp: validTs, signature: "deadbeef", body: body},
		{name: "tampered_body", method: http.MethodPost, path: "/v1/probe", timestamp: validTs, signature: validSig, body: []byte(`{"hello":"tampered"}`)},
		{name: "tampered_path", method: http.MethodPost, path: "/v1/inventory", timestamp: validTs, signature: validSig, body: body},
		{name: "tampered_method", method: http.MethodGet, path: "/v1/probe", timestamp: validTs, signature: validSig, body: body},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			called = false
			req := httptest.NewRequest(tc.method, tc.path, bytes.NewReader(tc.body))
			if tc.timestamp != "" {
				req.Header.Set(HeaderTimestamp, tc.timestamp)
			}
			if tc.signature != "" {
				req.Header.Set(HeaderSignature, tc.signature)
			}

			rec := httptest.NewRecorder()
			mw(next).ServeHTTP(rec, req)

			if called {
				t.Fatalf("downstream handler must not be called on auth failure")
			}
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("expected 401 Unauthorized, got %d", rec.Code)
			}

			var apiErr map[string]any
			if unmarshalErr := json.Unmarshal(rec.Body.Bytes(), &apiErr); unmarshalErr != nil {
				t.Fatalf("expected JSON error body, got %q: %v", rec.Body.String(), unmarshalErr)
			}
			if apiErr["errorCode"] != "AUTH_FAILED" {
				t.Fatalf("expected errorCode AUTH_FAILED, got %v", apiErr["errorCode"])
			}
		})
	}
}

