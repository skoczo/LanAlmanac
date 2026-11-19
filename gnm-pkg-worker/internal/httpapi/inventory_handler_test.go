package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestInventoryEndpoint_CollectsAndParsesPackagesOverSSH(t *testing.T) {
	host, port, expectedFP := startHTTPTestSSHServerWithHandler(t, func(cmd string) (string, string, uint32) {
		out := "install ok installed\topenssh-server\t1:9.2p1-2+deb12u3\tamd64\topenssh\t1:9.2p1-2+deb12u3\n" +
			"install ok installed\tbash\t5.2.15-2+b7\tamd64\tbash\t5.2.15-2\n"
		return out, "", 0
	})

	fixedNow := time.Unix(1700000000, 0).UTC()
	handler, err := NewHandler(Config{
		AuthSecret: []byte(testWorkerSecret),
		NowFn:      func() time.Time { return fixedNow },
	})
	if err != nil {
		t.Fatalf("NewHandler: %v", err)
	}

	req := signedJSONRequest(http.MethodPost, "/v1/inventory", map[string]any{
		"executionId": "8f2c1b0a-2222-4222-8222-222222222222",
		"target": map[string]any{
			"transport":             "SSH",
			"host":                  host,
			"port":                  port,
			"username":              "lanalmanac",
			"expectedHostKeySha256": expectedFP,
			"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
		},
		"osFacts": map[string]string{"os.id": "debian"},
		"inventorySpec": map[string]any{
			"command":           "dpkg-query -W",
			"requiresPrivilege": false,
			"timeoutSeconds":    30,
			"parserType":        "DELIMITED",
			"parserConfig": map[string]any{
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
			},
			"purlTemplate": "pkg:deb/{{os.id}}/{{name}}@{{version}}?arch={{arch}}",
		},
	}, fixedNow)

	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (%s)", rec.Code, rec.Body.String())
	}

	var resp InventoryResponseDto
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if resp.TotalParsed != 2 || len(resp.Packages) != 2 {
		t.Fatalf("unexpected inventory response: %+v", resp)
	}
	if resp.Packages[0].Name != "openssh-server" || resp.Packages[0].SourceName != "openssh" {
		t.Errorf("unexpected package[0]: %+v", resp.Packages[0])
	}

	t.Run("unsupported_elevation_mode_returns_502", func(t *testing.T) {
		reqSudoPwd := signedJSONRequest(http.MethodPost, "/v1/inventory", map[string]any{
			"executionId": "8f2c1b0a-2222-4222-8222-222222222223",
			"target": map[string]any{
				"transport":             "SSH",
				"host":                  host,
				"port":                  port,
				"username":              "lanalmanac",
				"expectedHostKeySha256": expectedFP,
				"auth":                  map[string]any{"type": "PASSWORD", "secret": "s3cr3t"},
			},
			"osFacts": map[string]string{"os.id": "debian"},
			"inventorySpec": map[string]any{
				"command":           "dpkg-query -W",
				"requiresPrivilege": true,
				"parserType":        "DELIMITED",
				"parserConfig":      map[string]any{},
				"purlTemplate":      "pkg:deb/debian/{{name}}@{{version}}",
			},
			"privilegeSpec": map[string]any{
				"elevationMode": "SUDO_PASSWORD",
			},
		}, fixedNow)

		recSudoPwd := httptest.NewRecorder()
		handler.ServeHTTP(recSudoPwd, reqSudoPwd)
		if recSudoPwd.Code != http.StatusBadGateway {
			t.Fatalf("expected 502, got %d (%s)", recSudoPwd.Code, recSudoPwd.Body.String())
		}
	})
}
