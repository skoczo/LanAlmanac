package parsers

import (
	"strings"
)

// RenderPURL renders a Package URL template using collected osFacts and package attributes.
func RenderPURL(
	template string,
	osFacts map[string]string,
	name, version, arch, sourceName, sourceVersion string,
) string {
	out := template
	for k, v := range osFacts {
		out = strings.ReplaceAll(out, "{{os."+strings.TrimPrefix(k, "os.")+"}}", sanitizePURLSegment(v))
		out = strings.ReplaceAll(out, "{{"+k+"}}", sanitizePURLSegment(v))
	}

	replacements := map[string]string{
		"{{name}}":          sanitizePURLSegment(name),
		"{{version}}":       sanitizePURLVersion(version),
		"{{arch}}":          sanitizePURLSegment(arch),
		"{{sourceName}}":    sanitizePURLSegment(sourceName),
		"{{sourceVersion}}": sanitizePURLVersion(sourceVersion),
	}
	for placeholder, val := range replacements {
		out = strings.ReplaceAll(out, placeholder, val)
	}

	// Clean trailing empty ?arch= if arch was not present
	out = strings.TrimSuffix(out, "?arch=")
	return out
}

func sanitizePURLSegment(s string) string {
	s = strings.TrimSpace(s)
	s = strings.ReplaceAll(s, " ", "%20")
	return s
}

func sanitizePURLVersion(v string) string {
	v = strings.TrimSpace(v)
	v = strings.ReplaceAll(v, " ", "%20")
	return v
}

