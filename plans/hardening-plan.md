# Plan utwardzania GreatNetworkManager / LanAlmanac

Wykonywany małymi krokami. Każdy krok jest samodzielnie wdrażalny i weryfikowalny.
Kolejność etapów jest celowa: najpierw zamykamy dostęp, potem budujemy siatkę jakości,
na końcu wydajność i higiena.

Legenda rozmiaru (nie czas, tylko liczba plików i ryzyko):
- **S** — 1-2 pliki, zmiana lokalna, niskie ryzyko
- **M** — 3-5 plików, wymaga testu
- **L** — więcej niż 5 plików lub zmiana kontraktu API

---

## Etap 0 — Baza wiedzy agenta

- [x] **0.1** Utworzyć [`.agents/invariants.md`](../.agents/invariants.md) (twarde reguły, <100 linii) — **S**
- [x] **0.2** Utworzyć [`.agents/context-map.md`](../.agents/context-map.md) (mapa plików z numerami linii, sekcja "nie czytaj") — **S**
- [x] **0.3** Utworzyć szablon `plans/_brief-template.md` do briefów jednokrokowych — **S**
- [x] **0.4** Dopisać do [`.agents/AGENTS.md`](../.agents/AGENTS.md) sekcję "Definition of Done" i odsyłacze do powyższych — **S**

**Kryterium:** agent potrafi odpowiedzieć na pytanie o dowolny moduł, czytając maksymalnie 2 pliki `.agents/`.

---

## Etap 1 — Zamknięcie dostępu (P0, najwyższe ryzyko)

Stan obecny: [`application.properties`](../gnm-app/src/main/resources/application.properties:35) wystawia bez uwierzytelnienia vault, terminal SSH, SSE z `sudo apt-get upgrade`, WebSocket zdarzeń i change-password.

### 1.1 [x] Bilety jednorazowe dla SSE i WebSocket — **M**
**Cel:** mechanizm, który pozwoli zamknąć trzy endpointy bez łamania frontendu (EventSource i WebSocket nie potrafią wysłać nagłówka `Authorization`).

**Pliki:**
- nowy `gnm-app/src/main/java/com/gnm/auth/AuthTicketService.java`
- nowy `gnm-app/src/main/java/com/gnm/resource/AuthTicketResource.java`
- nowy `gnm-app/src/main/java/com/gnm/dto/AuthTicket.java`

**Zmiana:**
- `POST /api/auth/ticket` (uwierzytelniony, rola `gnm-admin` lub `gnm-operator`) przyjmuje `{ "purpose": "sse-update" | "ws-terminal" | "ws-events", "resourceId": "<uuid|brak>" }`
- zwraca `{ "ticket": "<losowy 32B base64url>", "expiresAt": "<iso>" }`
- ticket przechowywany w pamięci: `Map<String, Ticket>`, TTL 30 s, jednorazowy
- ticket zawiera: `username`, `roles`, `purpose`, `resourceId`, `expiry`
- `consume(ticket, purpose, resourceId)` — atomowo usuwa i waliduje (`ConcurrentHashMap.remove`), zwraca `Optional<Ticket>`
- czyszczenie wygasłych: `@Scheduled(every = "60s")`

**Kryterium:** test jednostkowy pokrywa: użycie poprawne, podwójne użycie (odrzucone), zły `purpose`, wygaśnięcie, obcy `resourceId`.

**Weryfikacja:** `./gradlew :gnm-app:test --tests "*AuthTicketServiceTest"`

### 1.2 SSE `/api/devices/{id}/update` — **M**
**Pliki:** [`UpdateResource.java`](../gnm-app/src/main/java/com/gnm/resource/UpdateResource.java:36), [`application.properties`](../gnm-app/src/main/resources/application.properties:35)

**Zmiana:**
- `GET` → `POST /api/devices/{id}/update?ticket=...` (POST wyklucza CSRF przez `<img src>`)
- walidacja `consume(ticket, "sse-update", id)`; brak/nieprawidłowy → `401`, bez uruchamiania SSH
- usunąć `/api/devices/*/update` z `permission.public.paths`
- zamienić `ManagedExecutor` na wirtualny wątek zgodnie z regułą projektu
- limit czasu sesji SSE (np. 15 min) i zamknięcie strumienia

