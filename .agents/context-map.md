# Context Map — Read This Instead of Exploring the Repository

Goal: Answer "where is X located" without calling `read_file` on large files.
Update this file whenever you move or add an important file.

## What NOT to Read (Token Waste)

| File | Size | What to Do Instead |
|:---|:---|:---|
| `docs/architecture.md` | 1778 lines | Sections 1-4 describe the original vision, not current code. Search for specific classes. |
| `docs/progress.md` | ~67 lines | Only high-level phase status. No code details. |
| `frontend/package-lock.json` | huge | `grep` a specific package only if genuinely needed. |
| `frontend/src/pages/DeviceDetail.tsx` | ~1700 lines | `search_files` by handler name or UI label. |
| `gnm-app/src/main/java/com/gnm/service/BackupService.java` | 521 lines | Methods: 22-73 export/import, 348-386 pg_dump/psql, 431-483 crypto. |
| `test_execution_*.log`, `gnm.log`, `*.log` | large | Never read in full. `grep` for the specific error. |

## System Entry Order for New Tasks

1. `.agents/invariants.md` (always first)
2. This file — section for the relevant domain
3. The specific class, via `search_files` by method name

## Backend — `gnm-app/src/main/java/com/gnm/`

### Security & Auth
| File | Contents | Notes |
|:---|:---|:---|
| `auth/PasswordService.java` | Argon2id hash + verify, `$argon2id$v=19$m=...` format | Good quality reference pattern. |
| `auth/UserBootstrap.java` | Admin seed from env vars, `mustChangePassword=true` | Default `admin/admin` — to be removed in Stage 4. |
| `auth/DynamicOidcTenantResolver.java` | Reads `oidc.*` from `GlobalSetting` on each request | **Escalation risk** — see hardening plan 1.7 / Stage 2. |
| `auth/AuthTicketService.java` | Single-use 30s TTL tickets (`issue`, `consume`, `purgeExpired`) | Used for SSE/WS handshakes. |
| `resource/AuthTicketResource.java` | `POST /api/auth/ticket` (`@RolesAllowed`) | Issues tickets for `sse-update`, `sse-patch-job`, `ws-terminal`, `ws-events`. |
| `resource/LocalAuthResource.java` | login (50), change-password (88), `extractUsernameFromToken` (141) | 141: manual Base64 JWT decode without signature check — Stage 1.7. |

### Vault
| File | Contents | Notes |
|:---|:---|:---|
| `service/VaultEngine.java` | Argon2 KEK + AES-GCM, `keys/.vault_master`, unseal (110), encrypt (168), decrypt (186) | Missing zeroization/auto-lock — Stage 4. |
| `resource/VaultResource.java` | `@PermitAll` (16), status (29), init (41), unseal (58), lock (79) | Config password fallback — Stage 1.5. |
| `resource/CredentialResource.java` | list, create, reveal, delete, update + multi-category `CredentialCategory` | Enforces per-device uniqueness of `PKG_MANAGEMENT` & `PKG_MANAGEMENT_FALLBACK`. |

### REST Resources — Permission Map
| File | Current Annotation | Target Role in Stage 2 |
|:---|:---|:---|
| `resource/DeviceResource.java` | `@RolesAllowed("gnm-admin")` (34) | Large file: 46 getAll, 64 getById, 98 probe-update, 113-176 services CRUD, 196 update, 224 state, 247 trust-ssh, 268 labels, 284 discover, 336 manual, 379-400 history |
| `resource/TopologyResource.java` | `@RolesAllowed("gnm-admin")` (23) | |
| `resource/DiagnosticsResource.java` | `@RolesAllowed("gnm-admin")` (26) | |
| `resource/BackupResource.java` | `@RolesAllowed("gnm-admin")` (20) | Password in query param (30) — Stage 5.1 |
| `resource/ThreatResource.java` | none | GET mutates DB (29-57) — Stage 9.5 |
| `resource/SettingsResource.java` | none | PUT without DTO (31) — Stage 3.2 |
| `resource/UserResource.java` | none | Admin creation without role check (82) — Stage 2 |
| `resource/CredentialResource.java` | none | Stage 2/3 |
| `resource/ScannerResource.java` | none | Stage 2 |
| `resource/DiscoveryResource.java` | none | Stage 2 |
| `resource/UpdateResource.java` | `@RolesAllowed({"gnm-admin", "gnm-operator"})` | SSE with `AuthTicket`, resolves upgrade command from `device.osProfile` |
| `resource/VersionResource.java` | none | Public version metadata |

### WebSockets
| File | Line | Notes |
|:---|:---|:---|
| `resource/TerminalWebSocket.java` | 37 path, 64 onOpen, 150 connectSsh, 157 host key verifier, 331 onMessage | Public path — Stage 1.3. TOFU works properly. |
| `resource/EventWebSocket.java` | 13 path, 34-52 broadcast device, 53-67 threat, 87 activity | Broadcasts to all — Stage 1.4 |

### Discovery & Fingerprinting
| File | Contents |
|:---|:---|
| `discovery/DiscoveryScheduler.java` | `@Scheduled` + starts passive listener (34) |
| `discovery/PassivePacketListener.java` | pcap BPF, sighting queue |
| `discovery/IcmpSweeper.java` | sweep, `ProcessBuilder ping`/`arping` (164, 203) — safe argument lists |
| `discovery/ArpScanner.java` | fallback from OS ARP table |
| `discovery/NetworkSightingQueue.java` | sole entry point into fingerprinting |
| `fingerprint/FingerprintEngine.java` | queue consumer |
| `fingerprint/SimilarityEngine.java` | cosine similarity, threshold 0.75 |
| `fingerprint/probes/Jndi*.java` | JNDI DNS — **to be rewritten**, Stage 5.5 |
| `service/SubnetFilter.java` | CIDR validation, pure logic — good candidate for jqwik tests |

