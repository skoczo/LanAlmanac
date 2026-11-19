package updates

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestCheckUpdates_DnfExitCode100AndZero(t *testing.T) {
	spec := CheckSpec{
		CheckCommand:              "dnf -q check-update",
		NoUpdatesExitCodes:        []int{0},
		UpdatesAvailableExitCodes: []int{0, 100},
		TimeoutSeconds:            30,
		ParserType:                "REGEX_LINE",
		ParserConfig: map[string]any{
			"pattern": `^(?<packageName>[A-Za-z0-9._+-]+)\.(?<arch>x86_64|noarch)\s+(?<candidateVersion>\S+)\s+\S+$`,
		},
	}

	t.Run("exit_code_100_parses_updates", func(t *testing.T) {
		res, err := RunCheck(
			context.Background(),
			func(ctx context.Context, cmd string) (string, string, int, error) {
				return "openssl.x86_64 1:3.0.7-27.el9_4 baseos\nbash.x86_64 5.1.8-9.el9 baseos\n", "", 100, nil
			},
			spec,
			LockSpec{},
			false,
			func(d time.Duration) {},
		)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if res.ExitCode != 100 || len(res.Updates) != 2 {
			t.Fatalf("unexpected result: %+v", res)
		}
		if res.Updates[0].PackageName != "openssl" || res.Updates[0].CandidateVersion != "1:3.0.7-27.el9_4" {
			t.Errorf("unexpected update[0]: %+v", res.Updates[0])
		}
	})

	t.Run("exit_code_0_with_no_updates", func(t *testing.T) {
		res, err := RunCheck(
			context.Background(),
			func(ctx context.Context, cmd string) (string, string, int, error) {
				return "", "", 0, nil
			},
			spec,
			LockSpec{},
			false,
			func(d time.Duration) {},
		)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(res.Updates) != 0 {
			t.Fatalf("expected 0 updates, got %d", len(res.Updates))
		}
	})

	t.Run("unexpected_exit_code_1_returns_error", func(t *testing.T) {
		_, err := RunCheck(
			context.Background(),
			func(ctx context.Context, cmd string) (string, string, int, error) {
				return "", "fatal error", 1, nil
			},
			spec,
			LockSpec{},
			false,
			func(d time.Duration) {},
		)
		var exitErr *CheckExitCodeError
		if !errors.As(err, &exitErr) {
			t.Fatalf("expected *CheckExitCodeError, got %T (%v)", err, err)
		}
	})
}

func TestCheckUpdates_RetriesOnPackageManagerLockAndFailsWhenExhausted(t *testing.T) {
	spec := CheckSpec{
		RefreshCommand:            "sudo -n apt-get update -qq",
		CheckCommand:              "apt-get -s upgrade",
		NoUpdatesExitCodes:        []int{0},
		UpdatesAvailableExitCodes: []int{0},
		TimeoutSeconds:            60,
		ParserType:                "REGEX_LINE",
		ParserConfig: map[string]any{
			"pattern": `^Inst\s+(?<packageName>\S+)\s+\[(?<currentVersion>[^\]]+)\]\s+\((?<candidateVersion>\S+)`,
		},
	}
	lockSpec := LockSpec{
		RetryableExitCodes: []int{100},
		LockErrorPattern:   `(Could not get lock|Unable to acquire the dpkg frontend lock)`,
		MaxRetries:         3,
		BackoffSeconds:     []int{1, 2, 3},
	}

	t.Run("succeeds_on_third_attempt", func(t *testing.T) {
		var refreshAttempts atomic.Int32
		res, err := RunCheck(
			context.Background(),
			func(ctx context.Context, cmd string) (string, string, int, error) {
				if cmd == spec.RefreshCommand {
					n := refreshAttempts.Add(1)
					if n < 3 {
						return "", "E: Could not get lock /var/lib/apt/lists/lock", 100, nil
					}
					return "", "", 0, nil
				}
				return "Inst libssl3 [3.0.11-1~deb12u1] (3.0.11-1~deb12u2 Debian-Security:12/stable-security [amd64])\n", "", 0, nil
			},
			spec,
			lockSpec,
			false,
			func(d time.Duration) {},
		)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if res.RetriesUsed != 2 {
			t.Fatalf("retriesUsed = %d, want 2", res.RetriesUsed)
		}
		if len(res.Updates) != 1 || res.Updates[0].PackageName != "libssl3" || !res.Updates[0].IsSecurityUpdate {
			t.Fatalf("unexpected updates: %+v", res.Updates)
		}
	})

	t.Run("exhausted_retries_returns_locked_error", func(t *testing.T) {
		_, err := RunCheck(
			context.Background(),
			func(ctx context.Context, cmd string) (string, string, int, error) {
				return "", "E: Unable to acquire the dpkg frontend lock", 100, nil
			},
			spec,
			lockSpec,
			false,
			func(d time.Duration) {},
		)
		var lockedErr *PackageManagerLockedError
		if !errors.As(err, &lockedErr) {
			t.Fatalf("expected *PackageManagerLockedError, got %T (%v)", err, err)
		}
	})
}

