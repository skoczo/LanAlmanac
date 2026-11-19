package httpapi

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/auth"
	"golang.org/x/crypto/ssh"
)

func startHTTPTestSSHServer(t *testing.T) (host string, port int, fingerprint string) {
	return startHTTPTestSSHServerWithHandler(t, func(cmd string) (string, string, uint32) {
		if strings.Contains(cmd, "GNM_OS_RELEASE_START") {
			return "GNM_KERNEL=Linux\nGNM_ARCH=x86_64\nGNM_RUNNING_KERNEL=6.1.0\nGNM_OS_RELEASE_START\nID=debian\nVERSION_ID=\"12\"\nGNM_OS_RELEASE_END\n", "", 0
		}
		return "", "", 0
	})
}

func startHTTPTestSSHServerWithHandler(
	t *testing.T,
	execFn func(cmd string) (stdout string, stderr string, exitCode uint32),
) (host string, port int, fingerprint string) {
	t.Helper()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate host key: %v", err)
	}
	signer, err := ssh.NewSignerFromKey(priv)
	if err != nil {
		t.Fatalf("signer: %v", err)
	}
	fp := ssh.FingerprintSHA256(signer.PublicKey())

	cfg := &ssh.ServerConfig{
		PasswordCallback: func(c ssh.ConnMetadata, pass []byte) (*ssh.Permissions, error) {
			if c.User() == "lanalmanac" && string(pass) == "s3cr3t" {
				return &ssh.Permissions{}, nil
			}
			return nil, fmt.Errorf("denied")
		},
	}
	cfg.AddHostKey(signer)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })

	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(nc net.Conn) {
				defer nc.Close()
				sc, chans, reqs, err := ssh.NewServerConn(nc, cfg)
				if err != nil {
					return
				}
				defer sc.Close()
				go ssh.DiscardRequests(reqs)
				for newCh := range chans {
					ch, chReqs, err := newCh.Accept()
					if err != nil {
						continue
					}
					go func() {
						defer ch.Close()
						for req := range chReqs {
							if req.Type == "exec" && len(req.Payload) >= 4 {
								n := binary.BigEndian.Uint32(req.Payload[:4])
								cmd := string(req.Payload[4 : 4+n])
								_ = req.Reply(true, nil)
								stdout, stderr, exitCode := execFn(cmd)
								if stdout != "" {
									_, _ = ch.Write([]byte(stdout))
								}
								if stderr != "" {
									_, _ = ch.Stderr().Write([]byte(stderr))
								}
								exitBytes := make([]byte, 4)
								binary.BigEndian.PutUint32(exitBytes, exitCode)
								_, _ = ch.SendRequest("exit-status", false, exitBytes)
								return
							}
						}
					}()
				}
			}(c)
		}
	}()

	h, pStr, _ := net.SplitHostPort(ln.Addr().String())
	p, _ := strconv.Atoi(pStr)
	return h, p, fp
}

