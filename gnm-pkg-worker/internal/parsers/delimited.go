package parsers

import (
	"bufio"
	"strings"
)

func parseDelimited(cfg map[string]any, raw string) ([]map[string]string, int, []string, error) {
	delimiter := "\t"
	if d, ok := cfg["delimiter"].(string); ok && d != "" {
		delimiter = d
	}

	hasFilter := false
	filterCol := 0
	filterEquals := ""
	if fc, ok := toInt(cfg["filterColumn"]); ok {
		if fe, okStr := cfg["filterEquals"].(string); okStr && fe != "" {
			hasFilter = true
			filterCol = fc
			filterEquals = fe
		}
	}

	stripZeroEpoch, _ := cfg["stripLeadingZeroEpoch"].(bool)

	colMap := make(map[string]int)
	if rawCols, ok := cfg["columns"].(map[string]any); ok {
		for field, rawIdx := range rawCols {
			if idx, okIdx := toInt(rawIdx); okIdx && idx >= 0 {
				colMap[field] = idx
			}
		}
	}
	if len(colMap) == 0 {
		colMap["name"] = 0
		colMap["version"] = 1
	}

	maxRequiredIdx := 0
	for _, field := range []string{"name", "version"} {
		if idx, ok := colMap[field]; ok && idx > maxRequiredIdx {
			maxRequiredIdx = idx
		}
	}

	var (
		results []map[string]string
		skipped int
		samples []string
	)

	scanner := bufio.NewScanner(strings.NewReader(raw))
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimRight(scanner.Text(), "\r")
		if strings.TrimSpace(line) == "" {
			continue
		}

		parts := strings.Split(line, delimiter)
		if len(parts) <= maxRequiredIdx {
			skipped++
			samples = appendSkippedSample(samples, line)
			continue
		}

		if hasFilter {
			if filterCol >= len(parts) || strings.TrimSpace(parts[filterCol]) != filterEquals {
				// Filtered out intentionally (e.g. deinstall ok config-files) — not a parser error.
				continue
			}
		}

		rec := make(map[string]string, len(colMap))
		for field, idx := range colMap {
			if idx < len(parts) {
				val := strings.TrimSpace(parts[idx])
				if field == "version" && stripZeroEpoch {
					val = strings.TrimPrefix(val, "0:")
					val = strings.TrimPrefix(val, "(none):")
				}
				rec[field] = val
			}
		}
		// dpkg-query source:Package may contain "openssl (3.0.11-1)" when version differs
		if src := rec["sourceName"]; strings.Contains(src, " ") {
			srcFields := strings.Fields(src)
			rec["sourceName"] = srcFields[0]
		}
		results = append(results, rec)
	}
	if err := scanner.Err(); err != nil {
		return nil, skipped, samples, &ParserError{Message: "scanner error: " + err.Error()}
	}
	return results, skipped, samples, nil
}

