package parsers

import (
	"strings"
	"testing"
)

func TestParseInventory_DelimitedDpkgAndRpm(t *testing.T) {
	t.Run("dpkg_filters_deinstall_and_populates_source_fallback", func(t *testing.T) {
		raw := strings.Join([]string{
			"install ok installed\tlibssl3\t3.0.11-1~deb12u2\tamd64\topenssl\t3.0.11-1~deb12u2",
			"install ok installed\tlibssl3\t3.0.11-1~deb12u2\ti386\topenssl\t3.0.11-1~deb12u2",
			"deinstall ok config-files\told-pkg\t1.0.0\tamd64\told-pkg\t1.0.0",
			"install ok installed\tcurl\t7.88.1-10+deb12u5\tamd64\t\t",
			"malformed-line-without-tabs",
		}, "\n")

		cfg := map[string]any{
			"delimiter":    "\t",
			"filterColumn": 0,
			"filterEquals": "install ok installed",
			"columns": map[string]any{
				"name":          1,
				"version":       2,
				"arch":          3,
				"sourceName":    4,
				"sourceVersion": 5,
			},
		}

		res, err := ParseInventory(
			"DELIMITED",
			cfg,
			raw,
			"pkg:deb/{{os.id}}/{{name}}@{{version}}?arch={{arch}}",
			map[string]string{"os.id": "debian"},
			0,
		)
		if err != nil {
			t.Fatalf("ParseInventory failed: %v", err)
		}

		if res.TotalParsed != 3 {
			t.Fatalf("totalParsed = %d, want 3 (got packages: %+v)", res.TotalParsed, res.Packages)
		}
		if res.SkippedLines != 1 {
			t.Fatalf("skippedLines = %d, want 1", res.SkippedLines)
		}
		if res.Packages[0].Name != "libssl3" || res.Packages[0].SourceName != "openssl" {
			t.Errorf("pkg[0] = %+v", res.Packages[0])
		}
		if res.Packages[0].PURL != "pkg:deb/debian/libssl3@3.0.11-1~deb12u2?arch=amd64" {
			t.Errorf("pkg[0].PURL = %q", res.Packages[0].PURL)
		}
		// curl had empty source columns -> must fallback to name & version
		if res.Packages[2].Name != "curl" || res.Packages[2].SourceName != "curl" || res.Packages[2].SourceVersion != "7.88.1-10+deb12u5" {
			t.Errorf("pkg[2] fallback failed: %+v", res.Packages[2])
		}
	})

	t.Run("rpm_strips_leading_zero_epoch_and_preview_limit", func(t *testing.T) {
		raw := strings.Join([]string{
			"bash\t0:5.1.8-9.el9\tx86_64\tbash-5.1.8-9.el9.src.rpm",
			"bind-libs\t32:9.16.23-18.el9\tx86_64\tbind-9.16.23-18.el9.src.rpm",
		}, "\n")

		cfg := map[string]any{
			"delimiter":             "\t",
			"stripLeadingZeroEpoch": true,
			"columns": map[string]any{
				"name":       0,
				"version":    1,
				"arch":       2,
				"sourceName": 3,
			},
		}

		res, err := ParseInventory(
			"DELIMITED",
			cfg,
			raw,
			"pkg:rpm/{{os.id}}/{{name}}@{{version}}?arch={{arch}}",
			map[string]string{"os.id": "rocky"},
			1,
		)
		if err != nil {
			t.Fatalf("ParseInventory failed: %v", err)
		}
		if res.TotalParsed != 2 {
			t.Fatalf("totalParsed = %d, want 2", res.TotalParsed)
		}
		if len(res.Packages) != 1 {
			t.Fatalf("preview limit 1 not applied, got %d packages", len(res.Packages))
		}
		if res.Packages[0].Version != "5.1.8-9.el9" {
			t.Errorf("expected 0: epoch stripped, got %q", res.Packages[0].Version)
		}
	})
}

func TestParseInventory_KeyValueBlocksAlpine(t *testing.T) {
	raw := `C:Q1234=
P:musl
V:1.2.5-r0
A:x86_64
o:musl

C:Q5678=
P:ssl_client
V:1.36.1-r29
A:x86_64
o:busybox

`
	cfg := map[string]any{
		"keyValueSeparator": ":",
		"fields": map[string]any{
			"name":       "P",
			"version":    "V",
			"arch":       "A",
			"sourceName": "o",
		},
	}

	res, err := ParseInventory(
		"KEY_VALUE_BLOCKS",
		cfg,
		raw,
		"pkg:apk/alpine/{{name}}@{{version}}?arch={{arch}}",
		map[string]string{"os.id": "alpine"},
		0,
	)
	if err != nil {
		t.Fatalf("ParseInventory KEY_VALUE_BLOCKS failed: %v", err)
	}
	if len(res.Packages) != 2 {
		t.Fatalf("got %d packages, want 2", len(res.Packages))
	}
	if res.Packages[1].Name != "ssl_client" || res.Packages[1].SourceName != "busybox" {
		t.Errorf("unexpected second package: %+v", res.Packages[1])
	}
	if res.Packages[1].PURL != "pkg:apk/alpine/ssl_client@1.36.1-r29?arch=x86_64" {
		t.Errorf("unexpected PURL: %q", res.Packages[1].PURL)
	}
}

func TestParseInventory_JsonMappingAndRegexLine(t *testing.T) {
	t.Run("json_mapping", func(t *testing.T) {
		raw := `[{"pkg_name":"nginx","pkg_ver":"1.24.0","cpu":"amd64"},{"pkg_name":"redis","pkg_ver":"7.2.4","cpu":"amd64"}]`
		cfg := map[string]any{
			"fields": map[string]any{
				"name":    "pkg_name",
				"version": "pkg_ver",
				"arch":    "cpu",
			},
		}
		res, err := ParseInventory(
			"JSON_MAPPING",
			cfg,
			raw,
			"pkg:generic/{{name}}@{{version}}?arch={{arch}}",
			nil,
			0,
		)
		if err != nil {
			t.Fatalf("JSON_MAPPING failed: %v", err)
		}
		if len(res.Packages) != 2 || res.Packages[0].Name != "nginx" {
			t.Fatalf("unexpected packages: %+v", res.Packages)
		}
	})

	t.Run("regex_line_bsd", func(t *testing.T) {
		raw := "# Header comment\ncurl-8.7.1_1\tsome description\nsudo-1.9.15p5\tanother description\n"
		cfg := map[string]any{
			"pattern": `^(?<name>[a-zA-Z0-9_+-]+)-(?<version>[0-9][^\t]*)\t`,
		}
		res, err := ParseInventory(
			"REGEX_LINE",
			cfg,
			raw,
			"pkg:freebsd/{{name}}@{{version}}",
			nil,
			0,
		)
		if err != nil {
			t.Fatalf("REGEX_LINE failed: %v", err)
		}
		if len(res.Packages) != 2 || res.Packages[0].Name != "curl" || res.Packages[0].Version != "8.7.1_1" {
			t.Fatalf("unexpected packages: %+v", res.Packages)
		}
		if res.SkippedLines != 1 {
			t.Errorf("skippedLines = %d, want 1", res.SkippedLines)
		}
	})
}