func signedJSONRequest(method, path string, payload any, now time.Time) *http.Request {
	b, _ := json.Marshal(payload)
	ts := strconv.FormatInt(now.Unix(), 10)
	sig := auth.ComputeSignature([]byte(testWorkerSecret), method, path, ts, b)
	req := httptest.NewRequest(method, path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(auth.HeaderTimestamp, ts)
	req.Header.Set(auth.HeaderSignature, sig)
	return req
}

func TestFingerprintEndpoint_Success(t *testing.T) {
	host, port, expectedFP := startHTTPTestSSHServer(t)
	fixedNow := time.Unix(1700000000, 0).UTC()
	handler, err := NewHandler(Config{
		AuthSecret: []byte(testWorkerSecret),
		NowFn:      func() time.Time { return fixedNow },
	})
	if err != nil {
		t.Fatalf("NewHandler: %v", err)
	}

	req := signedJSONRequest(http.MethodPost, "/v1/ssh/fingerprint", map[string]any{
		"host":                  host,
		"port":                  port,
		"connectTimeoutSeconds": 5,
	}, fixedNow)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (%s)", rec.Code, rec.Body.String())
	}
	var resp struct {
		FingerprintSHA256 string `json:"fingerprintSha256"`
		KeyAlgorithm      string `json:"keyAlgorithm"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if resp.FingerprintSHA256 != expectedFP {
		t.Fatalf("fingerprint = %q, want %q", resp.FingerprintSHA256, expectedFP)
	}
	if resp.KeyAlgorithm != "ssh-ed25519" {
		t.Fatalf("keyAlgorithm = %q, want ssh-ed25519", resp.KeyAlgorithm)
	}
}

func TestProbeEndpoint_SuccessAndHostKeyMismatchAndValidation(t *testing.T) {
	host, port, expectedFP := startHTTPTestSSHServer(t)
	fixedNow := time.Unix(1700000000, 0).UTC()
	handler, err := NewHandler(Config{
		AuthSecret: []byte(testWorkerSecret),
		NowFn:      func() time.Time { return fixedNow },
	})
	if err != nil {
		t.Fatalf("NewHandler: %v", err)
	}

	t.Run("missing_expected_host_key_returns_400", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/probe", map[string]any{
			"executionId": "8f2c1b0a-1111-4111-8111-111111111111",
			"target": map[string]any{
				"transport": "SSH",
				"host":      host,
				"port":      port,
				"username":  "lanalmanac",
				"auth":      map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
		}, fixedNow)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d (%s)", rec.Code, rec.Body.String())
		}
	})

	t.Run("host_key_mismatch_returns_502_with_actual_fingerprint", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/probe", map[string]any{
			"executionId": "8f2c1b0a-1111-4111-8111-111111111112",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
		}, fixedNow)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadGateway {
			t.Fatalf("expected 502, got %d (%s)", rec.Code, rec.Body.String())
		}
		var apiErr ApiError
		if err := json.Unmarshal(rec.Body.Bytes(), &apiErr); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if apiErr.ErrorCode != "HOST_KEY_MISMATCH" {
			t.Fatalf("errorCode = %q, want HOST_KEY_MISMATCH", apiErr.ErrorCode)
		}
		if apiErr.ActualFingerprint == nil || *apiErr.ActualFingerprint != expectedFP {
			t.Fatalf("actualFingerprint = %v, want %q", apiErr.ActualFingerprint, expectedFP)
		}
	})

	t.Run("valid_probe_returns_os_facts", func(t *testing.T) {
		req := signedJSONRequest(http.MethodPost, "/v1/probe", map[string]any{
			"executionId": "8f2c1b0a-1111-4111-8111-111111111113",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
			"verifyCommands": map[string]string{
				"debian-apt": "command -v dpkg-query",
			},
		}, fixedNow)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d (%s)", rec.Code, rec.Body.String())
		}
		var resp ProbeResponse
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if resp.OSFacts["os.id"] != "debian" || resp.OSFacts["os.version_major"] != "12" {
			t.Fatalf("unexpected osFacts: %+v", resp.OSFacts)
		}
		if !resp.VerifyResults["debian-apt"] {
			t.Fatalf("expected debian-apt verify true")
		}
	})

	t.Run("auth_failed_and_bad_requests_mapped_properly", func(t *testing.T) {
		// Wrong password -> 502 AUTH_FAILED
		req := signedJSONRequest(http.MethodPost, "/v1/probe", map[string]any{
			"executionId": "8f2c1b0a-1111-4111-8111-111111111114",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "wrong"},
			},
		}, fixedNow)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadGateway {
			t.Fatalf("expected 502, got %d", rec.Code)
		}

		// Missing executionId -> 400
		reqMissingID := signedJSONRequest(http.MethodPost, "/v1/probe", map[string]any{
			"target": map[string]any{"host": host, "port": port},
		}, fixedNow)
		recMissingID := httptest.NewRecorder()
		handler.ServeHTTP(recMissingID, reqMissingID)
		if recMissingID.Code != http.StatusBadRequest {
			t.Fatalf("expected 400 for missing executionId, got %d", recMissingID.Code)
		}

		// Connection refused on fingerprint -> 502 CONNECTION_REFUSED
		reqRefused := signedJSONRequest(http.MethodPost, "/v1/ssh/fingerprint", map[string]any{
			"host": "127.0.0.1",
			"port": 1,
		}, fixedNow)
		recRefused := httptest.NewRecorder()
		handler.ServeHTTP(recRefused, reqRefused)
		if recRefused.Code != http.StatusBadGateway {
			t.Fatalf("expected 502 for connection refused, got %d", recRefused.Code)
		}

		// Invalid JSON on fingerprint and probe -> 400
		for _, path := range []string{"/v1/ssh/fingerprint", "/v1/probe"} {
			badBody := []byte("{invalid-json")
			ts := strconv.FormatInt(fixedNow.Unix(), 10)
			sig := auth.ComputeSignature([]byte(testWorkerSecret), http.MethodPost, path, ts, badBody)
			badReq := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(badBody))
			badReq.Header.Set(auth.HeaderTimestamp, ts)
			badReq.Header.Set(auth.HeaderSignature, sig)
			badRec := httptest.NewRecorder()
			handler.ServeHTTP(badRec, badReq)
			if badRec.Code != http.StatusBadRequest {
				t.Fatalf("expected 400 for malformed JSON on %s, got %d", path, badRec.Code)
			}
		}
	})
}

