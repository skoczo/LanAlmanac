package updates

import (
	"context"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/parsers"
)

// SudoPermissionRequiredError indicates sudo -n rejected execution because a password or sudoers entry is missing.
type SudoPermissionRequiredError struct {
	ExitCode int
	Message  string
}

func (e *SudoPermissionRequiredError) Error() string { return e.Message }

// PackageManagerLockedError indicates the remote package manager lock remained held after all retries.
type PackageManagerLockedError struct {
	ExitCode int
	Message  string
}

func (e *PackageManagerLockedError) Error() string { return e.Message }

// CheckExitCodeError indicates the update check command exited with an unexpected exit code.
type CheckExitCodeError struct {
	ExitCode int
	Message  string
}

func (e *CheckExitCodeError) Error() string { return e.Message }

type AvailableUpdate struct {
	PackageName      string  `json:"packageName"`
	Arch             *string `json:"arch,omitempty"`
	CurrentVersion   *string `json:"currentVersion,omitempty"`
	CandidateVersion string  `json:"candidateVersion"`
	IsSecurityUpdate bool    `json:"isSecurityUpdate"`
}

type CheckSpec struct {
	RefreshCommand            string
	CheckCommand              string
	NoUpdatesExitCodes        []int
	UpdatesAvailableExitCodes []int
	TimeoutSeconds            int
	ParserType                string
	ParserConfig              map[string]any
}

type LockSpec struct {
	RetryableExitCodes []int
	LockErrorPattern   string
	MaxRetries         int
	BackoffSeconds     []int
}

type CheckResult struct {
	Updates     []AvailableUpdate
	ExitCode    int
	RetriesUsed int
}

type ExecFunc func(ctx context.Context, cmd string) (stdout string, stderr string, exitCode int, err error)

// RunCheck executes the optional cache refresh and dry-run update check with lock retry and exit-code handling.
func RunCheck(
	ctx context.Context,
	execFn ExecFunc,
	spec CheckSpec,
	lockSpec LockSpec,
	skipRefresh bool,
	sleepFn func(time.Duration),
) (CheckResult, error) {
	if sleepFn == nil {
		sleepFn = time.Sleep
	}

	var lockRe *regexp.Regexp
	if strings.TrimSpace(lockSpec.LockErrorPattern) != "" {
		if compiled, err := regexp.Compile(lockSpec.LockErrorPattern); err == nil {
			lockRe = compiled
		}
	}

	retriesUsed := 0
	execWithRetry := func(cmd string, allowedExitCodes []int) (string, string, int, error) {
		maxRetries := lockSpec.MaxRetries
		if maxRetries < 0 {
			maxRetries = 0
		}
		for attempt := 0; ; attempt++ {
			stdout, stderr, exitCode, err := execFn(ctx, cmd)
			if err != nil {
				return stdout, stderr, exitCode, err
			}
			if isSudoStderr(stderr) && exitCode != 0 {
				return stdout, stderr, exitCode, &SudoPermissionRequiredError{
					ExitCode: exitCode,
					Message:  "sudo non-interactive permission denied",
				}
			}

			combined := stdout + "\n" + stderr
			isLocked := lockRe != nil && lockRe.MatchString(combined)
			if isLocked {
				if attempt < maxRetries {
					retriesUsed++
					sleepFn(backoffDuration(lockSpec.BackoffSeconds, attempt))
					continue
				}
				return stdout, stderr, exitCode, &PackageManagerLockedError{
					ExitCode: exitCode,
					Message:  "Remote package manager is locked after maximum retries",
				}
			}

			if slices.Contains(allowedExitCodes, exitCode) {
				return stdout, stderr, exitCode, nil
			}
			return stdout, stderr, exitCode, &CheckExitCodeError{
				ExitCode: exitCode,
				Message:  fmt.Sprintf("command exited with unexpected status %d", exitCode),
			}
		}
	}

	if !skipRefresh && strings.TrimSpace(spec.RefreshCommand) != "" {
		if _, _, _, err := execWithRetry(spec.RefreshCommand, []int{0}); err != nil {
			return CheckResult{}, err
		}
	}

	allowedCheckCodes := mergeExitCodes(spec.NoUpdatesExitCodes, spec.UpdatesAvailableExitCodes)
	stdout, _, exitCode, err := execWithRetry(spec.CheckCommand, allowedCheckCodes)
	if err != nil {
		return CheckResult{}, err
	}

	records, _, _, err := parsers.ParseRecords(spec.ParserType, spec.ParserConfig, stdout)
	if err != nil {
		return CheckResult{}, err
	}

	updates := make([]AvailableUpdate, 0, len(records))
	for _, rec := range records {
		pkgName := strings.TrimSpace(rec["packageName"])
		if pkgName == "" {
			pkgName = strings.TrimSpace(rec["name"])
		}
		candidate := strings.TrimSpace(rec["candidateVersion"])
		if candidate == "" {
			candidate = strings.TrimSpace(rec["version"])
		}
		if pkgName == "" || candidate == "" {
			continue
		}

		var archPtr *string
		if arch := strings.TrimSpace(rec["arch"]); arch != "" {
			archCopy := arch
			archPtr = &archCopy
		}
		var currPtr *string
		if curr := strings.TrimSpace(rec["currentVersion"]); curr != "" {
			currCopy := curr
			currPtr = &currCopy
		}

		isSec := strings.Contains(strings.ToLower(stdout), "security")
		updates = append(updates, AvailableUpdate{
			PackageName:      pkgName,
			Arch:             archPtr,
			CurrentVersion:   currPtr,
			CandidateVersion: candidate,
			IsSecurityUpdate: isSec,
		})
	}

	return CheckResult{
		Updates:     updates,
		ExitCode:    exitCode,
		RetriesUsed: retriesUsed,
	}, nil
}

func isSudoStderr(stderr string) bool {
	lower := strings.ToLower(stderr)
	return strings.Contains(lower, "sudo: a password is required") ||
		strings.Contains(lower, "sudo: a terminal is required") ||
		strings.Contains(lower, "is not in the sudoers file") ||
		strings.Contains(lower, "not allowed to execute")
}

func backoffDuration(backoffs []int, attempt int) time.Duration {
	if len(backoffs) == 0 {
		return 15 * time.Second
	}
	idx := attempt
	if idx >= len(backoffs) {
		idx = len(backoffs) - 1
	}
	sec := backoffs[idx]
	if sec <= 0 {
		sec = 10
	}
	return time.Duration(sec) * time.Second
}

func mergeExitCodes(a, b []int) []int {
	if len(a) == 0 && len(b) == 0 {
		return []int{0}
	}
	seen := make(map[int]bool)
	var out []int
	for _, code := range append(append([]int(nil), a...), b...) {
		if !seen[code] {
			seen[code] = true
			out = append(out, code)
		}
	}
	return out
}

