# Project Invariants — Read Before Every Change

Hard, non-negotiable rules. If a requested change conflicts with any rule below, stop and report
the conflict instead of working around it. Maximum file size: 100 lines. Do not bloat this file.

## Architecture (Non-Negotiable)

1. The core application (`gnm-app`) is a Quarkus modular monolith — the sole owner of PostgreSQL/Flyway,
   `VaultEngine`, Auth/RBAC, and public REST/WS APIs. Extracting a stateless worker container
   (e.g., `gnm-pkg-worker`) is allowed only behind an explicit, versioned internal contract
   (zero direct DB/Vault access, zero public port exposure, no arbitrary microservices).
2. Java 21. Concurrency via virtual threads and `StructuredTaskScope`.
   Forbidden: `new Thread(...)`, `Executors.newCachedThreadPool()`, `newFixedThreadPool`, `ManagedExecutor`.
   Allowed: `Executors.newVirtualThreadPerTaskExecutor()`, `Thread.startVirtualThread`.
3. PostgreSQL + TimescaleDB. Schema changes strictly via Flyway migrations — never `schema-management`.
4. Every JPA entity returned over REST must go through a DTO. Request bodies must never be JPA entities.
5. All code, logs, error messages, documentation, and UI text must be strictly in English.

## Security (Non-Negotiable)

6. Every new REST endpoint starts as `deny`. Adding `@PermitAll` requires a plan entry and justification.
7. Every resource class in `com.gnm.resource` must have an explicit `@RolesAllowed` or `@Authenticated`.
   Missing annotation is a bug, not "authenticated by default".
8. `sshHostKeyTrusted` is set exclusively by the dedicated trust endpoint. Never via mass assignment.
9. Secrets must never appear in logs, exceptions, HTTP responses, or the repository.
   Logging a secret is a critical vulnerability, not minor tech debt.
10. Cryptographic keys live in `byte[]`/`char[]` and are zeroed after use. Never `String` for a password or key.
11. New passwords, tokens, or keys: minimum AES-256-GCM / Argon2id with parameters no weaker than
    `m=65536, t=3, p=1`. Never roll custom cryptography.
12. Every external process invocation (`ProcessBuilder`) must pass arguments as a list, never `sh -c`.

## Contracts (Non-Negotiable)

13. `docs/architecture.md` is a design document, not the source of truth about current code. Do not edit casually.
14. Changing an API contract requires updating the frontend in the same step. Never leave a broken UI.
15. Adding a new dependency requires an entry in the plan. Without one, do not add it.

## Working Discipline

16. One session = one step from the active plan (`plans/hardening-plan.md` or `plans/package-management-plan.md`).
17. Read the minimum: `search_files` by signature, then `read_file` with `offset`/`limit`.
    Never read an entire large file when you only need a single method.
18. Verify edits via `git diff`, not by re-reading the whole file.
19. Run targeted tests: `--tests "*ClassNameTest"`. Full test suite only before committing a full stage.
20. Before marking a step done: `./gradlew :gnm-app:compileJava` and `./gradlew spotlessApply`.

## Known Repository Pitfalls

21. `build.gradle.kts` currently sets `ignoreFailures = true` — failing tests do not fail the Gradle build.
    Never treat a green Gradle exit code as proof that tests passed; inspect the JUnit XML reports.
22. `application.properties` historically has open public paths (`/api/vault/*`,
    `/ws/terminal/*`, `/api/devices/*/update`). Never copy this pattern to new endpoints.
23. The frontend sets a `jwt` cookie from JavaScript. Do not extend this mechanism.
24. `MockDataLoader` is a no-op that logs success. Do not trust startup logs alone.
25. `NetworkSightingQueue` is the sole entry point to fingerprinting — never bypass it.
