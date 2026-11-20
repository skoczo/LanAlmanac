# Package Management & Vulnerability Improvements Plan

This plan tracks the fixes and UX enhancements requested for Agentless Package Management and Vulnerability Assessment.

## Step 1 — Fix 404 Routing Bug on Patch Jobs & Guard SSH Timeouts
- [x] **1.1** Fix JAX-RS path collision between `DevicePackageResource` (`@Path("/api/devices/{id}")`) and `PatchJobResource` (`@Path("/api")` with `@Path("/devices/{id}/patch-jobs")`) by adding `/patch-jobs` endpoints directly inside `DevicePackageResource` delegating to `PatchJobService`.
- [x] **1.2** Add HTTP integration tests in `DevicePackageResourceTest.java` verifying `GET /api/devices/{id}/patch-jobs` and `POST /api/devices/{id}/patch-jobs` return HTTP 200 (not 404 HTML).
- [x] **1.3** Enhance error reporting in `apiClient` / frontend to display sanitized error messages instead of raw HTML (`<html><body>...`), and add connection timeout grace for low-power devices.
- [x] **1.4** Verify `./gradlew :gnm-app:compileJava` and `./gradlew :gnm-app:test --tests "*DevicePackageResourceTest*"`.

## Step 2 — Consolidate Packages & Updates into a Single View
- [x] **2.1** Update `DevicePackageDto` or packages endpoint to include available update info (`hasUpdate`, `candidateVersion`, `severity`).
- [x] **2.2** Update `DevicePackagesTab.tsx` with:
  - Header actions: "Check Updates" and "Upgrade System" with patch job launcher.
  - Filter pills: "All Packages", "Updates Available", "Vulnerable".
  - Table badges: Installed version -> Candidate version (`1.2.3 -> 1.2.4`) with update badge.
- [x] **2.3** Remove the standalone redundant `Updates` tab from `DeviceDetail.tsx`.
- [x] **2.4** Verify frontend with `./gradlew detectFrontendIssues`.

## Step 3 — Local Offline CVE Database Sync
- [x] **3.1** Design `LocalVulnerabilityDatabaseService`: Downloads ecosystem zip archives (e.g. `Debian.zip`, `Ubuntu.zip`, `Alpine.zip`) from OSV cloud storage (`https://osv-vulnerabilities.storage.googleapis.com/<Ecosystem>/all.zip`) on demand or scheduled.
- [x] **3.2** Batch upsert advisories into local `vulnerability_advisory` and `vulnerability_affected_package` PostgreSQL tables with stream parsing (avoiding high RAM consumption).
- [x] **3.3** Update `VulnerabilityAssessmentService` to perform offline matching against the local DB first without outbound API queries when local advisories exist.
- [x] **3.4** Add unit/integration tests verifying local dump ingestion and offline CVE matching (`LocalVulnerabilityDatabaseServiceTest`, `VulnerabilityResourceTest`).
- [x] **3.5** Verify `./gradlew :gnm-app:compileJava` and `./gradlew spotlessApply`.

## Step 4 — Resolve Build Collision & Fast-Jar Validation
- [x] **4.1** Remove duplicate `startPatchJob` and `listDevicePatchJobs` endpoints from `PatchJobResource.java` to eliminate Quarkus RESTEasy deployment collision with `DevicePackageResource`.
- [x] **4.2** Update `PatchJobResourceTest.java` reflection assertions to match remaining methods.
- [x] **4.3** Run `./gradlew spotlessApply` and verify fast-jar build `./gradlew :gnm-app:build -Dquarkus.package.type=fast-jar -x test --no-daemon`.
- [x] **4.4** Verify unit test suite (`PatchJobResourceTest`, `DevicePackageResourceTest`, `VulnerabilityResourceTest`, `LocalVulnerabilityDatabaseServiceTest`).

## Step 5 — Fix Long-Running Remote SSH Transaction Timeouts
- [x] **5.1** Identify root cause of `Error id 3d3a0b67-b638-4d1b-a224-0e943442a108-1`: `UpdateCheckService.checkDeviceUpdates` had `@Transactional` holding a DB transaction while `apt-get` was executed remotely over SSH via `gnm-pkg-worker`. On low-power ARM nodes (e.g. Raspberry Pi 1 `watchdog`), this remote SSH query takes ~75s, exceeding Narayana's 60s JTA timeout. At 60s, Narayana's `TransactionReaper` aborted the transaction, causing `Failing fast as the transaction has rolled back` and `ARJUNA016102: The transaction is not active!`.
- [x] **5.2** Decouple remote HTTP/SSH worker calls from database transactions in `UpdateCheckService` and `InventoryService` using discrete `QuarkusTransaction.requiringNew()` blocks for entity reads and reconcile/writes.
- [x] **5.3** Increase SSH connect timeout in `PkgWorkerClient.buildDecryptedTarget` from 10s to 30s to prevent handshake drops on constrained nodes.
- [x] **5.4** Add exception cause unwrapping in `DevicePackageResource` to return proper HTTP error codes instead of generic 500 Arc proxy exceptions.
- [x] **5.5** Improve error parsing in `auth-context.tsx` to display clean server messages instead of raw serialized JSON.
- [x] **5.6** Verified with `./gradlew spotlessApply`, compileJava, targeted unit tests, frontend issue scans, and fast-jar build.
