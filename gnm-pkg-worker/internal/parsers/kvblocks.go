package parsers

import (
	"bufio"
	"strings"
)

func parseKeyValueBlocks(cfg map[string]any, raw string) ([]map[string]string, int, []string, error) {
	sep := ":"
	if s, ok := cfg["keyValueSeparator"].(string); ok && s != "" {
		sep = s
	}

	fieldKeys := make(map[string]string)
	if rawFields, ok := cfg["fields"].(map[string]any); ok {
		for targetField, rawKey := range rawFields {
			if k, okStr := rawKey.(string); okStr && k != "" {
				fieldKeys[targetField] = k
			}
		}
	}
	if len(fieldKeys) == 0 {
		fieldKeys["name"] = "P"
		fieldKeys["version"] = "V"
		fieldKeys["arch"] = "A"
		fieldKeys["sourceName"] = "o"
	}

	var (
		results []map[string]string
		skipped int
		samples []string
		current = make(map[string]string)
	)

	flushBlock := func() {
		if len(current) == 0 {
			return
		}
		rec := make(map[string]string, len(fieldKeys))
		for targetField, blockKey := range fieldKeys {
			if v, ok := current[blockKey]; ok {
				rec[targetField] = v
			}
		}
		if rec["name"] != "" && rec["version"] != "" {
			results = append(results, rec)
		}
		current = make(map[string]string)
	}

	scanner := bufio.NewScanner(strings.NewReader(raw))
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimRight(scanner.Text(), "\r")
		if strings.TrimSpace(line) == "" {
			flushBlock()
			continue
		}
		idx := strings.Index(line, sep)
		if idx <= 0 {
			skipped++
			samples = appendSkippedSample(samples, line)
			continue
		}
		k := strings.TrimSpace(line[:idx])
		v := strings.TrimSpace(line[idx+len(sep):])
		current[k] = v
	}
	flushBlock()

	if err := scanner.Err(); err != nil {
		return nil, skipped, samples, &ParserError{Message: "scanner error: " + err.Error()}
	}
	return results, skipped, samples, nil
}

