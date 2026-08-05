# NetAlmanac — Implementation Progress

This document tracks the completion of developmental phases for the NetAlmanac project.

---

## 📊 Summary of Progress

*   **Phase 1: Foundation, Auth & GUI Shell** — 🟩 **100% Completed**
*   **Phase 2: Discovery, Fingerprinting & Device UI** — 🟩 **100% Completed**
*   **Phase 3: Credential Vault & Vault UI** — ⬜ 0% Planned
*   **Phase 4: Remote Access & Terminal UI** — ⬜ 0% Planned
*   **Phase 5: Monitoring, Charts & Updates UI** — ⬜ 0% Planned
*   **Phase 6: Network Map, Polish & Hardening** — ⬜ 0% Planned

---

## 🟩 Phase 1: Foundation + Auth + GUI Shell (Completed)
- [x] **Backend Project Scaffold**: Configured Gradle Kotlin DSL multi-project with Java 21, matching dependencies for Quarkus REST, Hibernate Panache, and Flyway.
- [x] **Database Schema**: Implemented Flyway migration ([V1__init_schema.sql](file:///workspaces/GreatNetworkManager/gnm-app/src/main/resources/db/migration/V1__init_schema.sql)) with tables for devices, identities, fingerprints, credentials, and telemetry.
- [x] **JPA Entities**: Created Panache active-record mappings in `com.gnm.model`.
- [x] **JWT Cryptographic Keys**: Setup dynamic RSA-2048 key pair generation at startup (saved to `/keys`) in [KeyGeneratorService.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/service/KeyGeneratorService.java).
- [x] **Local Authentication Endpoint**: Created `POST /api/auth/login` returning signed JWT credentials in [LocalAuthResource.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/resource/LocalAuthResource.java).
- [x] **Mock Network Discovery Loader**: Seeds realistic home lab hosts, random MAC history, and 24 hours of telemetry history in [MockDataLoader.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/service/MockDataLoader.java) at startup.
- [x] **Device REST APIs**: Exposes device list, details, and telemetry history under [DeviceResource.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/resource/DeviceResource.java).
- [x] **Frontend Styling & Routing**: Structured custom dark themes, glassmorphism scrollbars, and TanStack Router paths inside [App.tsx](file:///workspaces/GreatNetworkManager/frontend/src/App.tsx).
- [x] **Auth Context Client**: Configured `apiClient` to automatically attach Bearer token headers and handle login checks.
- [x] **GUI Layout & Page Shells**: Coded the Sidebar, Header, Dashboard Bento grid (with custom SVG traffic sparklines/donut charts), search filter grids, tabbed device details, interactive SVG network maps, and vault passcode gates.

---

## 🟩 Phase 2: Discovery, Fingerprinting & Device UI (Completed)
- [x] **Passive Network Sniffer**: Implemented capturing loop with BPF compile filters matching ARP, DHCP, mDNS, SSDP, and TCP SYN in [PassivePacketListener.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/discovery/PassivePacketListener.java).
- [x] **Active Scanners & Scheduler**: Created parallel multi-threaded ICMP ping sweeping in [IcmpSweeper.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/discovery/IcmpSweeper.java), system ARP cache fallback scanning in [ArpScanner.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/discovery/ArpScanner.java), and wired up background schedules in [DiscoveryScheduler.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/discovery/DiscoveryScheduler.java).
- [x] **Fingerprint Matching Engine**: Coded the background matching and database persistence processor in [FingerprintEngine.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/fingerprint/FingerprintEngine.java).
- [x] **Similarity Calculator**: Implemented dynamic weighted Cosine Similarity math for MAC randomization merging in [SimilarityEngine.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/fingerprint/SimilarityEngine.java).
- [x] **WebSocket Event Broadcasting**: Established a live server endpoint in [EventWebSocket.java](file:///workspaces/GreatNetworkManager/gnm-app/src/main/java/com/gnm/resource/EventWebSocket.java) and matched it with real-time UI toast notifications in [Layout.tsx](file:///workspaces/GreatNetworkManager/frontend/src/components/Layout.tsx).

---

## ✅ Phase 3: Credential Vault & Vault UI (Completed)
- [x] Write secure envelope encryption engine (AES-256-GCM using derived Argon2id master key).
- [x] Implement backend unseal endpoint & credentials CRUD.
- [x] Connect frontend credential creation form dialog and secure reveal triggers.

---

## ⬜ Phase 4: Remote Access & Terminal UI (Planned)
- [ ] Create WebSocket SSH bridge server (Apache MINA SSHD + WebSocket Next).
- [ ] Build React browser terminal panel (`xterm.js` with WebGL rendering and split-pane resizers).
- [ ] Set up HTTP reverse proxy for iframe embedding of local web consoles.

---

## ⬜ Phase 5: Monitoring, Charts & Updates UI (Planned)
- [ ] Connect SNMP v2c/v3 poller and remote SSH command executors.
- [ ] Create TimescaleDB telemetry ingestion pipeline and log rotation triggers.
- [ ] Connect frontend Recharts area graphs to active backend metrics databases.
- [ ] Implement one-click remote package update scripts with live stream console output.

---

## ⬜ Phase 6: Network Map, Polish & Hardening (Planned)
- [ ] Render advanced interactive graph layout (`react-cytoscapejs`).
- [ ] Implement advanced active fingerprinting (JA4, TLS handshakes, HTTP banners).
- [ ] Harden Docker Compose production parameters and compile GraalVM native images.
