package sshclient

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestParseProbeOutput_Distributions(t *testing.T) {
	cases := []struct {
		name     string
		raw      string
		wantFact map[string]string
	}{
		{
			name: "debian_12",
			raw: `GNM_KERNEL=Linux
GNM_ARCH=x86_64
GNM_RUNNING_KERNEL=6.1.0-21-amd64
GNM_OS_RELEASE_START
PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
NAME="Debian GNU/Linux"
VERSION_ID="12"
VERSION="12 (bookworm)"
VERSION_CODENAME=bookworm
ID=debian
GNM_OS_RELEASE_END
`,
			wantFact: map[string]string{
				"kernel":               "Linux",
				"arch":                 "x86_64",
				"running_kernel":       "6.1.0-21-amd64",
				"os.id":                "debian",
				"os.id_or_like":        "debian",
				"os.version_id":        "12",
				"os.version_major":     "12",
				"os.codename":          "bookworm",
				"os.pretty_name":       "Debian GNU/Linux 12 (bookworm)",
			},
		},
		{
			name: "ubuntu_24_04",
			raw: `GNM_KERNEL=Linux
GNM_ARCH=aarch64
GNM_RUNNING_KERNEL=6.8.0-45-generic
GNM_OS_RELEASE_START
NAME="Ubuntu"
VERSION_ID="24.04"
ID=ubuntu
ID_LIKE=debian
VERSION_CODENAME=noble
GNM_OS_RELEASE_END
`,
			wantFact: map[string]string{
				"os.id":                  "ubuntu",
				"os.id_like":             "debian",
				"os.id_or_like":          "ubuntu debian",
				"os.version_id":          "24.04",
				"os.version_major":       "24",
				"os.version_major_minor": "24.04",
				"os.codename":            "noble",
			},
		},
		{
			name: "rocky_9",
			raw: `GNM_KERNEL=Linux
GNM_ARCH=x86_64
GNM_RUNNING_KERNEL=5.14.0-427.el9.x86_64
GNM_OS_RELEASE_START
ID="rocky"
ID_LIKE="rhel centos fedora"
VERSION_ID="9.4"
GNM_OS_RELEASE_END
`,
			wantFact: map[string]string{
				"os.id":                  "rocky",
				"os.id_like":             "rhel centos fedora",
				"os.id_or_like":          "rocky rhel centos fedora",
				"os.version_id":          "9.4",
				"os.version_major":       "9",
				"os.version_major_minor": "9.4",
			},
		},
		{
			name: "alpine_3_20",
			raw: `GNM_KERNEL=Linux
GNM_ARCH=x86_64
GNM_RUNNING_KERNEL=6.6.32-0-lts
GNM_OS_RELEASE_START
NAME="Alpine Linux"
ID=alpine
VERSION_ID=3.20.3
GNM_OS_RELEASE_END
GNM_ALPINE_RELEASE=3.20.3
`,
			wantFact: map[string]string{
				"os.id":                  "alpine",
				"os.version_id":          "3.20.3",
				"os.version_major":       "3",
				"os.version_major_minor": "3.20",
			},
		},
		{
			name: "fallback_without_os_release_alpine",
			raw: `GNM_KERNEL=Linux
GNM_ARCH=armv7l
GNM_RUNNING_KERNEL=5.15.0
GNM_ALPINE_RELEASE=3.19.1
`,
			wantFact: map[string]string{
				"kernel":                 "Linux",
				"arch":                   "armv7l",
				"os.id":                  "alpine",
				"os.id_or_like":          "alpine",
				"os.version_id":          "3.19.1",
				"os.version_major":       "3",
				"os.version_major_minor": "3.19",
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			facts := ParseProbeOutput(tc.raw)
			for k, want := range tc.wantFact {
				if got := facts[k]; got != want {
					t.Errorf("facts[%q] = %q, want %q", k, got, want)
				}
			}
		})
	}
}

func TestProbe_ExecutesGenericProbeAndVerifyCommands(t *testing.T) {
	srv := startMockSSHServer(t, func(s *mockSSHServer) {
		s.execHandler = func(cmd string) (string, string, uint32) {
			if strings.Contains(cmd, "GNM_OS_RELEASE_START") {
				return "GNM_KERNEL=Linux\nGNM_ARCH=x86_64\nGNM_RUNNING_KERNEL=6.1.0\nGNM_OS_RELEASE_START\nID=debian\nVERSION_ID=\"12\"\nGNM_OS_RELEASE_END\n", "", 0
			}
			if strings.Contains(cmd, "dpkg-query") {
				return "", "", 0
			}
			if strings.Contains(cmd, "dnf") {
				return "", "not found", 127
			}
			return "", "", 1
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
		ConnectTimeout:        5 * time.Second,
	})
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	defer conn.Close()

	res, err := Probe(ctx, conn, map[string]string{
		"debian-apt": "command -v dpkg-query >/dev/null 2>&1",
		"rhel-dnf":   "command -v dnf >/dev/null 2>&1",
	})
	if err != nil {
		t.Fatalf("Probe failed: %v", err)
	}

	if res.OSFacts["os.id"] != "debian" || res.OSFacts["os.version_major"] != "12" {
		t.Fatalf("unexpected osFacts: %+v", res.OSFacts)
	}
	if !res.VerifyResults["debian-apt"] {
		t.Errorf("expected debian-apt verifyCommand to succeed")
	}
	if res.VerifyResults["rhel-dnf"] {
		t.Errorf("expected rhel-dnf verifyCommand to return false")
	}
}

