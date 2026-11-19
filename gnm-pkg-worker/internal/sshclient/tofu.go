package sshclient

import (
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"net"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

var sha256FingerprintPattern = regexp.MustCompile(`^SHA256:[A-Za-z0-9+/=]+$`)

// InvalidTargetError indicates invalid caller input (maps to HTTP 400).
type InvalidTargetError struct {
	Message string
}

func (e *InvalidTargetError) Error() string {
	return e.Message
}

// HostKeyMismatchError indicates the remote host key does not match the trusted fingerprint.
type HostKeyMismatchError struct {
	Expected string
	Actual   string
}

func (e *HostKeyMismatchError) Error() string {
	return fmt.Sprintf("SSH host key mismatch: expected %s, got %s", e.Expected, e.Actual)
}

// FingerprintResult contains the host key SHA-256 fingerprint and key algorithm.
type FingerprintResult struct {
	FingerprintSHA256 string
	KeyAlgorithm      string
}

// ValidateExpectedFingerprint validates the expected SHA-256 fingerprint format.
func ValidateExpectedFingerprint(fp string) error {
	if !sha256FingerprintPattern.MatchString(fp) {
		return &InvalidTargetError{Message: "expectedHostKeySha256 must match SHA256:<base64>"}
	}
	return nil
}

func normalizeFingerprint(fp string) string {
	trimmed := strings.TrimSpace(fp)
	if strings.HasPrefix(trimmed, "SHA256:") {
		return "SHA256:" + strings.TrimRight(strings.TrimPrefix(trimmed, "SHA256:"), "=")
	}
	return trimmed
}

func verifyHostKeyCallback(expected string) ssh.HostKeyCallback {
	normExpected := []byte(normalizeFingerprint(expected))
	return func(_ string, _ net.Addr, key ssh.PublicKey) error {
		actual := ssh.FingerprintSHA256(key)
		normActual := []byte(normalizeFingerprint(actual))
		if subtle.ConstantTimeCompare(normExpected, normActual) != 1 {
			return &HostKeyMismatchError{
				Expected: expected,
				Actual:   actual,
			}
		}
		return nil
	}
}

var errFingerprintCaptured = errors.New("ssh host key fingerprint captured")

// FetchFingerprint performs SSH key exchange ONLY (zero credentials sent) to capture
// the remote server's SHA-256 host key fingerprint for TOFU approval in gnm-app.
func FetchFingerprint(ctx context.Context, host string, port int, timeout time.Duration) (FingerprintResult, error) {
	if strings.TrimSpace(host) == "" {
		return FingerprintResult{}, &InvalidTargetError{Message: "host is required"}
	}
	if port < 1 || port > 65535 {
		return FingerprintResult{}, &InvalidTargetError{Message: "port must be between 1 and 65535"}
	}
	if timeout <= 0 {
		timeout = 10 * time.Second
	}

	dialCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	addr := net.JoinHostPort(host, strconv.Itoa(port))
	var d net.Dialer
	netConn, err := d.DialContext(dialCtx, "tcp", addr)
	if err != nil {
		return FingerprintResult{}, classifyDialError(err)
	}
	defer netConn.Close()

	if deadline, ok := dialCtx.Deadline(); ok {
		_ = netConn.SetDeadline(deadline)
	}

	var captured FingerprintResult
	cfg := &ssh.ClientConfig{
		User: "gnm-tofu-probe",
		Auth: nil, // No authentication methods — connection aborts during KEX.
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			captured = FingerprintResult{
				FingerprintSHA256: ssh.FingerprintSHA256(key),
				KeyAlgorithm:      key.Type(),
			}
			return errFingerprintCaptured
		},
		Timeout: timeout,
	}

	_, _, _, err = ssh.NewClientConn(netConn, addr, cfg)
	if captured.FingerprintSHA256 != "" {
		return captured, nil
	}
	if err != nil {
		return FingerprintResult{}, classifyDialError(err)
	}
	return FingerprintResult{}, fmt.Errorf("SSH handshake ended without host key")
}