**Kryterium:** żądanie bez biletu zwraca 401 i nie wykonuje żadnego połączenia SSH; żądanie z biletem działa jak dotychczas.

**Weryfikacja:** `./gradlew :gnm-app:test --tests "*UpdateResourceTest"`

### 1.3 Terminal WebSocket — **M**
**Pliki:** [`TerminalWebSocket.java`](../gnm-app/src/main/java/com/gnm/resource/TerminalWebSocket.java:37), [`Terminal.tsx`](../frontend/src/components/Terminal.tsx:74)

**Zmiana:**
- ścieżka `/ws/terminal/{deviceId}/{credentialId}?ticket=...`
- `@OnOpen` pobiera `ticket` z `connection.handshakeRequest().query()` i woła `consume(ticket, "ws-terminal", deviceId)`
- brak biletu → `connection.close()` przed jakimkolwiek dostępem do bazy i vaulta
- usunąć `/ws/terminal/*` z `permission.public.paths`
- frontend: pobierz bilet przez `apiClient`, dołącz do URL

**Kryterium:** nieuwierzytelniony handshake jest odrzucany; nieuwierzytelniony użytkownik nie powoduje żadnego zapisu do bazy (`NetworkService` nie powstaje).

**Weryfikacja:** `./gradlew :gnm-app:test --tests "*RemoteAccessTest"` + `npx playwright test`

### 1.4 WebSocket `/ws/events` — **S**
**Pliki:** [`EventWebSocket.java`](../gnm-app/src/main/java/com/gnm/resource/EventWebSocket.java:13), [`Layout.tsx`](../frontend/src/components/Layout.tsx)

**Zmiana:** to samo co 1.3 z `purpose = "ws-events"`; usunąć `/ws/events` z `permit`; ograniczyć broadcast do zalogowanych połączeń.

**Kryterium:** brak biletu = brak subskrypcji; zdarzenia nie wyciekają do anonimowych klientów (nazwy urządzeń, IP, alarmy).

### 1.5 Vault — koniec z `@PermitAll` — **S**
**Pliki:** [`VaultResource.java`](../gnm-app/src/main/java/com/gnm/resource/VaultResource.java:16), [`application.properties`](../gnm-app/src/main/resources/application.properties:35)

**Zmiana:**
- usunąć `@PermitAll` i `/api/vault/*` z `permit`
- `@RolesAllowed("gnm-admin")` na całej klasie
- **usunąć fallback** `vaultPassword.orElse(null)` — hasło wyłącznie z ciała żądania; konfiguracja `gnm.vault.password` do wykreślenia
- `initialize` i `unseal` przez `AuthTicketService`-owy licznik prób (patrz 1.6)

**Kryterium:** `curl -X POST /api/vault/unseal` bez tokenu → 401. Nie da się odpieczętować vaulta hasłem z konfiguracji.

### 1.6 Rate limiting i blokada konta — **M**
**Pliki:** nowy `gnm-app/src/main/java/com/gnm/auth/LoginThrottle.java`, [`LocalAuthResource.java`](../gnm-app/src/main/java/com/gnm/resource/LocalAuthResource.java:50), [`VaultResource.java`](../gnm-app/src/main/java/com/gnm/resource/VaultResource.java:60)

**Zmiana:**
- licznik nieudanych prób per `username` i per IP: próg 5, okno 15 min, blokada 15 min
- na `/api/vault/unseal` próg 3, blokada 5 min (Argon2id i tak spowalnia, ale licznik chroni CPU)
- odpowiadać tak samo dla "nie ma użytkownika" i "złe hasło" (już OK) — zachować
- ujednolicić czas odpowiedzi dla nieistniejącego użytkownika (dummy Argon2 verify), aby nie wyciekać informacji o kontach

**Kryterium:** 6. próba logowania zwraca 429; test mierzy, że czas odpowiedzi dla nieistniejącego użytkownika jest w tym samym rzędzie wielkości.

### 1.7 `change-password` bez ręcznego dekodowania JWT — **S**
**Pliki:** [`LocalAuthResource.java`](../gnm-app/src/main/java/com/gnm/resource/LocalAuthResource.java:88)

