# GreatNetworkManager (NetAlmanac) Project Rules

## Architecture & Core Philosophy
1. **Modular Monolith**: The system is designed as a modular monolith using Java 21 and Quarkus. Do NOT introduce microservices or separate network services.
2. **PostgreSQL & TimescaleDB**: The database handles both relational structures (devices, identities) and time-series telemetry (via TimescaleDB hypertables).
3. **Event-Driven UI**: Real-time updates are pushed via WebSocket (`/ws/events`). Zustand stores handle these updates on the frontend.
4. **No Ephemeral Entities**: Ephemeral IPs/MACs are merged into persistent `PhysicalDevice` entities using fingerprinting (DHCP, TCP, mDNS, JA4).
5. **Security First**: The `CredentialVault` uses envelope encryption (AES-256-GCM) with a sealed-key architecture (Argon2id KEK). Keys must NEVER be logged or written to disk.

## Development Constraints
- **Do not introduce heavy frameworks** unless approved. The stack is already chosen: Quarkus for Backend, React+Vite for Frontend.
- **Do not break the UI design system**: Use the existing Tailwind CSS 4 variables and Shadcn/ui components. The app uses a premium dark-mode aesthetic.
- **Concurrency**: Use Java 21 Virtual Threads (`@RunOnVirtualThread`) and `StructuredTaskScope` for concurrency instead of traditional Thread Pools or ExecutorServices.

## Documentation
- Refer to `docs/architecture.md` for detailed architectural decisions, database schemas, and module breakdowns.

Whenever you make changes to the backend, you must always run /home/skoczo/workspace/GreatNetworkManager/run_tests.sh to verify your changes before finishing.