### Config & Infrastructure
| File | Notes |
|:---|:---|
| `src/main/resources/application.properties` | line 35: public path list — Stage 1; lines 8-9, 51: default credentials — Stage 4 |
| `src/main/resources/db/migration/` | V1-V28 (V24..V28: OS profiles, packages/vulnerabilities, available updates, patch jobs, schedules) |
| `build.gradle.kts` | line 77 `ignoreFailures = true` — Stage 7.1 |
| `../build.gradle.kts` (root) | Sonar `sonar.sources=frontend/src` only — Stage 7.3 |
| `../.github/workflows/build-and-push.yml` | Builds `lanalmanac` and `lanalmanac-pkg-worker` images (including dev tags on `agentless-package-management`) |

### Agentless Package, Update, Patch & Vulnerability Management (`com.gnm.pkgmgmt.*` + `gnm-pkg-worker/`)
| File | Contents |
|:---|:---|
| `contracts/pkg-worker-v1.openapi.yaml` | Versioned `v1` OpenAPI contract between `gnm-app` and stateless Go container `gnm-pkg-worker` |
| `gnm-pkg-worker/` | Stateless Go 1.23+ worker (`internal/auth`, `internal/sshclient`, `internal/parsers`, `internal/updates`, `internal/patcher`, `internal/httpapi`) |
| `pkgmgmt/client/PkgWorkerClient.java` | HTTP/NDJSON client with HMAC-SHA256 signing (`PkgWorkerHmacSigner`), TOFU gate, `CredentialResolver`, `DeviceLockManager` |
| `pkgmgmt/profile/*` | `OsProfile`, `OsProfileSpecs`, `ProfileValidator`, `OsProfileMatcher`, `OsDiscoveryService` |
| `pkgmgmt/inventory/*` | `InventoryService`, `CycloneDxExportService`, `InventoryUpdatedEvent` |
| `pkgmgmt/vulnerability/*` | `VulnerabilityProvider`, `OsvApiVulnerabilityProvider`, `VulnerabilityAssessmentService` |
| `pkgmgmt/updates/*` | `UpdateCheckService`, `SudoersSnippetGenerator` |
| `pkgmgmt/patching/*` | `PatchJobService`, `PatchJobLogBroker` (SSE replay + delayed 60s re-check on `CONNECTION_LOST`) |
| `pkgmgmt/schedule/*` | `CronScheduleCalculator`, `PackageManagementScheduler` |
| `resource/OsProfileResource.java`, `DevicePackageResource.java`, `VulnerabilityResource.java`, `PatchJobResource.java` | REST/SSE endpoints using DTOs and explicit `@RolesAllowed` |
| `frontend/src/components/packages/*`, `pages/VulnerabilitiesPage.tsx`, `pages/OsProfilesSettingsTab.tsx` | UI: Packages, Vulnerabilities, Updates, Live Patch Log Modal, Per-Device Schedule, OS Profiles Settings |

## Frontend — `frontend/src/`

| File | Contents | Notes |
|:---|:---|:---|
| `lib/auth/auth-context.tsx` | OIDC init (73), `jwt` cookie (112, 131, 151, 176, 206), `apiClient` (212) | Token in `localStorage` — Stage 1.8/10 |
| `lib/vault/vault-context.tsx` | Vault status, unseal modal | thin, safe |
| `components/Terminal.tsx` | xterm, WS URL (74), resize, onData | missing ticket — Stage 1.3 |
| `components/Layout.tsx` | sidebar, toast from `/ws/events` | Stage 1.4 |
| `App.tsx` | TanStack routing, auth guard (23) | `isAuthenticated` from token |
| `pages/Login.tsx` | login form + SSO | |
| `pages/DeviceDetail.tsx` | large file, device tabs (`packages`, `updates`, `vulnerabilities`, `settings`) | |
| `pages/UsersTab.tsx`, `pages/Settings.tsx` | check `gnm-admin` on UI side | UI is not access control |
| `eslint.config.js` | `no-explicit-any` = warn (27) | Stage 8.3 |

## Tests — Where to Add New Tests

| Area | Test File |
|:---|:---|
| Role authorization matrix | `resource/AuthorizationMatrixTest.java`, `resource/*ResourceTest.java` |
| Vault | `CredentialVaultTest.java`, `service/VaultEngineTest.java` |
| SSH/WS | `RemoteAccessTest.java`, `SecurityAndThreatTest.java` |
| Fingerprinting | `fingerprint/*Test.java` |
| Backup | `BackupCompatibilityTest.java`, `service/BackupServiceTest.java` |
| Package Management | `pkgmgmt/**/*Test.java`, `resource/OsProfile*Test.java`, `resource/DevicePackageResourceTest.java`, `resource/PatchJobResourceTest.java`, `resource/VulnerabilityResourceTest.java` |
| E2E UI | `frontend/tests/*.spec.ts` |

Integration test base class: `AbstractE2ETest.java` (Testcontainers infrastructure).

## Commands — Minimal Set

> Note (host with default JDK 25 outside devcontainer): prefix `./gradlew` with `JAVA_HOME=/home/skoczo/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`.

```bash
./gradlew :gnm-app:compileJava                      # quick compilation check
./gradlew spotlessApply                             # before every commit
./gradlew :gnm-app:test --tests "*VaultEngineTest"  # targeted test execution
cd frontend && npx tsc -b                           # TypeScript check
cd frontend && npx eslint src/pages/DeviceDetail.tsx
git diff --stat                                     # instead of re-reading files
```