**Zmiana:**
- usunąć `extractUsernameFromToken()` (Base64 + `ObjectMapper`, brak weryfikacji podpisu)
- wstrzyknąć `JsonWebToken` i brać `getSubject()`/`getName()`
- usunąć `/api/auth/change-password` z `perm` — ma być wymagane uwierzytelnienie
- dodać `jti` i `aud` do generowanych tokenów, TTL z konfiguracji zamiast zaszytych 24 h
- po zmianie hasła unieważnić poprzednie sesje (pole `tokenVersion` w `GnmUser`, sprawdzane w filtrze)

**Kryterium:** token z podmienionym payloadem (nieprawidłowy podpis) nie pozwala zmienić hasła.

### 1.8 Frontend: koniec z JWT w cookie z JS — **M**
**Pliki:** [`auth-context.tsx`](../frontend/src/lib/auth/auth-context.tsx:112), [`Terminal.tsx`](../frontend/src/components/Terminal.tsx:74), [`DeviceDetail.tsx`](../frontend/src/pages/DeviceDetail.tsx:1622)

**Zmiana:**
- usunąć wszystkie `document.cookie = \`jwt=...\`` (4 miejsca) — token nie może być czytelny dla JS w cookie
- wpiąć bilety z 1.1 w SSE, oba WebSockety
- token trzymać w pamięci + `sessionStorage` zamiast `localStorage` (mniejsza ekspozycja przy XSS)
- dodać CSP z `frame-ancestors 'none'` w nagłówkach Quarkusa

**Kryterium:** w narzędziach devtools brak cookie `jwt`; terminal i strumień aktualizacji działają.

**Weryfikacja:** `cd frontend && npm run build && npx playwright test`

---

## Etap 2 — Role i uprawnienia — **M**

**Pliki:** wszystkie zasoby w `gnm-app/src/main/java/com/gnm/resource/`, nowy `gnm-app/src/main/java/com/gnm/auth/Permissions.java`

**Zmiana:** jawne `@RolesAllowed` na każdej klasie zasobu według macierzy:

| Zasób | viewer | operator | admin |
|:---|:---|:---|:---|
| devices GET, telemetry GET, topology GET, threats GET | tak | tak | tak |
| threats resolve/note | nie | tak | tak |
| scanner POST | nie | tak | tak |
| discovery toggle/trigger | nie | tak | tak |
| credentials lista | nie | tak | tak |
| credentials reveal | nie | nie | tak |
| credentials create/update/delete | nie | tak | tak |
| users | nie | nie | tak |
| settings | nie | nie | tak |
| vault | nie | nie | tak |
| backup | nie | nie | tak |
| diagnostics | nie | nie | tak |
| update (SSE) | nie | tak | tak |

Dodatkowo: reguła domyślna `deny` — każdy nowy zasób bez adnotacji ma być odrzucany. Osiągnąć przez zmianę polityki: `quarkus.http.auth.permission.api.policy=authenticated` zostaje, ale dochodzi `@Authenticated` wymuszone testem ArchUnit (etap 7).

**Kryterium:** test integracyjny na każdą rolę: viewer dostaje 403 na wszystkich mutacjach (lista endpointów w teście).

---

## Etap 3 — Mass assignment i wyciek danych z encji — **M**

- [ ] **3.1** DTO wejściowe dla `NetworkService` (bez `sshHostKey` i `sshHostKeyTrusted`) — [`DeviceResource.java`](../gnm-app/src/main/java/com/gnm/resource/DeviceResource.java:141) — **S**
- [ ] **3.2** DTO wejściowe dla `GlobalSetting` (tylko `value`, `key` z path) — [`SettingsResource.java`](../gnm-app/src/main/java/com/gnm/resource/SettingsResource.java:31) — **S**
- [ ] **3.3** `@JsonIgnore` na `encryptedPayload`/`noncePayload` w [`Credential.java`](../gnm-app/src/main/java/com/gnm/model/Credential.java:30) — **S**
- [ ] **3.4** Odpowiedzi REST tylko przez DTO dla `PhysicalDevice` (dziś encja z całym grafem) — **M**
- [ ] **3.5** Test regresyjny: `PUT /services/{id}` z `sshHostKeyTrusted: true` → pole niezmienione — **S**

