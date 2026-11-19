package sshclient

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

func TestFetchFingerprint_ReturnsSHA256WithoutSendingCredentials(t *testing.T) {
	srv := startMockSSHServer(t, nil)
	host, port := srv.HostPort()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	res, err := FetchFingerprint(ctx, host, port, 5*time.Second)
	if err != nil {
		t.Fatalf("FetchFingerprint failed: %v", err)
	}

	if res.FingerprintSHA256 != srv.hostFingerprint {
		t.Fatalf("fingerprint = %q, want %q", res.FingerprintSHA256, srv.hostFingerprint)
	}
	if res.KeyAlgorithm != srv.hostKeyAlgorithm {
		t.Fatalf("keyAlgorithm = %q, want %q", res.KeyAlgorithm, srv.hostKeyAlgorithm)
	}
	if srv.passwordAuthCalls.Load() != 0 || srv.publicKeyAuthCalls.Load() != 0 {
		t.Fatalf("expected 0 auth attempts during fingerprint fetch, got pwd=%d pubkey=%d",
			srv.passwordAuthCalls.Load(), srv.publicKeyAuthCalls.Load())
	}
}

func TestDial_HostKeyMismatchAbortsBeforeAuth(t *testing.T) {
	srv := startMockSSHServer(t, nil)
	host, port := srv.HostPort()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, err := Dial(ctx, Target{
		Host:                  host,
		Port:                  port,
		Username:              srv.allowedUser,
		ExpectedHostKeySHA256: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
		AuthType:              AuthTypePassword,
		Secret:                []byte(srv.allowedPassword),
		ConnectTimeout:        5 * time.Second,
	})
	if err == nil {
		t.Fatalf("expected HostKeyMismatchError, got nil")
	}

	var mismatchErr *HostKeyMismatchError
	if !errors.As(err, &mismatchErr) {
		t.Fatalf("expected *HostKeyMismatchError, got %T (%v)", err, err)
	}
	if mismatchErr.Actual != srv.hostFingerprint {
		t.Fatalf("mismatchErr.Actual = %q, want %q", mismatchErr.Actual, srv.hostFingerprint)
	}
	if srv.passwordAuthCalls.Load() != 0 || srv.publicKeyAuthCalls.Load() != 0 {
		t.Fatalf("credentials MUST NOT be sent on host key mismatch! pwd=%d pubkey=%d",
			srv.passwordAuthCalls.Load(), srv.publicKeyAuthCalls.Load())
	}
}

func TestDial_PasswordAndSSHKeyAuth(t *testing.T) {
	edPem, edPub := generateTestEd25519Key(t)
	rsaPem, rsaPub := generateTestRSAKey(t)

	srv := startMockSSHServer(t, func(s *mockSSHServer) {
		s.allowedPubKeys = []ssh.PublicKey{edPub, rsaPub}
		s.execHandler = func(cmd string) (string, string, uint32) {
			return "hello-stdout\n", "hello-stderr\n", 0
		}
	})
	host, port := srv.HostPort()

	cases := []struct {
		name     string
		authType AuthType
		secret   []byte
	}{
		{name: "password", authType: AuthTypePassword, secret: []byte(srv.allowedPassword)},
		{name: "ssh_key_ed25519", authType: AuthTypeSSHKey, secret: edPem},
		{name: "ssh_key_rsa", authType: AuthTypeSSHKey, secret: rsaPem},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			secretCopy := append([]byte(nil), tc.secret...)
			conn, err := Dial(ctx, Target{
				Host:                  host,
				Port:                  port,
				Username:              srv.allowedUser,
				ExpectedHostKeySHA256: srv.hostFingerprint,
				AuthType:              tc.authType,
				Secret:                secretCopy,
				ConnectTimeout:        5 * time.Second,
			})
			if err != nil {
				t.Fatalf("Dial failed for %s: %v", tc.name, err)
			}
			defer conn.Close()

			// Verify secret slice was zeroed after Dial.
			for i, b := range secretCopy {
				if b != 0 {
					t.Fatalf("secret byte at index %d was not zeroed after Dial", i)
				}
			}

			execRes, err := conn.ExecBuffered(ctx, "echo hello", map[string]string{"LC_ALL": "C"}, 1024*1024)
			if err != nil {
				t.Fatalf("ExecBuffered failed: %v", err)
			}
			if execRes.ExitCode != 0 {
				t.Fatalf("exitCode = %d, want 0", execRes.ExitCode)
			}
			if !strings.Contains(execRes.Stdout, "hello-stdout") {
				t.Fatalf("stdout = %q, want hello-stdout", execRes.Stdout)
			}
			if !strings.Contains(execRes.Stderr, "hello-stderr") {
				t.Fatalf("stderr = %q, want hello-stderr", execRes.Stderr)
			}
		})
	}
}

func TestDial_RejectsEmptyOrMalformedHostKey(t *testing.T) {
	ctx := context.Background()
	for _, bad := range []string{"", "not-a-sha256", "MD5:aa:bb:cc"} {
		_, err := Dial(ctx, Target{
			Host:                  "127.0.0.1",
			Port:                  22,
			Username:              "user",
			ExpectedHostKeySHA256: bad,
			AuthType:              AuthTypePassword,
			Secret:                []byte("secret"),
		})
		if err == nil {
			t.Fatalf("expected error for invalid expectedHostKeySha256 %q", bad)
		}
		var invalidErr *InvalidTargetError
		if !errors.As(err, &invalidErr) {
			t.Fatalf("expected *InvalidTargetError for %q, got %T (%v)", bad, err, err)
		}
	}
}

