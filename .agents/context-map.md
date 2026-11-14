# Mapa kontekstu — czytaj to zamiast eksplorować repo

Cel: odpowiedzieć na pytanie "gdzie to jest" bez `read_file` na dużych plikach.
Aktualizuj ten plik, gdy przeniesiesz lub dodasz istotny plik.

## Czego NIE czytać (marnotrawstwo tokenów)

| Plik | Rozmiar | Co robić zamiast tego |
|:---|:---|:---|
| `docs/architecture.md` | 1778 linii | Sekcje 1-4 to opis wizji, nie stan kodu. Pytaj o konkretną klasę. |
| `docs/progress.md` | ~67 linii | Tylko status faz. Nie wnosi wiedzy o kodzie. |
| `frontend/package-lock.json` | ogromny | `grep` konkretnej paczki, jeśli naprawdę trzeba. |
| `frontend/src/pages/DeviceDetail.tsx` | ~1700 linii | `search_files` po nazwie handlera lub tekstu UI. |
| `gnm-app/src/main/java/com/gnm/service/BackupService.java` | 521 linii | Metody: 22-73 export/import, 348-386 pg_dump/psql, 431-483 krypto. |
| `test_execution_*.log`, `gnm.log`, `*.log` | duże | Nigdy nie czytaj. `grep` po błędzie. |

## Wejście do systemu — kolejność czytania przy nowym zadaniu

1. `.agents/invariants.md` (zawsze)
2. Ten plik — sekcja dla obszaru, którego dotyczy zadanie
3. Konkretna klasa, przez `search_files` po nazwie metody

## Backend — `gnm-app/src/main/java/com/gnm/`

### Bezpieczeństwo i auth
| Plik | Co zawiera | Uwaga |
|:---|:---|:---|
| `auth/PasswordService.java` | Argon2id hash + verify, format `$argon2id$v=19$m=...` | OK jakościowo. Wzór do naśladowania. |
| `auth/UserBootstrap.java` | Seed admina ze zmiennych env, `mustChangePassword=true` | Domyślne `admin/admin` — do usunięcia w Etapie 4. |
| `auth/DynamicOidcTenantResolver.java` | Czyta `oidc.*` z `GlobalSetting` na każdym żądaniu | **Ryzyko eskalacji** — patrz plan 1.7/Etap 2. |
| `auth/AuthTicketService.java` | Jednorazowe bilety TTL 30s (`issue`, `consume`, `purgeExpired`) | Używane do handshake SSE/WS. |
| `resource/AuthTicketResource.java` | `POST /api/auth/ticket` (`@RolesAllowed`) | Wydaje bilety dla `sse-update`, `ws-terminal`, `ws-events`. |
| `resource/LocalAuthResource.java` | login (50), change-password (88), `extractUsernameFromToken` (141) | 141: ręczne Base64 JWT bez weryfikacji podpisu — Etap 1.7. |

### Vault
| Plik | Co zawiera | Uwaga |
|:---|:---|:---|
| `service/VaultEngine.java` | Argon2 KEK + AES-GCM, plik `keys/.vault_master`, unseal (110), encrypt (168), decrypt (186) | Brak zeroizacji, brak `synchronized`, brak auto-locka — Etap 4. |
| `resource/VaultResource.java` | `@PermitAll` (16), status (29), init (41), unseal (58), lock (79) | Fallback hasła z konfiguracji — Etap 1.5. |
| `resource/CredentialResource.java` | lista (34), create (51), reveal (90), delete (115), update (126) | Brak sprawdzenia właściciela — Etap 2/3. |

### Zasoby REST — mapa uprawnień
| Plik | Adnotacja dziś | Rolę docelową wyznacza Etap 2 |
|:---|:---|:---|
| `resource/DeviceResource.java` | `@RolesAllowed("gnm-admin")` (34) | duży plik, metody: 46 getAll, 64 getById, 98 probe-update, 113-176 services CRUD, 196 update, 224 state, 247 trust-ssh, 268 labels, 284 discover, 336 manual, 379-400 historia |
| `resource/TopologyResource.java` | `@RolesAllowed("gnm-admin")` (23) | |
| `resource/DiagnosticsResource.java` | `@RolesAllowed("gnm-admin")` (26) | |
| `resource/BackupResource.java` | `@RolesAllowed("gnm-admin")` (20) | hasło w query (30) — Etap 5.1 |
| `resource/ThreatResource.java` | brak | GET mutuje bazę (29-57) — Etap 9.5 |
| `resource/SettingsResource.java` | brak | PUT bez DTO (31) — Etap 3.2 |
| `resource/UserResource.java` | brak | tworzenie admina bez roli (82) — Etap 2 |
| `resource/CredentialResource.java` | brak | Etap 2/3 |
| `resource/ScannerResource.java` | brak | Etap 2 |
| `resource/DiscoveryResource.java` | brak | Etap 2 |
| `resource/TelemetryResource.java` | brak | brak limitu (18) — Etap 9.3 |
| `resource/UpdateResource.java` | brak, ścieżka publiczna | SSE, `sudo apt-get upgrade` (91) — Etap 1.2 |
| `resource/VersionResource.java` | brak | bezpieczny, publiczny |