**Kryterium:** żaden endpoint nie przyjmuje encji JPA jako ciała żądania.

---

## Etap 4 — Sekrety i kryptografia — **M**

- [ ] **4.1** Usunąć [`privateKey.pem`](../gnm-app/src/main/resources/privateKey.pem:1) z `resources` — **S**
- [ ] **4.2** Fail-fast: aplikacja nie startuje, gdy `GNM_AUTH_LOCAL_PASSWORD` lub hasło bazy mają wartość domyślną w profilu `prod` — **S**
- [ ] **4.3** Usunąć `gnm.auth.local.password=admin` i `gnmpassword` z [`application.properties`](../gnm-app/src/main/resources/application.properties:8) — **S**
- [ ] **4.4** [`VaultEngine`](../gnm-app/src/main/java/com/gnm/service/VaultEngine.java:31): zeroizacja `kekBytes`, `masterKeyBytes`, `synchronized` na `initialize`/`unseal`/`lock`, plik vaulta `0600`, katalog `0700` — **M**
- [ ] **4.5** Auto-lock po bezczynności (`gnm.vault.auto-lock-minutes`) + czyszczenie klucza w `ShutdownEvent` — **M**
- [ ] **4.6** Tabela `vault_audit_log` (migracja V24): `username`, `action`, `credential_id`, `success`, `at`, `source_ip`; zapis przy każdym reveal/encrypt/decrypt — **M**
- [ ] **4.7** Zrotować token Sonar obecny w `.env` — **S** (działanie poza kodem)

**Kryterium:** test sprawdza, że po `lockVault()` odwołanie do klucza jest niemożliwe, a plik vaulta ma uprawnienia 600.

---

## Etap 5 — Backup i JNDI — **M**

- [ ] **5.1** Hasło backupu z ciała żądania zamiast query stringa — [`BackupResource.java`](../gnm-app/src/main/java/com/gnm/resource/BackupResource.java:30) — **S**
- [ ] **5.2** Usunąć `System.exit(0)` i `new Thread` z restore; użyć zdarzenia restartu i wirtualnego wątku — **M**
- [ ] **5.3** `pg_dump`/`psql`: brak cichego dummy dumpa, propagacja błędu, limit czasu, hasło przez `PGPASSFILE` a nie `PGPASSWORD` w env — [`BackupService.java`](../gnm-app/src/main/java/com/gnm/service/BackupService.java:348) — **M**
- [ ] **5.4** Limit rozmiaru uploadu i czasu restore — **S**
- [ ] **5.5** Wyłączyć `quarkus.naming.enable-jndi` i przepisać probe'y DNS na `InetAddress`/dnsjava — **M**

---

## Etap 6 — Kontenery — **M**

- [ ] **6.1** [`Dockerfile`](../Dockerfile:39): użytkownik non-root, `chmod 700` na `/app/keys`, `HEALTHCHECK`, multi-arch pinning — **S**
- [ ] **6.2** [`docker-compose.prod.yml`](../docker-compose.prod.yml:1): usunąć publikację `5432` i `6379`, `no-new-privileges`, `read_only` + tmpfs, hasło Redis, `cap_add` ograniczone — **M**
- [ ] **6.3** Obrazy pinowane po digest zamiast `latest` — **S**
- [ ] **6.4** Sekrety przez plik/Docker secrets zamiast env, gdzie możliwe — **M**

---

## Etap 7 — Siatka jakości: bramki — **M**

- [ ] **7.1** `ignoreFailures = true` → `false` w [`build.gradle.kts`](../gnm-app/build.gradle.kts:77); najpierw oznaczyć znane czerwone testy jako `@Disabled` z ticketem, potem naprawiać po kolei — **S**
- [ ] **7.2** CI: usunąć `continue-on-error` z testów, zostawić tylko na Sonarze do czasu przejścia quality gate — **S**
- [ ] **7.3** Sonar: dodać `sonar.java.binaries`, `sonar.sources` z backendem, `sonar.qualitygate.wait=true` — **S**
- [ ] **7.4** Nowy workflow: gitleaks, Trivy (obraz), `npm audit --audit-level=high`, CodeQL — **M**
- [ ] **7.5** Dependabot/Renovate dla Gradle i npm — **S**

