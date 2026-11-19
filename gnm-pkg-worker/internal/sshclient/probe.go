package sshclient

import (
	"context"
	"strings"
	"time"
)

const genericPOSIXProbeScript = `printf 'GNM_KERNEL=%s\n' "$(uname -s 2>/dev/null || true)"
printf 'GNM_ARCH=%s\n' "$(uname -m 2>/dev/null || true)"
printf 'GNM_RUNNING_KERNEL=%s\n' "$(uname -r 2>/dev/null || true)"
if [ -r /etc/os-release ]; then
  printf 'GNM_OS_RELEASE_START\n'
  cat /etc/os-release
  printf '\nGNM_OS_RELEASE_END\n'
elif [ -r /usr/lib/os-release ]; then
  printf 'GNM_OS_RELEASE_START\n'
  cat /usr/lib/os-release
  printf '\nGNM_OS_RELEASE_END\n'
fi
if [ -r /etc/alpine-release ]; then
  printf 'GNM_ALPINE_RELEASE=%s\n' "$(cat /etc/alpine-release)"
fi`

// ProbeResult contains normalized OS facts and optional verifyCommand outcomes.
type ProbeResult struct {
	OSFacts       map[string]string
	VerifyResults map[string]bool
}

// Probe executes a single generic read-only POSIX probe command and optional verification commands.
func Probe(ctx context.Context, conn *Conn, verifyCommands map[string]string) (ProbeResult, error) {
	probeCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()

	res, err := conn.ExecBuffered(probeCtx, genericPOSIXProbeScript, map[string]string{"LC_ALL": "C"}, 64*1024)
	if err != nil {
		return ProbeResult{}, err
	}

	facts := ParseProbeOutput(res.Stdout)
	verifyResults := make(map[string]bool, len(verifyCommands))
	for slug, cmd := range verifyCommands {
		trimmed := strings.TrimSpace(cmd)
		if trimmed == "" {
			continue
		}
		vCtx, vCancel := context.WithTimeout(ctx, 10*time.Second)
		vRes, vErr := conn.ExecBuffered(vCtx, trimmed, map[string]string{"LC_ALL": "C"}, 64*1024)
		vCancel()
		verifyResults[slug] = (vErr == nil && vRes.ExitCode == 0)
	}

	return ProbeResult{
		OSFacts:       facts,
		VerifyResults: verifyResults,
	}, nil
}

// ParseProbeOutput parses the raw output of the generic POSIX probe into normalized OS facts.
func ParseProbeOutput(raw string) map[string]string {
	facts := make(map[string]string)
	inOSRelease := false
	var alpineRelease string

	for _, rawLine := range strings.Split(raw, "\n") {
		line := strings.TrimSpace(strings.TrimRight(rawLine, "\r"))
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if line == "GNM_OS_RELEASE_START" {
			inOSRelease = true
			continue
		}
		if line == "GNM_OS_RELEASE_END" {
			inOSRelease = false
			continue
		}

		idx := strings.IndexByte(line, '=')
		if idx <= 0 {
			continue
		}
		key := strings.TrimSpace(line[:idx])
		val := unquoteOSReleaseValue(strings.TrimSpace(line[idx+1:]))

		if inOSRelease {
			switch key {
			case "ID":
				facts["os.id"] = strings.ToLower(val)
			case "ID_LIKE":
				facts["os.id_like"] = strings.ToLower(val)
			case "VERSION_ID":
				facts["os.version_id"] = val
			case "VERSION_CODENAME":
				facts["os.codename"] = strings.ToLower(val)
			case "PRETTY_NAME":
				facts["os.pretty_name"] = val
			}
			continue
		}

		switch key {
		case "GNM_KERNEL":
			if val != "" {
				facts["kernel"] = val
			}
		case "GNM_ARCH":
			if val != "" {
				facts["arch"] = val
			}
		case "GNM_RUNNING_KERNEL":
			if val != "" {
				facts["running_kernel"] = val
			}
		case "GNM_ALPINE_RELEASE":
			alpineRelease = val
		}
	}

	// Fallback if /etc/os-release did not populate os.id but /etc/alpine-release was present.
	if facts["os.id"] == "" && alpineRelease != "" {
		facts["os.id"] = "alpine"
		if facts["os.version_id"] == "" {
			facts["os.version_id"] = alpineRelease
		}
	}

	populateVersionSegments(facts)
	populateCombinedIDTokens(facts)
	return facts
}

func populateVersionSegments(facts map[string]string) {
	v := facts["os.version_id"]
	if v == "" {
		return
	}
	parts := strings.Split(v, ".")
	if len(parts) >= 1 && parts[0] != "" {
		facts["os.version_major"] = parts[0]
	}
	if len(parts) >= 2 && parts[0] != "" && parts[1] != "" {
		facts["os.version_major_minor"] = parts[0] + "." + parts[1]
	}
}

func populateCombinedIDTokens(facts map[string]string) {
	seen := make(map[string]bool)
	var tokens []string
	for _, field := range []string{facts["os.id"], facts["os.id_like"]} {
		for _, tok := range strings.Fields(field) {
			if !seen[tok] {
				seen[tok] = true
				tokens = append(tokens, tok)
			}
		}
	}
	if len(tokens) > 0 {
		facts["os.id_or_like"] = strings.Join(tokens, " ")
	}
}

func unquoteOSReleaseValue(s string) string {
	if len(s) >= 2 {
		if (s[0] == '"' && s[len(s)-1] == '"') || (s[0] == '\'' && s[len(s)-1] == '\'') {
			inner := s[1 : len(s)-1]
			inner = strings.ReplaceAll(inner, `\"`, `"`)
			inner = strings.ReplaceAll(inner, `\\`, `\`)
			return inner
		}
	}
	return s
}

