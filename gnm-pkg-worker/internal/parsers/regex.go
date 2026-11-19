package parsers

import (
	"bufio"
	"regexp"
	"strings"
)

func parseRegexLines(cfg map[string]any, raw string) ([]map[string]string, int, []string, error) {
	patStr, _ := cfg["pattern"].(string)
	patStr = strings.TrimSpace(patStr)
	if patStr == "" {
		return nil, 0, nil, &ParserError{Message: "REGEX_LINE parser requires parserConfig.pattern"}
	}
	if len(patStr) > 512 {
		return nil, 0, nil, &ParserError{Message: "regex pattern exceeds maximum length of 512"}
	}

	re, err := regexp.Compile(patStr)
	if err != nil {
		return nil, 0, nil, &ParserError{Message: "invalid regex pattern: " + err.Error()}
	}

	groupNames := re.SubexpNames()
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

		matches := re.FindStringSubmatch(line)
		if matches == nil {
			skipped++
			samples = appendSkippedSample(samples, line)
			continue
		}

		rec := make(map[string]string, len(groupNames))
		for i, groupName := range groupNames {
			if i > 0 && groupName != "" && i < len(matches) {
				rec[groupName] = strings.TrimSpace(matches[i])
			}
		}
		results = append(results, rec)
	}
	if err := scanner.Err(); err != nil {
		return nil, skipped, samples, &ParserError{Message: "scanner error: " + err.Error()}
	}
	return results, skipped, samples, nil
}

