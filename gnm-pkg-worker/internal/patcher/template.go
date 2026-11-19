package patcher

import (
	"fmt"
	"regexp"
	"strings"
)

var validPackageNamePattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9.+:~_-]{0,127}$`)

// InvalidPackageNameError indicates a package name failed strict allowlist validation.
type InvalidPackageNameError struct {
	PackageName string
	Message     string
}

func (e *InvalidPackageNameError) Error() string { return e.Message }

// ValidatePackageName enforces the strict allowlist regex on a single package token.
func ValidatePackageName(pkg string) error {
	if !validPackageNamePattern.MatchString(pkg) {
		return &InvalidPackageNameError{
			PackageName: pkg,
			Message:     fmt.Sprintf("invalid or forbidden package name %q", pkg),
		}
	}
	return nil
}

// RenderSelectedUpgradeCommand validates every package name and substitutes {{packages}}
// with POSIX single-quoted tokens.
func RenderSelectedUpgradeCommand(template string, packages []string) (string, error) {
	if strings.TrimSpace(template) == "" || !strings.Contains(template, "{{packages}}") {
		return "", fmt.Errorf("selectedUpgradeTemplate must contain {{packages}}")
	}
	if len(packages) == 0 {
		return "", &InvalidPackageNameError{
			Message: "at least one package name is required for SELECTED_PACKAGES mode",
		}
	}

	quoted := make([]string, 0, len(packages))
	for _, pkg := range packages {
		if err := ValidatePackageName(pkg); err != nil {
			return "", err
		}
		quoted = append(quoted, "'"+pkg+"'")
	}

	return strings.ReplaceAll(template, "{{packages}}", strings.Join(quoted, " ")), nil
}

