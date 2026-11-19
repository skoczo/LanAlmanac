# LanAlmanac

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=skoczo_LanAlmanac&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=skoczo_LanAlmanac)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=skoczo_LanAlmanac&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=skoczo_LanAlmanac)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=skoczo_LanAlmanac&metric=bugs)](https://sonarcloud.io/summary/new_code?id=skoczo_LanAlmanac)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=skoczo_LanAlmanac&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=skoczo_LanAlmanac)

LanAlmanac (formerly GreatNetworkManager) is an open-source, self-hosted LAN management and network observability platform. It is engineered as a **core modular monolith** (`gnm-app`, Quarkus JAX-RS/WebSocket + React 19 SPA) paired with an approved, stateless remote execution worker (`gnm-pkg-worker`, Go 1.23+) for agentless package, vulnerability, and patch management over SSH.

---

## 🛠️ System Requirements

Before running the application, make sure you have the following installed:
* **Docker & Docker Compose v2+** (recommended for all deployments and dependencies)
* **Java SDK 21** or later (LTS version, required for local backend development)
* **Node.js 22** or later and `npm` (required for local frontend development)
* **Go 1.23** or later (optional, only if compiling `gnm-pkg-worker` locally outside Docker)

---

## 🚀 Local Development Setup (Quick Start)

Follow these steps to run the full stack in development mode with live hot-reload.

### Step 1: Start Supporting Services with Docker Compose
LanAlmanac uses TimescaleDB (PostgreSQL-compatible) for state and time-series telemetry, Redis for event pub/sub, and `gnm_pkg_worker` for SSH operations. Start all dependencies in the background:
```bash
docker compose up -d
```
* **PostgreSQL (TimescaleDB)**: Port `5432` (`gnm` / `gnmpassword`)
* **Redis**: Port `6379`
* **Package Worker (`pkg_worker`)**: Port `8081` (internal container network)
* **Test Node Lab**: Includes pre-configured `ubuntu_ne`, `centos_ne`, and `alpine_ne` SSH test targets on the `172.28.0.0/24` subnet.

### Step 2: Run Quarkus Backend in Dev Mode
Start the Quarkus development server. Dev mode automatically recompiles Java classes on code changes, runs Flyway migrations, and hot-swaps resources:
```bash
GNM_VAULT_PASSWORD=my_secure_passcode ./gradlew :gnm-app:quarkusDev
```
* **Vault Password**: Required for Credential Vault envelope encryption (AES-256-GCM). Can be provided via `GNM_VAULT_PASSWORD`, `.env`, or manually entered in the Web GUI upon startup.
* **JWT Signing Keys**: Automatically generated as an RSA 2048-bit keypair in `./keys` on first boot.
* **Database Migrations**: Flyway automatically executes migrations (`V1` through `V28+`).
* **Mock Data Seeding**: If the database is empty, a mock service seeds sample network devices, historical telemetry, credentials, and OS profiles.
* **REST API Port**: Listens at `http://localhost:8080`.

### Step 3: Install & Start React Frontend
In a separate terminal, navigate to the frontend directory, install dependencies, and start Vite:
```bash
cd frontend
npm install
npm run dev
```
* **Vite Port**: The React client will listen at `http://localhost:5173`.
* **API Proxy**: Vite proxies `/api` and WebSocket endpoints to Quarkus on port `8080`.

### Step 4: Login to the Dashboard
Open your browser and navigate to:
👉 **[http://localhost:5173](http://localhost:5173)**

Authenticate using the default local administrator credentials:
* **Username**: `admin`
* **Password**: `admin`

---

## 📦 Production Deployment (Docker Compose)

Pre-built Docker images are published to the GitHub Container Registry (`ghcr.io`):
* `ghcr.io/skoczo/lanalmanac` (Core application: Quarkus + embedded React SPA)
* `ghcr.io/skoczo/lanalmanac-pkg-worker` (Stateless Go SSH package worker)

### 1. Prepare Environment Configuration
Copy the provided `.env.example` template:
```bash
cp .env.example .env
```
Edit `.env` to configure your passwords and secrets:
* `ADMIN_USERNAME` / `ADMIN_PASSWORD`: Credentials for the default administrator account.
* `GNM_VAULT_PASSWORD`: Master passphrase for the AES-256-GCM Credential Vault.
* `GNM_PKG_WORKER_AUTH_SECRET`: Shared secret (min 32 characters) for HMAC-SHA256 request authentication between the core monolith and the package worker.

### 2. Deploy with Docker Compose
Start the production stack:
```bash
docker compose -f docker-compose.prod.yml up -d
```
The application will be available at:
👉 **`http://<server-ip>:8787`** (or `http://localhost:8787`)

### 3. Testing Development Builds (Branch Images)
When testing features from active branches (such as `agentless-package-management`), set the image tags in `.env`:
```bash
GNM_APP_IMAGE=ghcr.io/skoczo/lanalmanac:dev-agentless-package-management
GNM_PKG_WORKER_IMAGE=ghcr.io/skoczo/lanalmanac-pkg-worker:dev-agentless-package-management
```
Then pull and restart:
```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

---

## 🛡️ Agentless Package, Vulnerability & Patch Management

LanAlmanac features agentless remote package management for Linux/POSIX hosts over SSH without requiring agents or daemons installed on managed machines:
* **Package Inventory**: Inspects installed packages using OS profiles (`apt`, `dnf`, `apk`, `pacman`, `zypper`, custom).
* **Vulnerability Assessment**: Correlates installed package versions with the OSV (Open Source Vulnerabilities) database, computing CVSS scores and severities.
* **SBOM Export**: Export device software bill of materials in standard CycloneDX 1.5 JSON format.
* **Update Detection**: Identifies pending security and general updates with advisory metadata.
* **Live Patch Execution**: Streams real-time patch installation output over Server-Sent Events (SSE) with strict privilege controls.
* **Automated Cron Scheduling**: Configure per-device automated update check and inventory refresh schedules.

### Target Managed Node Setup
To manage a remote Linux host:
1. Ensure the host has an active SSH server accessible by LanAlmanac.
2. In the LanAlmanac Web GUI, store an SSH credential (password or private key) in the **Credential Vault**.
3. For update and patch operations requiring root privileges, generate a minimal least-privilege `/etc/sudoers.d/lanalmanac` rule by clicking **"Copy Sudoers Snippet"** in the device's **Packages & Updates** tab or **Settings > OS Profiles**.

---

## 🏗️ Building Container Images Locally

If you prefer building the container images directly from source:

### Build Main Application Image (Backend + Frontend)
```bash
docker build -t lanalmanac:latest .
```
This multi-stage Docker build:
1. Compiles the React SPA using Node 22.
2. Packages the Quarkus fast-jar embedding the static frontend assets.
3. Produces a hardened Eclipse Temurin JRE 21 runtime container.

### Build Package Worker Image
```bash
docker build -t lanalmanac-pkg-worker:latest -f gnm-pkg-worker/Dockerfile ./gnm-pkg-worker
```

---

## 📂 Project Structure

* [gnm-app/](file:///workspaces/GreatNetworkManager/gnm-app) - Java 21 Quarkus modular monolith
  * `src/main/java/com/gnm/model/` - Panache database entities (Devices, Credentials, OS Profiles, Patch Jobs, Vulnerabilities)
  * `src/main/java/com/gnm/resource/` - JAX-RS REST endpoints and WebSocket definitions
  * `src/main/java/com/gnm/pkgmgmt/` - Agentless package management orchestration, vulnerability analysis, and SSE brokers
  * `src/main/java/com/gnm/vault/` - Envelope-encrypted Credential Vault engine (AES-256-GCM + Argon2id)
* [gnm-pkg-worker/](file:///workspaces/GreatNetworkManager/gnm-pkg-worker) - Stateless Go 1.23+ remote execution worker
  * `internal/transport/` - SSH client with TOFU (Trust-On-First-Use) host key verification
  * `internal/parser/` - Generic output parsers (delimited, key-value blocks, JSON map, regex)
  * `internal/handler/` - HMAC-authenticated HTTP handlers (`/v1/inventory`, `/v1/updates/check`, `/v1/patch/execute`)
* [frontend/](file:///workspaces/GreatNetworkManager/frontend) - React 19 Vite TypeScript dashboard
  * `src/pages/` - UI pages (Devices, Vulnerabilities, Topology, Settings)
  * `src/components/devices/details/` - Device detail tabs (Packages, Updates, Vulnerabilities, Schedule)
  * `src/components/settings/` - OS Profile and system settings
* [contracts/](file:///workspaces/GreatNetworkManager/contracts) - OpenAPI 3.1 specification for the worker contract (`pkg-worker-v1.openapi.yaml`)
* [docs/](file:///workspaces/GreatNetworkManager/docs) - Architectural guidelines, invariants, and specifications
