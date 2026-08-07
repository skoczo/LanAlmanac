---
name: Backend Development
description: Guidelines and stack for developing the Quarkus Java backend for GreatNetworkManager
---
# Backend Guidelines

## Tech Stack
- **Language**: Java 21+
- **Framework**: Quarkus 3.x
- **Build Tool**: Gradle (Kotlin DSL)
- **Database**: PostgreSQL 16 + TimescaleDB
- **ORM**: Hibernate ORM + Panache (Active Record or Repository pattern)
- **API**: Quarkus RESTEasy Reactive (JAX-RS) + WebSockets Next
- **Caching/PubSub**: Redis 7
- **Authentication**: Quarkus OIDC + Local Fallback (Argon2id JWT)

## Concurrency (Java 21)
- **Virtual Threads**: Use `@RunOnVirtualThread` for I/O-bound tasks. Avoid traditional Thread Pools or `ExecutorService`.
- **Structured Concurrency**: Use `StructuredTaskScope.ShutdownOnFailure` for fan-out tasks (e.g., active fingerprinting, port scanning).
- **Producer-Consumer Queues**: Use `LinkedBlockingQueue` for passing events between continuous listeners and consumer threads.

## Architecture Patterns
- **Modular Monolith**: Code is organized into cohesive modules (`discovery`, `fingerprint`, `vault`, `remote`, `monitor`, `scheduler`) within a single Quarkus app.
- **Background Processes**: Passive listeners (e.g., `pcap4j` sniffing) run continuously on a Virtual Thread via `@Observes StartupEvent`.
- **Scheduled Tasks**: Use `quarkus-scheduler` (`@Scheduled`) for periodic active scans (ARP, ICMP, SNMP polling).
- **Security**: The `CredentialVault` uses a Master Passphrase to derive a Key Encryption Key (KEK) using Argon2id. The KEK is kept in-memory and decrypts Data Encryption Keys (DEKs) which decrypt AES-256-GCM encrypted credentials.

## Best Practices
- Never log credentials, payloads, or cryptographic keys.
- Write tests utilizing Quarkus Dev Services where appropriate.
- Keep REST endpoints non-blocking where possible, delegating to virtual threads.
