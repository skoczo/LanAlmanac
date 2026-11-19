package parsers

import (
	"fmt"
	"strings"
)

const MaxSkippedSamples = 10

// ParserError represents a configuration or input parsing failure (maps to PARSER_ERROR).
type ParserError struct {
	Message string
}

func (e *ParserError) Error() string { return e.Message }

// NormalizedPackage represents an OS-agnostic installed package record.
type NormalizedPackage struct {
	Name          string  `json:"name"`
	Version       string  `json:"version"`
	Arch          *string `json:"arch,omitempty"`
	SourceName    string  `json:"sourceName"`
	SourceVersion string  `json:"sourceVersion"`
	PURL          string  `json:"purl"`
}

// InventoryParseResult holds parsed packages and diagnostic line statistics.
type InventoryParseResult struct {
	Packages           []NormalizedPackage
	TotalParsed        int
	SkippedLines       int
	SampleSkippedLines []string
}

// ParseRecords dispatches raw command output to one of the 4 generic parsers and returns
// normalized field maps along with skipped line diagnostics.
func ParseRecords(parserType string, parserConfig map[string]any, raw string) ([]map[string]string, int, []string, error) {
	switch strings.ToUpper(strings.TrimSpace(parserType)) {
	case "DELIMITED":
		return parseDelimited(parserConfig, raw)
	case "KEY_VALUE_BLOCKS":
		return parseKeyValueBlocks(parserConfig, raw)
	case "JSON_MAPPING":
		return parseJSONMapping(parserConfig, raw)
	case "REGEX_LINE":
		return parseRegexLines(parserConfig, raw)
	default:
		return nil, 0, nil, &ParserError{Message: fmt.Sprintf("unsupported parserType %q", parserType)}
	}
}

// ParseInventory parses raw inventory output into NormalizedPackage records with rendered PURLs.
func ParseInventory(
	parserType string,
	parserConfig map[string]any,
	raw string,
	purlTemplate string,
	osFacts map[string]string,
	previewLimit int,
) (InventoryParseResult, error) {
	if strings.TrimSpace(purlTemplate) == "" {
		return InventoryParseResult{}, &ParserError{Message: "purlTemplate is required"}
	}

	records, skipped, samples, err := ParseRecords(parserType, parserConfig, raw)
	if err != nil {
		return InventoryParseResult{}, err
	}

	packages := make([]NormalizedPackage, 0, len(records))
	for _, rec := range records {
		name := strings.TrimSpace(rec["name"])
		if name == "" {
			name = strings.TrimSpace(rec["packageName"])
		}
		version := strings.TrimSpace(rec["version"])
		if name == "" || version == "" {
			skipped++
			continue
		}

		arch := strings.TrimSpace(rec["arch"])
		var archPtr *string
		if arch != "" {
			archCopy := arch
			archPtr = &archCopy
		}

		sourceName := strings.TrimSpace(rec["sourceName"])
		if sourceName == "" {
			sourceName = name
		}
		sourceVersion := strings.TrimSpace(rec["sourceVersion"])
		if sourceVersion == "" {
			sourceVersion = version
		}

		purl := RenderPURL(purlTemplate, osFacts, name, version, arch, sourceName, sourceVersion)
		packages = append(packages, NormalizedPackage{
			Name:          name,
			Version:       version,
			Arch:          archPtr,
			SourceName:    sourceName,
			SourceVersion: sourceVersion,
			PURL:          purl,
		})
	}

	totalParsed := len(packages)
	if previewLimit > 0 && len(packages) > previewLimit {
		packages = packages[:previewLimit]
	}
	if samples == nil {
		samples = []string{}
	}

	return InventoryParseResult{
		Packages:           packages,
		TotalParsed:        totalParsed,
		SkippedLines:       skipped,
		SampleSkippedLines: samples,
	}, nil
}

func appendSkippedSample(samples []string, line string) []string {
	if len(samples) >= MaxSkippedSamples {
		return samples
	}
	trimmed := strings.TrimSpace(line)
	if len(trimmed) > 200 {
		trimmed = trimmed[:200]
	}
	return append(samples, trimmed)
}

func toInt(v any) (int, bool) {
	switch n := v.(type) {
	case int:
		return n, true
	case int64:
		return int(n), true
	case float64:
		return int(n), true
	default:
		return 0, false
	}
}

