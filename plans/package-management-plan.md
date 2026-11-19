# Package, Update, Patch & Vulnerability Management Implementation Plan (TDD · Core + Go Worker)

**Architecture Document & Approved ADR-0..9:** [`docs/package-management-architecture.md`](../docs/package-management-architecture.md)  
**Working Rules:** [`.agents/invariants.md`](../.agents/invariants.md) · [`plans/_brief-template.md`](_brief-template.md)

Executed in small, self-contained steps (one agent session = one step). **Every step is strictly executed using TDD (Red → Green → Refactor).**

Size Legend (file count and risk):
- **S** — 1–2 files, local change, low risk
- **M** — 3–5 files, requires unit/integration tests
- **L** — more than 5 files or API contract + frontend change

---

## TDD Rules & Test Pyramid (Mandatory in Every Step)

1. **Red (Tests First):** Before writing production code for a step, write the unit, integration, or security tests listed in **"Tests (TDD — write first)"** and run them to confirm they fail for the expected reason.
2. **Green (Minimal Implementation):** Write only the production code required to pass the tests and satisfy the step criteria.
3. **Refactor + Verify:** Clean up the code, run the formatter, and run the step's verification suite:
   * **`gnm-pkg-worker` (Go):** `cd gnm-pkg-worker && go vet ./... && go test -race -cover ./...` (minimum **80% coverage** across domain packages; use an in-memory SSH test server via `golang.org/x/crypto/ssh` rather than mocking the SSH transport logic).
   * **`gnm-app` (Quarkus / Java 21):** `./gradlew spotlessApply && ./gradlew :gnm-app:compileJava && ./gradlew :gnm-app:test --tests "*<TestName>"` (Note Invariant #21: `ignoreFailures = true` in `build.gradle.kts` — always inspect the actual JUnit XML test results, not just the Gradle exit code!).
   * **RBAC Security:** Every new resource in `com.gnm.resource` must have an authorization matrix test (missing token → `401`, `gnm-viewer`, `gnm-operator`, `gnm-admin`).
   * **`frontend`:** `cd frontend && npx tsc -b && npx eslint <changed files>` + Playwright E2E tests (`frontend/tests/package-management.spec.ts`) for key user journeys.
   * **Contract:** `PkgWorkerContractTest` (black-box contract verification of `contracts/pkg-worker-v1.openapi.yaml`).

### Dependencies (Invariant #15)
* **`gnm-app` (Quarkus):** **ZERO new dependencies** in `gnm-app/build.gradle.kts` (uses JDK 21 `java.net.http.HttpClient`, existing `quarkus-redis-client`, `quarkus-scheduler`, `quarkus-flyway`, `Testcontainers`, `RestAssured`, `JUnit 5`, `Mockito`).
* **`frontend`:** **ZERO new dependencies** in `frontend/package.json`.
* **`gnm-pkg-worker` (Go 1.23+, ADR-0):** new module `gnm-pkg-worker/go.mod` with a single external production dependency: `golang.org/x/crypto` (for `ssh`). All other functionality uses Go stdlib (`net/http`, `encoding/json`, `regexp`, `crypto/hmac`, `crypto/sha256`).
* **Stage 6 (optional, ADR-1):** pre-built `aquasec/trivy` container as an optional sidecar (no scanner binaries embedded in the `gnm-app` image).

---

## Stage 0 — Contract `v1` & Stateless Go Worker Skeleton

Goal: freeze the OpenAPI `v1` contract and stand up a tested `gnm-pkg-worker` Go container with HMAC-SHA256 authentication.

### 0.1 [x] Contract Specification `contracts/pkg-worker-v1.openapi.yaml` — **S**
**Goal:** Single source of truth for all 7 `v1` endpoints, NDJSON streaming schemas, HMAC headers, `elevationMode` enum (`NONE`, `SUDO_NOPASSWD`, `SUDO_PASSWORD` — ADR-3), and the `errorCode` catalog.

**Files:**
- new `contracts/pkg-worker-v1.openapi.yaml`

**Verification:** YAML/OpenAPI 3.1 syntax validation + complete schemas for all endpoints and error payloads.

---

### 0.2 [x] `gnm-pkg-worker` Skeleton (Go 1.23+), HMAC Middleware & `/v1/health` — **M**
**Goal:** Stateless Go container on an internal Docker network (zero host port exposure, zero DB access) with strict HMAC-SHA256 signature middleware (±30s replay window).

**Files:**
- new `gnm-pkg-worker/go.mod`, `gnm-pkg-worker/Dockerfile` (multi-stage → `distroless/static-debian12:nonroot`)
- new `gnm-pkg-worker/cmd/worker/main.go`
- new `gnm-pkg-worker/internal/auth/hmac.go` + `hmac_test.go`
- new `gnm-pkg-worker/internal/httpapi/server.go` + `health_test.go`
- update `docker-compose.yml` (`pkg_worker` service on internal network, no `ports:`, env `GNM_PKG_WORKER_AUTH_SECRET`)

**Tests (TDD — write first):**
- `hmac_test.go`: valid signature → `200`; missing signature → `401`; invalid signature → `401`; timestamp older than 30s or >30s in the future → `401`; tampered body with old signature → `401`; server refuses to start when secret is empty or <32 bytes.
- `health_test.go`: `GET /v1/health` returns `contractVersion: "v1"`, `implementation: "go"`, supported parsers, and `supportedElevationModes: ["NONE", "SUDO_NOPASSWD"]`.

**Verification:** `cd gnm-pkg-worker && go vet ./... && go test -race -cover ./...`

---

## Stage 1 — Foundation: Go Worker SSH Transport, `gnm-app` Client, OS Profiles & Auto-Detection

Goal: Complete, tested path `gnm-app` → `gnm-pkg-worker` → SSH host (TOFU + `PASSWORD` and `SSH_KEY`), OS profile database (`V24`), and zero-hardcode OS auto-detection.

### 1.1 [x] SSH Transport in `gnm-pkg-worker`: `/v1/ssh/fingerprint` & `/v1/probe` — **M**
**Goal:** Unauthenticated host key fingerprint retrieval, `expectedHostKeySha256` verification prior to authentication, `PASSWORD` and `SSH_KEY` (Ed25519/RSA/ECDSA) authentication with in-memory secret zeroization, and generic POSIX OS probing.

**Files:**
- new `gnm-pkg-worker/internal/sshclient/client.go`, `tofu.go`, `probe.go`
- new `gnm-pkg-worker/internal/sshclient/testserver_test.go` (in-memory SSH server for TDD)
- new `gnm-pkg-worker/internal/sshclient/client_test.go`, `probe_test.go`
- new `gnm-pkg-worker/internal/httpapi/probe_handler.go` + `probe_handler_test.go`

**Tests (TDD — write first):**
- `/v1/ssh/fingerprint` returns `SHA256:...` without sending any password or private key.
- Connection with mismatched `expectedHostKeySha256` aborts handshake with `HOST_KEY_MISMATCH` + `actualFingerprint` and **never sends** credentials (assert in mock SSH server that `PasswordCallback`/`PublicKeyCallback` were never invoked).
- Successful authentication for both `PASSWORD` and `SSH_KEY` (Ed25519 and RSA); empty `expectedHostKeySha256` returns `400`.
- `probe` parses `/etc/os-release` (Debian 12, Ubuntu 24.04, Rocky 9, Alpine 3.20, quoted and unquoted values, fallback when `/etc/os-release` is missing) and optional `verifyCommands[]`.

**Verification:** `cd gnm-pkg-worker && go test -race -cover ./...`

---

### 1.2 [x] `PkgWorkerClient`, `PkgWorkerHmacSigner`, `DeviceLockManager` & `CredentialResolver` in `gnm-app` — **M**
**Goal:** HTTP/NDJSON client in `gnm-app` with HMAC-SHA256 signing, hard TOFU gate before decrypting from `VaultEngine`, `byte[]`/`char[]` zeroization, per-device locking, and deterministic category-based credential resolution (ADR-9).

**Files:**
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/client/PkgWorkerHmacSigner.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/client/PkgWorkerClient.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/client/DeviceLockManager.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/client/CredentialResolver.java`
- new `v1` contract DTOs in `gnm-app/src/main/java/com/gnm/pkgmgmt/client/dto/`
- update `gnm-app/src/main/resources/application.properties` (`gnm.pkg-worker.base-url`, `gnm.pkg-worker.auth-secret`)
- new tests: `PkgWorkerHmacSignerTest.java`, `PkgWorkerClientTest.java`, `DeviceLockManagerTest.java`, `CredentialResolverTest.java`

**Tests (TDD — write first):**
- `PkgWorkerHmacSignerTest`: HMAC-SHA256 signature matches the deterministic test vector shared with Go `hmac_test.go`.
- `CredentialResolverTest`:
  - selects `PKG_MANAGEMENT` ahead of `PKG_MANAGEMENT_FALLBACK` and `NetworkService(SSH).credential`;
  - ignores credentials from unrelated categories (`WEB_ADMIN`, `API`) and types outside `{SSH_KEY, PASSWORD}`;
  - falls back to `PKG_MANAGEMENT_FALLBACK` when `PKG_MANAGEMENT` is absent, and to `NetworkService(SSH).credential` when both are absent;
  - on `AUTH_FAILED` with `PKG_MANAGEMENT`, retries **at most once** with `PKG_MANAGEMENT_FALLBACK` and marks `usedFallback = true`;
  - **never** retries with fallback on `HOST_KEY_MISMATCH`;
  - returns `MISSING_CREDENTIAL` when no candidate exists.
- `PkgWorkerClientTest` (local `com.sun.net.httpserver.HttpServer`):
  - when `sshHostKeyTrusted == false` → throws exception and **never calls** `VaultEngine.decrypt(...)` (Invariant #8);
  - when `VaultEngine.isUnsealed() == false` → fails cleanly before making any HTTP call;
  - when worker returns `HOST_KEY_MISMATCH` → records/updates `ThreatEvent(HIGH)`;
  - verifies `X-GNM-Timestamp` and `X-GNM-Signature` headers.
- `DeviceLockManagerTest`: rejects concurrent acquisition for the same `deviceId`, allows parallel locks across distinct `deviceId`s, and releases locks in `finally` on exception.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*PkgWorker*,*DeviceLockManagerTest,*CredentialResolverTest"`

---

### 1.3 [x] Migration `V24__os_profiles.sql`, `OsProfile` Entity, `credential_category`, `ProfileValidator` & `OsProfileMatcher` — **M**
**Goal:** OS profile table seeded with 3 system profiles (`debian-apt`, `rhel-dnf`, `alpine-apk`), multi-category `Credential` (`credential_category`), profile security validation, and zero-`if/else` OS matching.

**Files:**
- new `gnm-app/src/main/resources/db/migration/V24__os_profiles.sql` (including `credential_category` table with partial unique index per `(physical_device_id, category)` for `PKG_MANAGEMENT` and `PKG_MANAGEMENT_FALLBACK`)
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/OsProfile.java`
- new `gnm-app/src/main/java/com/gnm/model/enums/CredentialCategory.java`
- update `gnm-app/src/main/java/com/gnm/model/Credential.java` (`categories`)
- update `gnm-app/src/main/java/com/gnm/model/PhysicalDevice.java` (`osProfile`, `osProfilePinned`, `osFacts`, `osDetectedAt`)
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/ProfileValidator.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/OsProfileMatcher.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/OsDiscoveryService.java`
- new tests: `ProfileValidatorTest.java`, `OsProfileMatcherTest.java`, `OsDiscoveryServiceTest.java`

**Tests (TDD — write first):**
- `ProfileValidatorTest`: rejects disallowed placeholders (e.g., `{{packages}}` in `inventorySpec`), invalid or overly long regexes, out-of-range timeouts, unsupported `elevationMode`, or blank `slug`.
- `OsProfileMatcherTest`: matches Debian 12, Ubuntu 24.04, Rocky 9, AlmaLinux 9, and Alpine 3.20 fixtures by `priority ASC`, and returns `Optional.empty()` for unknown OS facts without hardcoded distro branches.
- `OsDiscoveryServiceTest`: respects `osProfilePinned = true` (does not overwrite pinned profile) and persists `osFacts` even when no profile matches.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*ProfileValidatorTest,*OsProfileMatcherTest,*OsDiscoveryServiceTest"`

---

### 1.4 [x] REST API `OsProfileResource` & Multi-Category `CredentialResource` + Credentials UI — **L**
**Goal:** Hardened OS profiles REST API (DTOs only, `@RolesAllowed`, audit logging, ADR-7) and multi-category support in `CredentialResource` and the Device Detail credential UI (Invariant #14, ADR-9).

**Files:**
- new DTOs in `gnm-app/src/main/java/com/gnm/pkgmgmt/dto/` (`OsProfileDto`, `OsProfileExportDto` with `schemaVersion: 1`)
- new `gnm-app/src/main/java/com/gnm/resource/OsProfileResource.java`
- update `gnm-app/src/main/java/com/gnm/resource/CredentialResource.java` (read/write `categories`, enforce per-device uniqueness of `PKG_MANAGEMENT` / `PKG_MANAGEMENT_FALLBACK` with optional atomic reassignment)
- update credential form & badges in `frontend/src/pages/DeviceDetail.tsx`
- new tests: `OsProfileResourceTest.java`

**Tests (TDD — write first):**
- `OsProfileResourceTest`: RBAC matrix (`gnm-viewer`/`gnm-operator` read-only, `gnm-admin` full CRUD); `PUT`/`DELETE` on `is_system=true` → `409`; `/clone`; `/export` → `/import` round-trip (`schemaVersion: 1`); multi-category parsing and exclusive category enforcement.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*OsProfileResourceTest"` + `cd frontend && npx tsc -b`

---

## Stage 2 — Step 1 (Inventory) + Step 4 (Offline Vulnerability Assessment)

Goal: Read-only remote package inventory collection and server-side CVE assessment with fleet-wide deduplication.

### 2.1 [x] 4 Generic Parsers & `/v1/inventory` Endpoint in `gnm-pkg-worker` — **M**
**Goal:** Streaming output parsing (`DELIMITED`, `KV_BLOCKS`, `JSON_MAP`, `REGEX`), safe `purlTemplate` rendering, and `sourceName`/`sourceVersion` extraction.

**Files:**
- new `gnm-pkg-worker/internal/parsers/` (`parser.go`, `delimited.go`, `kvblocks.go`, `jsonmap.go`, `regex.go`, `purl.go` + `parsers_test.go`)
- new `gnm-pkg-worker/internal/httpapi/inventory_handler.go` + `inventory_handler_test.go`

**Tests (TDD — write first):**
- Table-driven tests on realistic command outputs:
  - `dpkg-query` (filtering out `deinstall ok config-files`, multi-arch `amd64`/`i386`, epoch versions `1:2.3.4-1deb12u1`, populating `sourceName`/`sourceVersion` with fallback to `name`/`version`);
  - `rpm -qa` (stripping leading `0:` epoch);
  - `/lib/apk/db/installed` (multi-line blocks, `o:` origin as `sourceName`);
  - `JSON_MAP` and `REGEX` (skipping headers and malformed lines without aborting the scan).
- Output size limit test (>16 MB rejected cleanly without OOM) and PURL URL-encoding (`+`, `:`, `~`).

**Verification:** `cd gnm-pkg-worker && go test -race -cover ./...`

---

### 2.2 [x] Migration `V25`, `InventoryService` & CycloneDX Export in `gnm-app` — **L**
**Goal:** Persist normalized packages in PostgreSQL and generate CycloneDX 1.5 JSON SBOMs on the fly.

**Files:**
- new `gnm-app/src/main/resources/db/migration/V25__device_packages_and_vulnerabilities.sql`
- new entities `DevicePackageScan.java`, `DevicePackage.java`, `VulnerabilityAdvisory.java`, `DeviceVulnerability.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/inventory/InventoryService.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/inventory/CycloneDxExportService.java`
- new tests: `InventoryServiceTest.java`, `CycloneDxExportServiceTest.java`

**Tests (TDD — write first):**
- `InventoryServiceTest`:
  - initial scan persists `device_package_scan` + packages with `purl` and `source_name`, and fires `InventoryUpdatedEvent`;
  - subsequent scan updates changed versions and **removes** uninstalled packages;
  - worker failure marks scan `FAILED` without wiping previous valid `device_package` rows;
  - missing OS profile automatically triggers `OsDiscoveryService`.
- `CycloneDxExportServiceTest`: generated JSON contains `bomFormat: "CycloneDX"`, `specVersion: "1.5"`, device metadata, and valid `components[].purl`.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*InventoryServiceTest,*CycloneDxExportServiceTest"`

---

### 2.3 [x] `OsvApiVulnerabilityProvider` & `VulnerabilityAssessmentService` in `gnm-app` — **L**
**Goal:** Offline CVE analysis in `gnm-app` with fleet-wide deduplication, distro source-package support, and advisory caching (ADR-1).

**Files:**
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/vulnerability/VulnerabilityProvider.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/vulnerability/OsvApiVulnerabilityProvider.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/vulnerability/VulnerabilityAssessmentService.java`
- update `gnm-app/src/main/resources/application.properties` (`gnm.vuln.osv.base-url=https://api.osv.dev`, cache TTL)
- new tests: `OsvApiVulnerabilityProviderTest.java`, `VulnerabilityAssessmentServiceTest.java`

**Tests (TDD — write first):**
- `OsvApiVulnerabilityProviderTest` (local mock HTTP OSV server): batching `/v1/querybatch` requests, fetching `/v1/vulns/{id}` details, and handling HTTP errors gracefully.
- `VulnerabilityAssessmentServiceTest`:
  - multiple devices sharing `(Debian:12, openssl, 3.0.11-1~deb12u1)` produce **exactly 1** query in the OSV batch;
  - queries `source_name` (`openssl`) rather than binary package (`libssl3`) when `packageMatchStrategy = SOURCE_PACKAGE`;
  - preserves `ACCEPTED_RISK` and `FALSE_POSITIVE` across subsequent scans;
  - transitions remediated packages to `FIXED` with `resolved_at != null`.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*Vulnerability*Test"`

---

### 2.4 [x] REST API & Frontend for Inventory + Vulnerabilities — **L**
**Goal:** Complete REST endpoints + `Packages` and `Vulnerabilities` tabs in `DeviceDetail` and the global `/vulnerabilities` page (Invariant #14).

**Files:**
- new DTOs in `gnm-app/src/main/java/com/gnm/pkgmgmt/dto/PackageManagementDtos.java`
- new `gnm-app/src/main/java/com/gnm/resource/DevicePackageResource.java`
- new `gnm-app/src/main/java/com/gnm/resource/VulnerabilityResource.java`
- new tests: `DevicePackageResourceTest.java`, `VulnerabilityResourceTest.java`
- new frontend files: `frontend/src/lib/api/packages.ts`, `frontend/src/components/packages/DevicePackagesTab.tsx`, `frontend/src/components/packages/DeviceVulnerabilitiesTab.tsx`, `frontend/src/pages/VulnerabilitiesPage.tsx`
- update `frontend/src/pages/DeviceDetail.tsx`, `frontend/src/App.tsx`, `frontend/src/components/Layout.tsx`

**Tests (TDD — write first):**
- RBAC matrix tests for `DevicePackageResource` and `VulnerabilityResource` (`gnm-viewer` read-only, `gnm-operator`/`gnm-admin` allowed to scan and update status).

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*DevicePackageResourceTest,*VulnerabilityResourceTest"` + `cd frontend && npx tsc -b`

---

## Stage 3 — Step 2 (Update Check, `sudo -n` + `sudoers.d` Generator, Package Manager Locks)

### 3.1 [x] `/v1/updates/check` Endpoint in `gnm-pkg-worker` — **M**
**Goal:** Two-phase update detection (`refresh` + `check`), package manager lock retry with backoff, non-zero success exit codes (`100` for `dnf`), and `elevationMode` verification (ADR-3).

**Files:**
- new `gnm-pkg-worker/internal/updates/checker.go` + `checker_test.go`
- new `gnm-pkg-worker/internal/httpapi/updates_handler.go` + `updates_handler_test.go`

**Tests (TDD — write first):**
- `dnf check-update` exit code `100` → parsed as available updates; exit code `0` → 0 updates; exit code `1` → error.
- Lock error (`Could not get lock`) on attempts 1 and 2, success on attempt 3 → backoff retry succeeds; exceeding `maxRetries` → `PACKAGE_MANAGER_LOCKED`.
- `sudo -n` returning `a password is required` → `SUDO_PERMISSION_REQUIRED`.
- `elevationMode: "SUDO_PASSWORD"` in MVP → `UNSUPPORTED_ELEVATION_MODE`.

**Verification:** `cd gnm-pkg-worker && go test -race -cover ./...`

---

### 3.2 [x] Migration `V26`, `UpdateCheckService`, `SudoersSnippetGenerator` & UI — **L**
**Goal:** Persist available updates, correlate `fix_available_now` with open CVEs, and provide the Updates tab + `/etc/sudoers.d/lanalmanac` snippet modal.

**Files:**
- new `gnm-app/src/main/resources/db/migration/V26__device_available_updates.sql`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/updates/DeviceAvailableUpdate.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/updates/UpdateCheckService.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/updates/SudoersSnippetGenerator.java`
- update `gnm-app/src/main/java/com/gnm/resource/DevicePackageResource.java` (`GET /updates`, `POST /updates/check`, `GET /sudoers`)
- new `frontend/src/components/packages/DeviceUpdatesTab.tsx`, update `frontend/src/pages/DeviceDetail.tsx`
- new tests: `UpdateCheckServiceTest.java`, `SudoersSnippetGeneratorTest.java`

**Tests (TDD — write first):**
- `UpdateCheckServiceTest`: reconciles `device_available_update` rows and sets `device_vulnerability.fix_available_now = true` for matching packages.
- `SudoersSnippetGeneratorTest`: generates valid `Cmnd_Alias` / `NOPASSWD:` syntax with full command paths and rejects unsafe usernames.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*UpdateCheckServiceTest,*SudoersSnippetGeneratorTest,*DevicePackageResourceTest"` + `cd frontend && npx tsc -b`

---

## Stage 4 — Step 3 (Patch Job Engine, Live NDJSON → SSE Streaming, Retiring Hardcoded Update Logic)

### 4.1 [x] `/v1/patch/execute` (NDJSON Stream) & `/cancel` in `gnm-pkg-worker` — **L**
**Goal:** Streaming multi-phase patch execution with concurrent `stdout`/`stderr` reading, Command Injection prevention, `CONNECTION_LOST` detection (ADR-4), and active job cancellation.

**Files:**
- new `gnm-pkg-worker/internal/patcher/template.go` + `template_test.go`
- new `gnm-pkg-worker/internal/patcher/executor.go` + `executor_test.go`
- new `gnm-pkg-worker/internal/httpapi/patch_handler.go` + `patch_handler_test.go`

**Tests (TDD — write first):**
- `template_test.go` (negative/security tests): rejects `; id`, `$(whoami)`, `` `id` ``, `pkg1 && rm -rf /`, `--allow-unauthenticated`, whitespace, and newlines with `INVALID_PACKAGE_NAME`; safely quotes valid package names (`libstdc++6`, `g++`, `linux-image-6.1.0-21-amd64`).
- `executor_test.go` (in-memory SSH test server):
  - concurrent `stdout` and `stderr` streaming without deadlocks or data races, preserving monotonic `seq`;
  - NDJSON frame order: `PHASE(REFRESH)` → `LOG` → `PHASE(UPGRADE)` → `PHASE(REBOOT_CHECK)` → `RESULT(rebootRequired: true)`;
  - abrupt SSH disconnect without `exit-status` → `RESULT(status: "CONNECTION_LOST")`;
  - `POST /v1/executions/{id}/cancel` terminates active execution with `CANCELLED`.

**Verification:** `cd gnm-pkg-worker && go vet ./... && go test -race -cover ./...`

---

### 4.2 [x] Migration `V27`, `PatchJobService`, `PatchJobLogBroker` & `PatchJobResource` in `gnm-app` — **L**
**Goal:** Persistent patch jobs and console logs in PostgreSQL, NDJSON stream consumption from the worker, and authenticated SSE streaming with single-use `AuthTicket` and lossless `Last-Event-ID`/`fromSeq` replay.

**Files:**
- new `gnm-app/src/main/resources/db/migration/V27__patch_jobs.sql`
- new `PatchJob.java`, `PatchJobLog.java`, `PatchJobLogBroker.java`, `PatchJobService.java`
- update `gnm-app/src/main/java/com/gnm/dto/AuthTicket.java` (`PURPOSE_SSE_PATCH_JOB = "sse-patch-job"`)
- new `gnm-app/src/main/java/com/gnm/resource/PatchJobResource.java`
- new tests: `PatchJobServiceTest.java`, `PatchJobResourceTest.java`

**Tests (TDD — write first):**
- `PatchJobServiceTest`:
  - validates `requestedPackages` against command injection and checks membership in `device_available_update`;
  - full NDJSON stream flow → persists `patch_job_log` chunks (50,000-line / 10 MB cap with `[Log truncated]`), records `credential_id` / `credential_username` / `used_fallback` audit fields, updates `physical_device.reboot_required`, and triggers post-patch Inventory refresh on `SUCCEEDED`;
  - `CONNECTION_LOST` schedules delayed 60s Inventory re-check (ADR-4).
- `PatchJobResourceTest`:
  - RBAC matrix (`gnm-viewer` → `403` on `POST /patch-jobs` and `/cancel`, `200` on `GET`);
  - `GET /api/patch-jobs/{jobId}/stream` without a valid ticket or with a ticket for another `jobId` → `401`.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*PatchJob*Test"`

---

### 4.3 [x] Live Log Viewer in UI & Removing Hardcoded `apt-get` from `UpdateResource.java` — **L**
**Goal:** Launch patch jobs from GUI (`FULL_UPGRADE`, `SECURITY_ONLY`, `SELECTED_PACKAGES`), view live/historical SSE console output, and remove hardcoded `apt-get` commands from `UpdateResource.java`.

**Files:**
- new `frontend/src/components/packages/PatchJobLogModal.tsx`
- update `frontend/src/components/packages/DeviceUpdatesTab.tsx`, `frontend/src/pages/DeviceDetail.tsx`
- update `gnm-app/src/main/java/com/gnm/resource/UpdateResource.java` (resolve command from `device.osProfile` instead of hardcoded `apt-get`)
- update `.agents/context-map.md`

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*PatchJob*" --tests "*UpdateResourceTest"` + `npm --prefix frontend run build`

---

## Stage 5 — Per-Device Schedules (GUI), OS Profile Editor (Dry-Run) & Contract / E2E Tests

### 5.1 [x] Migration `V28`, `DevicePackageSchedule`, `PackageManagementScheduler` & GUI (ADR-5) — **L**
**Goal:** Per-device schedules configurable directly from the GUI (Inventory, Update Check, opt-in Auto-Patch `SECURITY_ONLY`/`FULL_UPGRADE`) with safe execution and audit trail.

**Files:**
- new `gnm-app/src/main/resources/db/migration/V28__device_package_schedule.sql`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/schedule/DevicePackageSchedule.java`
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/schedule/CronScheduleCalculator.java` (calculates `nextRunAt` with full `timezone` support)
- new `gnm-app/src/main/java/com/gnm/pkgmgmt/schedule/PackageManagementScheduler.java` (`@Scheduled(every = "60s")` + `Semaphore` limiting concurrent SSH hosts)
- update `gnm-app/src/main/java/com/gnm/resource/DevicePackageResource.java` (`GET`/`PUT /api/devices/{id}/package-schedule`)
- new `frontend/src/components/packages/DeviceScheduleCard.tsx`, update `frontend/src/components/packages/DeviceUpdatesTab.tsx`
- new tests: `CronScheduleCalculatorTest.java`, `PackageManagementSchedulerTest.java`, update `DevicePackageResourceTest.java`

**Tests (TDD — write first):**
- `CronScheduleCalculatorTest`: computes next 3 execution timestamps across presets, step expressions, and timezones (`Europe/Warsaw`, `UTC`), and rejects invalid cron expressions or unknown timezones (`400`).
- `PackageManagementSchedulerTest`:
  - triggers only schedules with `next_*_run_at <= now()`;
  - skips `OFFLINE` devices, untrusted TOFU host keys, and sealed `VaultEngine`;
  - records `triggered_by = "schedule:<updatedBy>"` on `patch_job`;
  - skips locked devices without queuing duplicates and advances `next_*_run_at`.
- RBAC: `gnm-viewer` is restricted to `GET`, while `gnm-operator`/`gnm-admin` can `PUT /package-schedule`.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*CronScheduleCalculatorTest,*PackageManagementSchedulerTest,*DevicePackageResourceTest"` + `npm --prefix frontend run build`

---

### 5.2 [x] Dry-Run (`POST /api/os-profiles/test`) & `OsProfilesSettingsTab.tsx` — **L**
**Goal:** Create/edit custom OS profiles, import/export JSON (`schemaVersion: 1`), and run read-only dry-run tests from the GUI (`gnm-admin`).

**Files:**
- update `gnm-app/src/main/java/com/gnm/resource/OsProfileResource.java` (`POST /api/os-profiles/test`, `GET /api/os-profiles/worker-health`)
- new `frontend/src/pages/OsProfilesSettingsTab.tsx`, update `frontend/src/pages/Settings.tsx`
- new test `gnm-app/src/test/java/com/gnm/resource/OsProfileDryRunTest.java`

**Tests (TDD — write first):**
- `OsProfileDryRunTest`: enforces `@RolesAllowed("gnm-admin")`, does not mutate `device_package`, and requests a 50-package preview (`dryRunPreviewLimit = 50`) with skipped-line diagnostics.

**Verification:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*OsProfileDryRunTest"` + `npm --prefix frontend run build`

---

### 5.3 [x] Contract Test Suite (`PkgWorkerContractTest`) — **M**
**Goal:** Automated black-box contract test verifying that `gnm-pkg-worker` and `PkgWorkerClient` adhere to `contracts/pkg-worker-v1.openapi.yaml`.

**Files:**
- new `gnm-app/src/test/java/com/gnm/pkgmgmt/contract/PkgWorkerContractTest.java` (`@Tag("contract")`)

**Test Cases:**
- HMAC-SHA256 signature verification (`401` on invalid signature).
- `/v1/health`, `/v1/ssh/fingerprint`, `/v1/inventory` (with `purl` and `sourceName`), `/v1/updates/check` (exit code `100`), and `/v1/patch/execute` (`PHASE`/`LOG`/`RESULT` NDJSON stream).

---

### 5.4 [x] Playwright E2E Tests (`frontend/tests/package-management.spec.ts`) — **M**
**Goal:** Automated end-to-end UI tests for key package management views.

**Files:**
- new `frontend/tests/package-management.spec.ts`

**Scenarios:**
- Fleet Vulnerability Center (`/vulnerabilities`) rendering CVE advisories and package correlation.
- `Settings → OS Profiles` tab rendering `gnm-pkg-worker` health badge and built-in profiles.

---

## Stage 6 — Future Extensions (Post-MVP)

### 6.1 [ ] *(Optional — ADR-1)* `aquasec/trivy server` Sidecar for Air-Gapped Networks — **M**
- `TrivyServerVulnerabilityProvider` implementing `VulnerabilityProvider` (configurable via `gnm.vuln.provider=osv-api|trivy-server`) + `offline-cve` profile in `docker-compose.yml` + TDD test against a mocked Trivy API.

### 6.2 [ ] *(Future — ADR-3)* `elevationMode: "SUDO_PASSWORD"` via `stdin` — **M**
- Extend `gnm-pkg-worker` to pipe `sudoSecret` to the SSH channel's `stdin` and immediately zeroize the buffer (`clear(buf)`), plus per-device opt-in configuration in `gnm-app` (zero breaking changes to contract `v1`) + TDD tests proving the password never appears in command arguments or logs.

---

## Final Verification Checklist Before Releasing a Full Stage
1. `docker build --target test --progress=plain ./gnm-pkg-worker` (`go vet ./... && go test -race -cover ./...`)
2. `./gradlew spotlessApply`
3. `./gradlew detectAllIssues`
4. Contract Test Suite (`PkgWorkerContractTest`) + Playwright (`package-management.spec.ts`)
5. Before committing a full release stage: `./run_tests.sh`