---

## Etap 8 — Statyczna analiza i testy — **M**

- [ ] **8.1** PMD + Checkstyle z realnym rulesetem (dziś `AGENTS.md` deklaruje PMD, którego nie ma) — **M**
- [ ] **8.2** Spotless: `googleJavaFormat` zamiast samego `removeUnusedImports` — **S**
- [ ] **8.3** Frontend: `no-explicit-any` → `error`, prettier, `eslint-plugin-jsx-a11y`, vitest — **M**
- [ ] **8.4** ArchUnit: granice modułów, każdy zasób ma adnotację bezpieczeństwa, brak `@PermitAll` poza listą dozwolonych — **M**
- [ ] **8.5** PIT na `VaultEngine` i `PasswordService`, jqwik na `SubnetFilter` i `SimilarityEngine` — **M**

---

## Etap 9 — Wydajność i schemat — **M**

- [ ] **9.1** Rozbić `join fetch` w [`getAllDevices()`](../gnm-app/src/main/java/com/gnm/resource/DeviceResource.java:50) na osobne zapytania lub `@BatchSize` — **S**
- [ ] **9.2** Paginacja `GET /api/devices` (`?page=&size=`, limit 200) — **M**
- [ ] **9.3** Telemetria: wymagany zakres czasu + limit punktów, brak pełnej historii — [`TelemetryResource.java`](../gnm-app/src/main/java/com/gnm/resource/TelemetryResource.java:18) — **S**
- [ ] **9.4** Migracja V24 z indeksami: `credential(physical_device_id)`, `network_identity(mac_address)`, `threat_event(resolved, detected_at)`, `network_service(physical_device_id)` — **S**
- [ ] **9.5** `ThreatResource.getThreats()` — usunąć mutację bazy z GET — **S**

---

## Etap 10 — Frontend hardening — **M**

- [ ] **10.1** Error boundary na poziomie route'a — **S**
- [ ] **10.2** Wyłączyć OSC 52 w xterm (blokada eksfiltracji clipboardu przez zdalną powłokę) — **S**
- [ ] **10.3** Walidacja odpowiedzi API (zod) na granicy — **M**
- [ ] **10.4** CSP, `X-Content-Type-Options`, `Referrer-Policy` — **S**

---

## Etap 11 — Obserwowalność — **M**

- [ ] **11.1** `quarkus-smallrye-health`: readiness (baza, Redis) i liveness — **S**
- [ ] **11.2** Micrometer/Prometheus + metryki kolejki sightingów i czasu probe'ów — **M**
- [ ] **11.3** Logi strukturalne JSON, korelacja `requestId`, zakaz logowania sekretów jako reguła testowana — **M**

---

## Etap 12 — Higiena pracy z agentem — **S**

- [ ] **12.1** Usunąć martwy kod: [`MockDataLoader`](../gnm-app/src/main/java/com/gnm/service/MockDataLoader.java:10) (fałszywy log), pozostałości JNDI — **S**
- [ ] **12.2** OpenAPI + generowany klient TS zamiast ręcznych `fetch` — **M**
- [ ] **12.3** Uzupełnić `AGENTS.md` o Definition of Done i inwarianty — **S**

---

## Zależności między krokami

```mermaid
graph LR
    S01[0.1 invariants] --> S11[1.1 bilety]
    S11 --> S12[1.2 SSE update]
    S11 --> S13[1.3 terminal WS]
    S11 --> S14[1.4 events WS]
    S12 --> S18[1.8 frontend]
    S13 --> S18
    S14 --> S18
    S15[1.5 vault role] --> S16[1.6 throttle]
    S17[1.7 JWT verify] --> S18
    S18 --> S2[Etap 2 role]
    S2 --> S3[Etap 3 DTO]
    S3 --> S4[Etap 4 sekrety]
    S71[7.1 ignoreFailures] --> S72[7.2 CI gates]
```

Uwaga: krok **1.8** zależy od 1.1-1.4. Nie wykonywać 1.2-1.4 bez 1.1, bo frontend przestanie działać.
