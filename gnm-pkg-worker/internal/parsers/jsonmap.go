package parsers

import (
	"encoding/json"
	"fmt"
	"strings"
)

func parseJSONMapping(cfg map[string]any, raw string) ([]map[string]string, int, []string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, 0, nil, nil
	}

	fieldPaths := make(map[string]string)
	if rawFields, ok := cfg["fields"].(map[string]any); ok {
		for targetField, rawKey := range rawFields {
			if k, okStr := rawKey.(string); okStr && k != "" {
				fieldPaths[targetField] = k
			}
		}
	}
	if len(fieldPaths) == 0 {
		fieldPaths["name"] = "name"
		fieldPaths["version"] = "version"
		fieldPaths["arch"] = "arch"
	}

	var items []map[string]any
	if strings.HasPrefix(trimmed, "[") {
		if err := json.Unmarshal([]byte(trimmed), &items); err != nil {
			return nil, 0, nil, &ParserError{Message: "invalid JSON array output: " + err.Error()}
		}
	} else {
		var obj map[string]any
		if err := json.Unmarshal([]byte(trimmed), &obj); err != nil {
			return nil, 0, nil, &ParserError{Message: "invalid JSON object output: " + err.Error()}
		}
		if itemsField, ok := cfg["itemsField"].(string); ok && itemsField != "" {
			if arr, okArr := obj[itemsField].([]any); okArr {
				for _, el := range arr {
					if m, okMap := el.(map[string]any); okMap {
						items = append(items, m)
					}
				}
			}
		} else {
			items = append(items, obj)
		}
	}

	results := make([]map[string]string, 0, len(items))
	skipped := 0
	for _, item := range items {
		rec := make(map[string]string, len(fieldPaths))
		for targetField, jsonKey := range fieldPaths {
			if v, ok := item[jsonKey]; ok && v != nil {
				rec[targetField] = fmt.Sprint(v)
			}
		}
		if rec["name"] == "" && rec["packageName"] == "" {
			skipped++
			continue
		}
		results = append(results, rec)
	}
	return results, skipped, nil, nil
}

