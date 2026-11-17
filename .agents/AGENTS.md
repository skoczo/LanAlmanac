# GreatNetworkManager (NetAlmanac) Project Rules

## Architecture & Core Philosophy
1. **Core Modular Monolith + Approved Stateless Workers**: The core (`gnm-app`, Java 21 / Quarkus) is a modular monolith and the sole owner of PostgreSQL/Flyway, `VaultEngine`, Auth/RBAC, and public REST/WS APIs. Explicitly approved stateless worker containers (e.g., `gnm-pkg-worker`) are allowed only behind a versioned, authenticated internal HTTP/streaming contract with zero direct DB access and no public port exposure. Do NOT introduce arbitrary microservices.
2. **PostgreSQL & TimescaleDB**: The database handles both relational structures (devices, identities) and time-series telemetry (via TimescaleDB hypertables).
3. **Event-Driven UI**: Real-time updates are pushed via WebSocket (`/ws/events`). Zustand stores handle these updates on the frontend.
4. **No Ephemeral Entities**: Ephemeral IPs/MACs are merged into persistent `PhysicalDevice` entities using fingerprinting (DHCP, TCP, mDNS, JA4).
5. **Security First**: The `CredentialVault` uses envelope encryption (AES-256-GCM) with a sealed-key architecture (Argon2id KEK). Keys must NEVER be logged or written to disk.

## Development Constraints
- **Do not introduce heavy frameworks** unless approved. The stack is already chosen: Quarkus for Backend, React+Vite for Frontend.
- **Do not break the UI design system**: Use the existing Tailwind CSS 4 variables and Shadcn/ui components. The app uses a premium dark-mode aesthetic.
- **Concurrency**: Use Java 21 Virtual Threads (`@RunOnVirtualThread`) and `StructuredTaskScope` for concurrency instead of traditional Thread Pools or ExecutorServices.
- **Language**: The entire application (UI components, error messages, log output, status messages, API entities) MUST be strictly in **English**.

## Documentation
- `docs/architecture.md` is a design document, NOT a description of current code. It contains 1778 lines; do not read it in full.
- **Always read `.agents/invariants.md` before any change.** It contains hard, non-negotiable rules.
- **Use `.agents/context-map.md` to locate code** instead of exploring the repository. It lists which files are expensive to read and what to avoid.
- Task plans live in `plans/`. Active hardening work: `plans/hardening-plan.md`.

## Issue & Dead Code Detection Tasks
- `./gradlew detectBackendIssues`: Scans backend (`gnm-app`) Java source files using PMD/Javac, and executes SonarCloud analysis if `SONAR_TOKEN` is set.
- `./gradlew detectFrontendIssues`: Runs TypeScript type checking (`tsc -b`), ESLint, and unimported dead code scanner on `frontend`.
- `./gradlew detectAllIssues`: Executes both backend and frontend issue scans sequentially.
- `./gradlew sonar`: Executes SonarCloud analysis (pass `-Dsonar.token=<your-token>`).

Whenever you make changes to the backend, you must always run `./gradlew spotlessApply` first.

**Do NOT run the full `run_tests.sh` for every change.** It builds three Docker images, starts Quarkus, installs Playwright browsers and runs the whole suite — that is slow, noisy and burns tokens. Instead:
- single change: `./gradlew :gnm-app:compileJava` plus the one test named in the task brief
- end of a plan step: `./gradlew :gnm-app:test --tests "*ClassNameTest"`
- only before committing a whole stage: `./run_tests.sh`

When writing temporary scripts (e.g., Python/Bash) to automate project tasks or refactoring, you MUST place them inside the `.agent_scripts/` directory so they are ignored by Git.

Don't use scripts to fix code. Just change code we are wasting tokens on script creation and still script is not working well.

## Working Discipline
- One agent session = one step from `plans/hardening-plan.md`. Do not mix a refactor with a security fix.
- Read the minimum: `search_files` for the signature, then `read_file` with `offset`/`limit`. Never read a 500-line resource class in full.
- After editing, verify with `git diff <file>` instead of re-reading the file.
- Never invent a new dependency without an entry in the plan.
- If a requested change conflicts with `.agents/invariants.md`, stop and report the conflict instead of working around it.

## Definition of Done
- [ ] `./gradlew :gnm-app:compileJava` passes
- [ ] `./gradlew spotlessApply` applied
- [ ] The specific test named in the task brief passes
- [ ] `git diff` touches only the files listed in the brief
- [ ] If an API contract changed, the frontend was updated in the same step
- [ ] Checkbox ticked in `plans/hardening-plan.md`

## Security Baseline
- A new REST endpoint starts as `deny`. Adding `@PermitAll` requires a plan entry and a justification.
- Every class in `com.gnm.resource` must have an explicit `@RolesAllowed` or `@Authenticated`. Missing annotation is a bug, not "authenticated by default".
- Secrets must never appear in logs, exception messages, HTTP responses or the repository.
- Cryptographic keys live in `byte[]`/`char[]` and are zeroed after use. Never `String` for a password or key.
- Never pass request data into `ProcessBuilder` as a shell string; always pass an argument list.