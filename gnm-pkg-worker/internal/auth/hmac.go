package auth

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	HeaderTimestamp = "X-GNM-Timestamp"
	HeaderSignature = "X-GNM-Signature"

	MinSecretBytes = 32
	MaxClockSkew   = 30 * time.Second
	MaxBodyBytes   = 2 << 20 // 2 MiB
)

type errorBody struct {
	ErrorCode string `json:"errorCode"`
	Message   string `json:"message"`
}

// ComputeSignature computes the lowercase hex HMAC-SHA256 over the canonical request string:
// METHOD\nPATH\nTIMESTAMP\nHEX_SHA256(BODY).
func ComputeSignature(secret []byte, method, path, timestamp string, body []byte) string {
	bodyHash := sha256.Sum256(body)
	canonical := method + "\n" + path + "\n" + timestamp + "\n" + hex.EncodeToString(bodyHash[:])

	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(canonical))
	return hex.EncodeToString(mac.Sum(nil))
}

// NewMiddleware returns an HTTP middleware verifying HMAC-SHA256 signatures and replay window.
func NewMiddleware(secret []byte, nowFn func() time.Time) (func(http.Handler) http.Handler, error) {
	if len(secret) < MinSecretBytes {
		return nil, fmt.Errorf("auth secret must be at least %d bytes", MinSecretBytes)
	}
	if nowFn == nil {
		nowFn = time.Now
	}

	keyCopy := make([]byte, len(secret))
	copy(keyCopy, secret)

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			tsHeader := strings.TrimSpace(r.Header.Get(HeaderTimestamp))
			sigHeader := strings.TrimSpace(r.Header.Get(HeaderSignature))
			if tsHeader == "" || sigHeader == "" {
				writeAuthError(w, http.StatusUnauthorized, "AUTH_FAILED", "Missing authentication headers")
				return
			}

			tsUnix, err := strconv.ParseInt(tsHeader, 10, 64)
			if err != nil {
				writeAuthError(w, http.StatusUnauthorized, "AUTH_FAILED", "Invalid timestamp header")
				return
			}

			now := nowFn()
			reqTime := time.Unix(tsUnix, 0)
			diff := now.Sub(reqTime)
			if diff < 0 {
				diff = -diff
			}
			if diff > MaxClockSkew {
				writeAuthError(w, http.StatusUnauthorized, "AUTH_FAILED", "Request timestamp outside allowed window")
				return
			}

			body, err := readBoundedBody(r.Body, MaxBodyBytes)
			if err != nil {
				if errors.Is(err, errBodyTooLarge) {
					writeAuthError(w, http.StatusRequestEntityTooLarge, "INVALID_REQUEST", "Request body exceeds maximum size")
					return
				}
				writeAuthError(w, http.StatusBadRequest, "INVALID_REQUEST", "Failed to read request body")
				return
			}
			r.Body = io.NopCloser(bytes.NewReader(body))

			expectedHex := ComputeSignature(keyCopy, r.Method, r.URL.Path, tsHeader, body)
			gotBytes, err := hex.DecodeString(strings.ToLower(sigHeader))
			if err != nil {
				writeAuthError(w, http.StatusUnauthorized, "AUTH_FAILED", "Invalid signature encoding")
				return
			}
			expectedBytes, _ := hex.DecodeString(expectedHex)

			if !hmac.Equal(gotBytes, expectedBytes) {
				writeAuthError(w, http.StatusUnauthorized, "AUTH_FAILED", "Invalid request signature")
				return
			}

			next.ServeHTTP(w, r)
		})
	}, nil
}

var errBodyTooLarge = errors.New("request body too large")

func readBoundedBody(rc io.ReadCloser, maxBytes int64) ([]byte, error) {
	if rc == nil {
		return nil, nil
	}
	defer rc.Close()

	limited := io.LimitReader(rc, maxBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > maxBytes {
		return nil, errBodyTooLarge
	}
	return data, nil
}

func writeAuthError(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(errorBody{
		ErrorCode: code,
		Message:   msg,
	})
}