### WebSockety
| Plik | Linia | Uwaga |
|:---|:---|:---|
| `resource/TerminalWebSocket.java` | 37 ścieżka, 64 onOpen, 150 connectSsh, 157 weryfikator host key, 331 onMessage | Ścieżka publiczna — Etap 1.3. TOFU działa poprawnie. |
| `resource/EventWebSocket.java` | 13 ścieżka, 34-52 broadcast device, 53-67 threat, 87 activity | Broadcast do wszystkich — Etap 1.4 |

### Discovery i fingerprint
| Plik | Co zawiera |
|:---|:---|
| `discovery/DiscoveryScheduler.java` | `@Scheduled` + start pasywnego nasłuchu (34) |
| `discovery/PassivePacketListener.java` | pcap BPF, kolejka sightingów |
| `discovery/IcmpSweeper.java` | sweep, `ProcessBuilder ping`/`arping` (164, 203) — argumenty bezpieczne |
| `discovery/ArpScanner.java` | fallback z tablicy ARP systemu |
| `discovery/NetworkSightingQueue.java` | jedyne wejście do fingerprintowania |
| `fingerprint/FingerprintEngine.java` | konsument kolejki, `newScheduledThreadPool` (88), `new Thread` (89) |
| `fingerprint/SimilarityEngine.java` | cosine similarity, próg 0.75 |
| `fingerprint/probes/Jndi*.java` | JNDI DNS — **do przepisania**, Etap 5.5 |
| `service/SubnetFilter.java` | walidacja CIDR, czysta logika — dobry kandydat na testy jqwik |

### Konfiguracja i infrastruktura
| Plik | Uwaga |
|:---|:---|
| `src/main/resources/application.properties` | linia 35: lista ścieżek publicznych — Etap 1; 8-9, 51: domyślne poświadczenia — Etap 4 |
| `src/main/resources/db/migration/` | V1-V23; kolejna migracja to **V24** |
| `build.gradle.kts` | linia 77 `ignoreFailures = true` — Etap 7.1 |
| `../build.gradle.kts` (root) | Sonar `sonar.sources=frontend/src` tylko — Etap 7.3 |
| `../.github/workflows/build-and-push.yml` | `continue-on-error` w 47 i 54 — Etap 7.2 |

## Frontend — `frontend/src/`

| Plik | Co zawiera | Uwaga |
|:---|:---|:---|
| `lib/auth/auth-context.tsx` | OIDC init (73), cookie `jwt` (112, 131, 151, 176, 206), `apiClient` (212) | Token w `localStorage` — Etap 1.8/10 |
| `lib/vault/vault-context.tsx` | status vaulta, modal unseal | cienki, bezpieczny |
| `components/Terminal.tsx` | xterm, WS URL (74), resize, onData | brak biletu — Etap 1.3 |
| `components/Layout.tsx` | sidebar, toast z `/ws/events` | Etap 1.4 |
| `App.tsx` | routing TanStack, guard (23) | `isAuthenticated` z tokenu — pamiętaj przy zmianach |
| `pages/Login.tsx` | formularz + SSO | |
| `pages/DeviceDetail.tsx` | duży plik, `EventSource /update` (1622) | Etap 1.2 |
| `pages/UsersTab.tsx`, `pages/Settings.tsx` | sprawdzają `gnm-admin` po stronie UI | UI nie jest kontrolą dostępu |
| `eslint.config.js` | `no-explicit-any` = warn (27) | Etap 8.3 |

## Testy — gdzie dopisać nowy test

| Obszar | Plik testu |
|:---|:---|
| Autoryzacja per rola | nowy `resource/AuthorizationMatrixTest.java` |
| Vault | `CredentialVaultTest.java`, `service/VaultEngineTest.java` |
| SSH/WS | `RemoteAccessTest.java`, `SecurityAndThreatTest.java` |
| Fingerprint | `fingerprint/*Test.java` |
| Backup | `BackupCompatibilityTest.java`, `service/BackupServiceTest.java` |
| E2E UI | `frontend/tests/*.spec.ts` |

Wzorzec testów integracyjnych: `AbstractE2ETest.java` (Testcontainers, gotowa infrastruktura).

## Komendy — minimalny zestaw

> Uwaga (host z domyślnym JDK 25 poza devcontainerem): poprzedzaj `./gradlew` zmienną `JAVA_HOME=/home/skoczo/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`.

```bash
./gradlew :gnm-app:compileJava                      # szybka weryfikacja
./gradlew spotlessApply                             # przed każdym commitem
./gradlew :gnm-app:test --tests "*VaultEngineTest"  # wybiórczo
cd frontend && npx tsc -b                           # typy
cd frontend && npx eslint src/pages/DeviceDetail.tsx
git diff --stat                                     # zamiast czytania plików
```