func TestDial_WrongPasswordReturnsAuthError(t *testing.T) {
	srv := startMockSSHServer(t, nil)
	host, port := srv.HostPort()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, err := Dial(ctx, Target{
		Host:                  host,
		Port:                  port,
		Username:              srv.allowedUser,
		ExpectedHostKeySHA256: srv.hostFingerprint,
		AuthType:              AuthTypePassword,
		Secret:                []byte("wrong-password"),
		ConnectTimeout:        5 * time.Second,
	})
	if err == nil {
		t.Fatalf("expected AuthError, got nil")
	}
	var authErr *AuthError
	if !errors.As(err, &authErr) {
		t.Fatalf("expected *AuthError, got %T (%v)", err, err)
	}
}

func TestExecBuffered_NonZeroExitOutputLimitTimeoutAndEnvValidation(t *testing.T) {
	srv := startMockSSHServer(t, func(s *mockSSHServer) {
		s.execHandler = func(cmd string) (string, string, uint32) {
			if strings.Contains(cmd, "sleep") {
				time.Sleep(300 * time.Millisecond)
				return "done\n", "", 0
			}
			if strings.Contains(cmd, "big") {
				return strings.Repeat("A", 2048), "", 0
			}
			return "out\n", "err\n", 42
		}
	})
	host, port := srv.HostPort()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, err := Dial(ctx, Target{
		Host:                  host,
		Port:                  port,
		Username:              srv.allowedUser,
		ExpectedHostKeySHA256: srv.hostFingerprint,
		AuthType:              AuthTypePassword,
		Secret:                []byte(srv.allowedPassword),
	})
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	defer conn.Close()

	t.Run("non_zero_exit_code", func(t *testing.T) {
		res, execErr := conn.ExecBuffered(ctx, "exit 42", map[string]string{"A": "it's"}, 0)
		if execErr != nil {
			t.Fatalf("unexpected error for non-zero exit code: %v", execErr)
		}
		if res.ExitCode != 42 {
			t.Fatalf("exitCode = %d, want 42", res.ExitCode)
		}
	})

	t.Run("invalid_env_var_name", func(t *testing.T) {
		_, execErr := conn.ExecBuffered(ctx, "echo hi", map[string]string{"BAD-KEY;id": "1"}, 1024)
		var invErr *InvalidTargetError
		if !errors.As(execErr, &invErr) {
			t.Fatalf("expected *InvalidTargetError, got %T (%v)", execErr, execErr)
		}
	})

	t.Run("output_limit_exceeded", func(t *testing.T) {
		_, execErr := conn.ExecBuffered(ctx, "big", nil, 512)
		var limitErr *OutputLimitError
		if !errors.As(execErr, &limitErr) {
			t.Fatalf("expected *OutputLimitError, got %T (%v)", execErr, execErr)
		}
	})

	t.Run("command_timeout", func(t *testing.T) {
		shortCtx, shortCancel := context.WithTimeout(ctx, 50*time.Millisecond)
		defer shortCancel()
		_, execErr := conn.ExecBuffered(shortCtx, "sleep 1", nil, 1024)
		var timeoutErr *CommandTimeoutError
		if !errors.As(execErr, &timeoutErr) {
			t.Fatalf("expected *CommandTimeoutError, got %T (%v)", execErr, execErr)
		}
	})

	t.Run("connection_refused_and_validation_errors", func(t *testing.T) {
		_, fpErr := FetchFingerprint(ctx, "", 22, time.Second)
		if fpErr == nil {
			t.Fatalf("expected error for empty host")
		}
		_, fpErr = FetchFingerprint(ctx, "127.0.0.1", 0, time.Second)
		if fpErr == nil {
			t.Fatalf("expected error for invalid port")
		}
		// Closed port -> ConnectionRefusedError
		srv.Close()
		_, fpErr = FetchFingerprint(ctx, host, port, time.Second)
		var refErr *ConnectionRefusedError
		if !errors.As(fpErr, &refErr) {
			t.Fatalf("expected *ConnectionRefusedError, got %T (%v)", fpErr, fpErr)
		}
	})

	t.Run("exec_streaming_streams_lines_concurrently", func(t *testing.T) {
		streamSrv := startMockSSHServer(t, func(s *mockSSHServer) {
			s.execHandler = func(cmd string) (string, string, uint32) {
				return "line1\nline2\n", "err1\n", 0
			}
		})
		sHost, sPort := streamSrv.HostPort()
		sConn, dErr := Dial(ctx, Target{
			Host:                  sHost,
			Port:                  sPort,
			Username:              streamSrv.allowedUser,
			ExpectedHostKeySHA256: streamSrv.hostFingerprint,
			AuthType:              AuthTypePassword,
			Secret:                []byte(streamSrv.allowedPassword),
		})
		if dErr != nil {
			t.Fatalf("Dial failed: %v", dErr)
		}
		defer sConn.Close()

		var lines []string
		code, sErr := sConn.ExecStreaming(ctx, "echo test", map[string]string{"LC_ALL": "C"}, 5*time.Second, func(stream, line string) {
			lines = append(lines, stream+":"+line)
		})
		if sErr != nil || code != 0 || len(lines) != 3 {
			t.Fatalf("ExecStreaming got code=%d err=%v lines=%v", code, sErr, lines)
		}
	})
}

