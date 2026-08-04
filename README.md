# NetAlmanac

NetAlmanac is a self-hosted LAN management tool designed as a **modular monolith** with a Quarkus JAX-RS/WebSocket backend and a premium React 19 + Tailwind v4 dark-mode SPA frontend dashboard.

---

## 🛠️ System Requirements

Before running the application, make sure you have the following installed:
* **Docker & Docker Compose** (for PostgreSQL database, TimescaleDB, and Redis)
* **Java SDK 21** or later (LTS version)
* **Node.js 22** or later (along with `npm`)

---

## 🚀 Local Development Setup (Quick Start)

Follow these steps to run the backend and frontend in development hot-reload modes.

### Step 1: Start PostgreSQL & Redis
GNM stores relational states in TimescaleDB (Postgres compatible) and telemetry events in Redis. Start these services in the background:
```bash
docker compose up -d
```
*This starts Postgres on port `5432` and Redis on port `6379`.*

### Step 2: Run Quarkus Backend in Dev Mode
Start the Quarkus development server. Dev mode automatically recompiles Java classes on code changes, runs Flyway migrations, and hot-swaps resources:
```bash
./gradlew :gnm-app:quarkusDev
```
* **JWT Signing Keys**: On the first start, GNM will automatically generate a secure 2048-bit RSA keypair in the `./keys` folder.
* **Database migrations**: Flyway automatically creates the tables at startup.
* **Mock Data**: A mock data service detects an empty database and automatically seeds 7 devices, history, credentials, and 24 hours of telemetry metrics for dashboard visualization.
* **REST API Port**: Backend will listen at `http://localhost:8080`.

### Step 3: Install & Start React Frontend
In a new terminal window, navigate to the frontend folder, install the packages, and boot the Vite development server:
```bash
cd frontend
npm install
npm run dev
```
* **Vite Port**: The React client will listen at `http://localhost:5173`.
* **API Proxy**: Vite is preconfigured to proxy `/api` calls directly to the Quarkus backend on port `8080`.

### Step 4: Login to the Dashboard
Open your browser and navigate to:
👉 **[http://localhost:5173](http://localhost:5173)**

Authenticate using the default local credentials:
* **Username**: `admin`
* **Password**: `admin`

---

## 📦 Production Deployment (Docker Compose)

To build and run the entire application bundle as a single multi-container production stack:

1. Create a `.env` file from the environment template (if configured).
2. Start the stack with build instructions:
```bash
docker compose -f docker-compose.yml up --build -d
```
The multi-stage `Dockerfile` will:
1. Compile the React frontend SPA into static assets.
2. Compile and package the Quarkus backend in JVM mode, embedding the static frontend files inside the JAR resource folder (`META-INF/resources`).
3. Deploy the final container using the Eclipse Temurin JRE runtime alongside the TimescaleDB and Redis containers.

---

## 📂 Project Structure

* [gnm-app/](file:///workspaces/GreatNetworkManager/gnm-app) - Java 21 Quarkus backend project
  * `src/main/java/com/gnm/model/` - JPA active-record Panache database entities
  * `src/main/java/com/gnm/resource/` - JAX-RS REST endpoints and WebSocket definitions
  * `src/main/java/com/gnm/service/` - JWT key generation services and mock data seeders
* [frontend/](file:///workspaces/GreatNetworkManager/frontend) - React 19 Vite TypeScript client
  * `src/App.tsx` - App entry point with TanStack Router path configurations
  * `src/lib/auth/` - Local token-based fetch clients and React session contexts
  * `src/pages/` - UI dashboard layouts, device tables, details tabs, topology map, and vault drawers
* [docs/](file:///workspaces/GreatNetworkManager/docs) - Architectural guidelines and specifications
