# Plan implementacji modułu zarządzania pakietami i podatnościami (TDD · Core + Go Worker)

**Dokument architektury i zatwierdzone ADR-0..9:** [`docs/package-management-architecture.md`](../docs/package-management-architecture.md)  
**Zasady pracy:** [`.agents/invariants.md`](../.agents/invariants.md) · [`plans/_brief-template.md`](_brief-template.md)

Wykonywany małymi krokami (jedna sesja agenta = jeden krok). **Każdy krok jest realizowany ściśle w TDD (Red → Green → Refactor).**

Legenda rozmiaru (liczba plików i ryzyko):
- **S** — 1–2 pliki, zmiana lokalna, niskie ryzyko
- **M** — 3–5 plików, wymaga testów
- **L** — więcej niż 5 plików lub zmiana kontraktu API + frontend

---

## Zasady TDD i piramida testów (obowiązujące w każdym kroku)

1. **Red (Najpierw testy):** zanim powstanie kod produkcyjny kroku, piszesz testy jednostkowe / integracyjne / bezpieczeństwa wymienione w sekcji **„Testy (TDD — napisz najpierw)”** i uruchamiasz je, potwierdzając, że nie przechodzą z właściwego powodu.
2. **Green (Minimalna implementacja):** piszesz wyłącznie kod potrzebny do przejścia testów oraz spełnienia kryteriów kroku.
3. **Refactor + Verify:** porządkujesz kod, uruchamiasz formatter i pełny zestaw testów kroku:
   * **`gnm-pkg-worker` (Go):** `cd gnm-pkg-worker && go vet ./... && go test -race -cover ./...` (minimum **80% pokrycia** dla pakietów domenowych; lokalny serwer SSH w pamięci z `golang.org/x/crypto/ssh` zamiast mockowania samej logiki transportu).
   * **`gnm-app` (Quarkus / Java 21):** `./gradlew spotlessApply && ./gradlew :gnm-app:compileJava && ./gradlew :gnm-app:test --tests "*<NazwaTest>"` (uwaga na Inwariant #21: `ignoreFailures = true` w `build.gradle.kts` — zawsze sprawdzaj realny wynik testów w logu, nie tylko kod wyjścia Gradle!).
   * **Bezpieczeństwo RBAC:** każdy nowy zasób w `com.gnm.resource` musi mieć test macierzy uprawnień (brak tokenu → `401`, `gnm-viewer`, `gnm-operator`, `gnm-admin`).
   * **`frontend`:** `cd frontend && npx tsc -b && npx eslint <zmienione pliki>` + testy E2E Playwright (`frontend/tests/package-management.spec.ts`) dla kluczowych ścieżek użytkownika.
   * **Kontrakt:** `PkgWorkerContractTest` (czarna skrzynka przeciw prawdziwemu kontenerowi Go + `ubuntu_ne`, `centos_ne`, `alpine_ne` z `docker-compose.yml`).

### Zależności (Inwariant #15)
* **`gnm-app` (Quarkus):** **ZERO nowych zależności** w `gnm-app/build.gradle.kts` (używamy JDK 21 `java.net.http.HttpClient`, istniejącego `quarkus-redis-client`, `quarkus-scheduler`, `quarkus-flyway`, `Testcontainers`, `RestAssured`, `JUnit 5`, `Mockito`).
* **`frontend`:** **ZERO nowych zależności** w `frontend/package.json`.
* **`gnm-pkg-worker` (Go 1.23+, ADR-0):** nowy moduł `gnm-pkg-worker/go.mod` z jedną zewnętrzną zależnością produkcyjną: `golang.org/x/crypto` (dla `ssh`, `bcrypt`/`hmac` z stdlib). Reszta z Go stdlib (`net/http`, `encoding/json`, `regexp`, `crypto/hmac`, `crypto/sha256`).
* **Etap 6 (opcjonalny, ADR-1):** gotowy kontener `aquasec/trivy` jako opcjonalny sidecar (bez binarek w obrazie `gnm-app`).

---

## Etap 0 — Kontrakt `v1` i szkielet bezstanowego kontenera Go

Cel etapu: zamrożenie kontraktu OpenAPI `v1` oraz działający, przetestowany kontener `gnm-pkg-worker` w Go z uwierzytelnianiem HMAC-SHA256.

### 0.1 [x] Specyfikacja kontraktu `contracts/pkg-worker-v1.openapi.yaml` — **S**
**Cel:** jedno źródło prawdy dla wszystkich 7 endpointów `v1`, schematów NDJSON, nagłówków HMAC, enumu `elevationMode` (`NONE`, `SUDO_NOPASSWD`, `SUDO_PASSWORD` — ADR-3) i katalogu `errorCode`.

**Pliki:**
- nowy `contracts/pkg-worker-v1.openapi.yaml`

**Weryfikacja:** walidacja składni YAML/OpenAPI 3.1 + kompletność schematów dla wszystkich endpointów i błędów.

---

### 0.2 [x] Szkielet `gnm-pkg-worker` (Go 1.23+), middleware HMAC i `/v1/health` — **M**
**Cel:** bezstanowy kontener Go w wewnętrznej sieci Docker (bez ekspozycji portów, bez DB) z rygorystycznym middleware podpisu HMAC-SHA256 (±30 s).

**Pliki:**
- nowy `gnm-pkg-worker/go.mod`, `gnm-pkg-worker/Dockerfile` (multi-stage → `distroless/static-debian12:nonroot`)
- nowy `gnm-pkg-worker/cmd/worker/main.go`
- nowy `gnm-pkg-worker/internal/auth/hmac.go` + `hmac_test.go`
- nowy `gnm-pkg-worker/internal/httpapi/server.go` + `health_test.go`
- zmiana `docker-compose.yml` (serwis `pkg_worker` w sieci wewnętrznej, bez `ports:`, env `GNM_PKG_WORKER_AUTH_SECRET`)

**Testy (TDD — napisz najpierw):**
- `hmac_test.go`: poprawny podpis → `200`; brak podpisu → `401`; zły podpis → `401`; timestamp starszy niż 30 s / z przyszłości >30 s → `401`; zmodyfikowane body przy starym podpisie → `401`; odmowa startu serwera przy pustym/krótkim sekrecie (<32 B).
- `health_test.go`: `GET /v1/health` zwraca `contractVersion: "1.0"`, `implementation: "go"`, listę parserów i `supportedElevationModes: ["NONE", "SUDO_NOPASSWD"]`.

**Weryfikacja:** `cd gnm-pkg-worker && go vet ./... && go test -race -cover ./...`

---

## Etap 1 — Fundament: SSH w Go workerze, Klient w `gnm-app`, Profile OS i Auto-detekcja

Cel etapu: kompletna, przetestowana ścieżka `gnm-app` → `gnm-pkg-worker` → SSH host (TOFU + `PASSWORD` i `SSH_KEY`), baza profili OS (`V24`) i auto-detekcja bez `if/else`.

### 1.1 [ ] Transport SSH w `gnm-pkg-worker`: `/v1/ssh/fingerprint` i `/v1/probe` — **M**
**Cel:** pobranie fingerprintu host key bez poświadczeń, weryfikacja `expectedHostKeySha256` przed auth, obsługa `PASSWORD` i `SSH_KEY` (Ed25519/RSA/ECDSA) z zerowaniem sekretu w RAM oraz generyczny probe POSIX.

**Pliki:**
- nowy `gnm-pkg-worker/internal/sshclient/client.go`, `tofu.go`, `probe.go`
- nowy `gnm-pkg-worker/internal/sshclient/testserver_test.go` (lokalny serwer SSH w pamięci dla TDD!)
- nowy `gnm-pkg-worker/internal/sshclient/client_test.go`, `probe_test.go`
- nowy `gnm-pkg-worker/internal/httpapi/probe_handler.go` + `probe_handler_test.go`

**Testy (TDD — napisz najpierw):**
- `/v1/ssh/fingerprint` zwraca `SHA256:...` bez podawania hasła/klucza.
- Połączenie z niezgodnym `expectedHostKeySha256` przerywa handshake z `HOST_KEY_MISMATCH` + `actualFingerprint` i **nie wysyła** poświadczeń (asercja w mock SSH server, że `PasswordCallback`/`PublicKeyCallback` nie zostały wywołane!).
- Poprawne uwierzytelnienie zarówno `PASSWORD`, jak i `SSH_KEY` (klucz Ed25519 i RSA); wywołanie z pustym `expectedHostKeySha256` zwraca `400`.
- `probe` parsuje `/etc/os-release` (Debian 12, Ubuntu 24.04, Rocky 9, Alpine 3.20, cytowane i niecytowane wartości, fallback bez `/etc/os-release`) oraz opcjonalne `verifyCommands[]`.

**Weryfikacja:** `cd gnm-pkg-worker && go test -race -cover ./...`

---

### 1.2 [ ] `PkgWorkerClient`, `PkgWorkerHmacSigner`, `DeviceLockManager` i `CredentialResolver` w `gnm-app` — **M**
**Cel:** klient HTTP/NDJSON w `gnm-app` z podpisem HMAC-SHA256, twardą bramką TOFU przed odszyfrowaniem z `VaultEngine`, zerowaniem `byte[]`/`char[]`, lockiem per urządzenie oraz deterministycznym doborem poświadczeń wg kategorii (ADR-9).

**Pliki:**
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/client/PkgWorkerHmacSigner.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/client/PkgWorkerClient.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/client/DeviceLockManager.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/client/CredentialResolver.java`
- nowe DTO kontraktu `v1` w `gnm-app/src/main/java/com/gnm/pkgmgmt/client/dto/`
- zmiana `gnm-app/src/main/resources/application.properties` (`gnm.pkg-worker.base-url`, `gnm.pkg-worker.auth-secret`)
- nowe testy: `PkgWorkerHmacSignerTest.java`, `PkgWorkerClientTest.java`, `DeviceLockManagerTest.java`, `CredentialResolverTest.java`

**Testy (TDD — napisz najpierw):**
- `PkgWorkerHmacSignerTest`: zgodność podpisu HMAC-SHA256 ze znanym wektorem testowym (wspólnym z Go `hmac_test.go`!).
- `CredentialResolverTest`:
  - wybiera `PKG_MANAGEMENT` przed `PKG_MANAGEMENT_FALLBACK` i `NetworkService(SSH).credential`;
  - ignoruje poświadczenia z innych kategorii (`WEB_ADMIN`, `API`) oraz typy spoza `{SSH_KEY, PASSWORD}`;
  - gdy brak `PKG_MANAGEMENT` → używa `PKG_MANAGEMENT_FALLBACK` (fallback konfiguracyjny), a gdy brak obu → `NetworkService(SSH).credential`;
  - przy błędzie `AUTH_FAILED` na `PKG_MANAGEMENT` wykonuje **maksymalnie 1** próbę na `PKG_MANAGEMENT_FALLBACK` i oznacza `usedFallback = true`;
  - przy `HOST_KEY_MISMATCH` **nigdy** nie wykonuje fallbacku;
  - brak kandydata → czysty wynik `MISSING_CREDENTIAL`.
- `PkgWorkerClientTest` (lokalny `com.sun.net.httpserver.HttpServer`):
  - gdy `sshHostKeyTrusted == false` → rzuca wyjątek i **nie woła** `VaultEngine.decrypt(...)` (Inwariant #8);
  - gdy `VaultEngine.isUnsealed() == false` → czytelny błąd bez wywołania HTTP;
  - gdy worker zwraca `HOST_KEY_MISMATCH` → tworzy/aktualizuje `ThreatEvent(HIGH)`;
  - weryfikacja poprawnych nagłówków `X-GNM-Timestamp` i `X-GNM-Signature`.
- `DeviceLockManagerTest`: drugie równoległe wywołanie na tym samym `deviceId` jest odrzucane, inne `deviceId` działają równolegle, zwolnienie locka w `finally` po wyjątku.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*PkgWorker*,*DeviceLockManagerTest,*CredentialResolverTest"`

---

### 1.3 [ ] Migracja `V24__os_profiles.sql`, encja `OsProfile`, `credential_category`, `ProfileValidator` i `OsProfileMatcher` — **M**
**Cel:** baza profili OS z 3 profilami systemowymi (`debian-apt`, `rhel-dnf`, `alpine-apk`), wielokategoriowe `Credential` (`credential_category`), walidacja bezpieczeństwa profili oraz auto-detekcja bez `if/else`.

**Pliki:**
- nowy `gnm-app/src/main/resources/db/migration/V24__os_profiles.sql` (w tym tabela `credential_category` z unikalnym indeksem częściowym per `(physical_device_id, category)` dla ról `PKG_MANAGEMENT` i `PKG_MANAGEMENT_FALLBACK`)
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/OsProfile.java`
- nowy `gnm-app/src/main/java/com/gnm/model/enums/CredentialCategory.java`
- zmiana `gnm-app/src/main/java/com/gnm/model/Credential.java` (`categories` + relacja z `credential_category`)
- zmiana `gnm-app/src/main/java/com/gnm/model/PhysicalDevice.java` (`osProfile`, `osProfilePinned`, `osFacts`, `osDetectedAt`)
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/ProfileValidator.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/OsProfileMatcher.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/profile/OsDiscoveryService.java`
- nowe testy: `ProfileValidatorTest.java`, `OsProfileMatcherTest.java`, `OsDiscoveryServiceTest.java`

**Testy (TDD — napisz najpierw):**
- `ProfileValidatorTest`: odrzuca nieznane placeholdery (np. `{{packages}}` w `inventorySpec`), nieprawidłowe/zbyt długie regexy, timeouty poza zakresem, niepoprawny `elevationMode`, pusty `slug`.
- `OsProfileMatcherTest`: dopasowuje fixtures Debian 12, Ubuntu 24.04, Rocky 9, AlmaLinux 9, Alpine 3.20 wg `priority ASC`, zwraca `Optional.empty()` dla nieznanego OS (zero `if/else`).
- `OsDiscoveryServiceTest`: respektuje `osProfilePinned = true` (nie nadpisuje profilu), zapisuje `osFacts` nawet przy `UNSUPPORTED_OS`, weryfikuje uruchomienie migracji `V24` oraz ograniczenie unikalności `PKG_MANAGEMENT` / `PKG_MANAGEMENT_FALLBACK` na Testcontainers PostgreSQL.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*ProfileValidatorTest,*OsProfileMatcherTest,*OsDiscoveryServiceTest"`

---

### 1.4 [ ] REST API `OsProfileResource` oraz kategorie w `CredentialResource` + UI poświadczeń — **L**
**Cel:** kompletne, utwardzone API profili OS (wyłącznie DTO, `@RolesAllowed`, audit log, ADR-7) oraz obsługa wielu kategorii w `CredentialResource` i formularzu poświadczeń w UI (Inwariant #14, ADR-9).

**Pliki:**
- nowe DTO w `gnm-app/src/main/java/com/gnm/pkgmgmt/dto/` (`OsProfileDto`, `OsProfileExportDto` z `schemaVersion: 1`)
- nowy `gnm-app/src/main/java/com/gnm/resource/OsProfileResource.java`
- zmiana `gnm-app/src/main/java/com/gnm/resource/CredentialResource.java` (zapis/odczyt `categories`, walidacja unikalności `PKG_MANAGEMENT` / `PKG_MANAGEMENT_FALLBACK` per urządzenie z opcją atomowego przejęcia roli)
- zmiana UI formularza/listy poświadczeń w `frontend/src/pages/DeviceDetail.tsx` (wybór wielu kategorii + badge'e)
- nowe testy: `OsProfileResourceTest.java`, rozszerzenie `CredentialVaultTest.java`

**Testy (TDD — napisz najpierw):**
- `OsProfileResourceTest`: macierz RBAC (anonim → `401`; `gnm-viewer`/`gnm-operator` → `200` na `GET`/`export`, `403` na mutacje; `gnm-admin` → pełen dostęp); `PUT`/`DELETE` na `is_system=true` → `409`; `/clone`; round-trip `/export` → `/import` (`schemaVersion`).
- `CredentialVaultTest`: zapis poświadczenia z wieloma kategoriami (`["SSH_TERMINAL", "PKG_MANAGEMENT"]`), próba duplikacji `PKG_MANAGEMENT` na drugim poświadczeniu tego samego urządzenia zwraca `409` (lub atomowo przenosi rolę przy fladze `reassignExclusiveCategories=true`), brak wpływu na inne urządzenia.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*OsProfileResourceTest,*CredentialVaultTest"` + `cd frontend && npx tsc -b`

---

## Etap 2 — Krok 1 (Inventory) + Krok 4 (Offline Vulnerability Assessment)

Cel etapu: pełna inwentaryzacja pakietów (read-only na hostach) i analiza CVE po stronie serwera z deduplikacją floty.

### 2.1 [ ] 4 generyczne parsery i endpoint `/v1/inventory` w `gnm-pkg-worker` — **M**
**Cel:** strumieniowe parsowanie wyjść (`DELIMITED`, `KEY_VALUE_BLOCKS`, `JSON_MAPPING`, `REGEX_LINE`), bezpieczne renderowanie `purlTemplate` i obsługa `sourceName`/`sourceVersion`.

**Pliki:**
- nowy `gnm-pkg-worker/internal/parsers/` (`parser.go`, `delimited.go`, `kvblocks.go`, `jsonmap.go`, `regex.go`, `purl.go` + `*_test.go`)
- nowy `gnm-pkg-worker/internal/httpapi/inventory_handler.go` + `inventory_handler_test.go`

**Testy (TDD — napisz najpierw):**
- Table-driven tests na realnych próbkach wyjść:
  - `dpkg-query` (w tym pakiety `deinstall ok config-files` odrzucane filtrem, multi-arch `amd64`/`i386`, pakiety z epoch `1:2.3.4-1deb12u1`, uzupełnianie `sourceName`/`sourceVersion` z fallbackiem na `name`/`version`);
  - `rpm -qa` (usuwanie wiodącego `0:` w epoch);
  - `/lib/apk/db/installed` (bloki wieloliniowe, pole `o:` jako `sourceName`);
  - `JSON_MAPPING` i `REGEX_LINE` (pomijanie nagłówków i linii z błędem bez przerywania skanu).
- Test limitu rozmiaru (wyjście >16 MB kończy się czystym błędem bez OOM) i URL-encodingu w PURL (`+`, `:`, `~`).

**Weryfikacja:** `cd gnm-pkg-worker && go test -race -cover ./...`

---

### 2.2 [ ] Migracja `V25`, `InventoryService` i eksport CycloneDX w `gnm-app` — **L**
**Cel:** zapis pakietów w PostgreSQL + generowanie CycloneDX 1.5 JSON w locie.

**Pliki:**
- nowy `gnm-app/src/main/resources/db/migration/V25__device_packages_and_vulnerabilities.sql`
- nowe encje `DevicePackageScan.java`, `DevicePackage.java`, `VulnerabilityAdvisory.java`, `DeviceVulnerability.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/inventory/InventoryService.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/inventory/CycloneDxExportService.java`
- nowe testy: `InventoryServiceTest.java`, `CycloneDxExportServiceTest.java`

**Testy (TDD — napisz najpierw):**
- `InventoryServiceTest` (Testcontainers PostgreSQL + mock `PkgWorkerClient`):
  - pierwszy skan zapisuje `device_package_scan` + wszystkie pakiety z `purl` i `source_name` oraz emituje `InventoryUpdatedEvent`;
  - drugi skan aktualizuje zmienioną wersję i **usuwa** pakiet odinstalowany z hosta;
  - błąd workera oznacza skan jako `FAILED` bez kasowania poprzedniego poprawnego stanu `device_package`;
  - brak przypisanego profilu automatycznie wyzwala `OsDiscoveryService`.
- `CycloneDxExportServiceTest`: wygenerowany JSON zawiera `bomFormat: "CycloneDX"`, `specVersion: "1.5"`, metadane urządzenia i poprawne `components[].purl`.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*InventoryServiceTest,*CycloneDxExportServiceTest"`

---

### 2.3 [ ] `OsvApiVulnerabilityProvider` i `VulnerabilityAssessmentService` w `gnm-app` — **L**
**Cel:** offline'owa analiza CVE w `gnm-app` z deduplikacją w skali całej floty, obsługą backportów dystrybucyjnych i cache poradników (ADR-1).

**Pliki:**
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/vulnerability/VulnerabilityProvider.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/vulnerability/OsvApiVulnerabilityProvider.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/vulnerability/VulnerabilityAssessmentService.java`
- zmiana `gnm-app/src/main/resources/application.properties` (`gnm.vuln.osv.base-url=https://api.osv.dev`, TTL cache)
- nowe testy: `OsvApiVulnerabilityProviderTest.java`, `VulnerabilityAssessmentServiceTest.java`

**Testy (TDD — napisz najpierw):**
- `OsvApiVulnerabilityProviderTest` (lokalny mock HTTP OSV): chunking powyżej 500 pakietów na osobne zapytania `/v1/querybatch`, pobieranie `/v1/vulns/{id}` tylko dla brakujących/wygasłych w cache, odporność na HTTP `429`/`503` (backoff).
- `VulnerabilityAssessmentServiceTest`:
  - 3 urządzenia z identycznym `(Debian:12, openssl, 3.0.11-1~deb12u1)` generują **dokładnie 1** zapytanie w batchu OSV;
  - dla Debiana wysyłane jest `source_name` (`openssl`), nie binary (`libssl3`);
  - status `ACCEPTED_RISK` i `FALSE_POSITIVE` nie jest nadpisywany przy kolejnym skanie;
  - po aktualizacji pakietu wcześniejsza podatność przechodzi na `FIXED` z `resolved_at != null`;
  - profil z `vulnerability_spec.enabled = false` jest pomijany bez fałszywego błędu;
  - nowe `CRITICAL`/`HIGH` emituje event na `/ws/events`.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*Vulnerability*Test"`

---

### 2.4 [ ] REST API i Frontend dla Inventory + Vulnerabilities — **L**
**Cel:** kompletne API + zakładki `Packages` i `Vulnerabilities` w `DeviceDetail` oraz globalny widok `/vulnerabilities` (Inwariant #14).

**Pliki:**
- nowe DTO w `gnm-app/src/main/java/com/gnm/pkgmgmt/dto/`
- nowy `gnm-app/src/main/java/com/gnm/resource/DevicePackageResource.java`
- nowy `gnm-app/src/main/java/com/gnm/resource/VulnerabilityResource.java`
- nowe testy: `DevicePackageResourceTest.java`, `VulnerabilityResourceTest.java`
- nowe pliki frontendu: `frontend/src/lib/api/packages.ts`, `frontend/src/components/packages/DevicePackagesTab.tsx`, `frontend/src/components/packages/DeviceVulnerabilitiesTab.tsx`, `frontend/src/pages/VulnerabilitiesPage.tsx`
- zmiana `frontend/src/pages/DeviceDetail.tsx`, `frontend/src/App.tsx`, `frontend/src/components/Layout.tsx`

**Testy (TDD — napisz najpierw):**
- Macierz RBAC dla wszystkich endpointów (`401` bez tokenu, `gnm-viewer` ma tylko `GET` i `403` na `POST /scan` i `PATCH /status`, `gnm-operator`/`gnm-admin` mają `202`/`200`).
- Testy paginacji, filtrowania (`q`, `severity`, `status`, `fixAvailable`) oraz pobierania `/sbom` (`Content-Type: application/vnd.cyclonedx+json`).

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*DevicePackageResourceTest,*VulnerabilityResourceTest"` + `cd frontend && npx tsc -b && npx eslint src/components/packages src/pages/VulnerabilitiesPage.tsx`

---

## Etap 3 — Krok 2 (Update Check, `sudo -n` + generator `sudoers.d`, locki)

### 3.1 [ ] Endpoint `/v1/updates/check` w `gnm-pkg-worker` — **M**
**Cel:** dwufazowe sprawdzanie aktualizacji (`refresh` + `check`), retry na locki menedżera pakietów, obsługa niestandardowych kodów wyjścia (`100` dla `dnf`) oraz weryfikacja `elevationMode` (ADR-3).

**Pliki:**
- nowy `gnm-pkg-worker/internal/updates/checker.go` + `checker_test.go`
- nowy `gnm-pkg-worker/internal/httpapi/updates_handler.go` + `updates_handler_test.go`

**Testy (TDD — napisz najpierw):**
- `dnf check-update` z kodem wyjścia `100` → traktowane jako sukces z listą aktualizacji; kod `0` → 0 aktualizacji; kod `1` → błąd.
- Komunikat `Could not get lock` w 1. i 2. próbie, sukces w 3. → retry z backoffem zwraca `OK`; przekroczenie `maxRetries` → `PACKAGE_MANAGER_LOCKED`.
- `sudo -n` zwracające `a password is required` → `SUDO_PERMISSION_REQUIRED` (bez wiszenia).
- `elevationMode: "SUDO_PASSWORD"` w MVP → czytelny błąd `UNSUPPORTED_ELEVATION_MODE`.

**Weryfikacja:** `cd gnm-pkg-worker && go test -race -cover ./...`

---

### 3.2 [ ] Migracja `V26`, `UpdateCheckService`, `SudoersSnippetGenerator` i UI — **L**
**Cel:** zapis dostępnych aktualizacji, korelacja `fix_available_now` z CVE oraz zakładka Updates + generator `/etc/sudoers.d/lanalmanac`.

**Pliki:**
- nowy `gnm-app/src/main/resources/db/migration/V26__device_available_updates.sql`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/updates/DeviceAvailableUpdate.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/updates/UpdateCheckService.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/updates/SudoersSnippetGenerator.java`
- zmiana `gnm-app/src/main/java/com/gnm/resource/DevicePackageResource.java` (`GET /updates`, `POST /updates/check`, `GET /os-profiles/{id}/sudoers`)
- nowy `frontend/src/components/packages/DeviceUpdatesTab.tsx`, zmiana `frontend/src/pages/DeviceDetail.tsx`
- nowe testy: `UpdateCheckServiceTest.java`, `SudoersSnippetGeneratorTest.java`

**Testy (TDD — napisz najpierw):**
- `UpdateCheckServiceTest`: zapis/aktualizacja `device_available_update`, usunięcie już zainstalowanych aktualizacji, automatyczne ustawienie `device_vulnerability.fix_available_now = true` dla dopasowanego pakietu.
- `SudoersSnippetGeneratorTest`: generuje poprawną składnię `Cmnd_Alias` / `NOPASSWD:` z pełnymi ścieżkami, odrzuca nieprawidłowe nazwy użytkowników (`username` zawierający spacje / znaki nowej linii).

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*UpdateCheckServiceTest,*SudoersSnippetGeneratorTest,*DevicePackageResourceTest"` + `cd frontend && npx tsc -b`

---

## Etap 4 — Krok 3 (Patch Job Engine, Live Streaming NDJSON → SSE, wygaszenie `UpdateResource`)

### 4.1 [ ] Endpoint `/v1/patch/execute` (NDJSON stream) i `/cancel` w `gnm-pkg-worker` — **L**
**Cel:** strumieniowe wykonywanie faz patchowania z równoległym odczytem `stdout`/`stderr`, ochroną przeciw Command Injection, detekcją `CONNECTION_LOST` (ADR-4) i obsługą anulowania.

**Pliki:**
- nowy `gnm-pkg-worker/internal/patcher/template.go` + `template_test.go`
- nowy `gnm-pkg-worker/internal/patcher/executor.go` + `executor_test.go`
- nowy `gnm-pkg-worker/internal/httpapi/patch_handler.go` + `patch_handler_test.go`

**Testy (TDD — napisz najpierw):**
- `template_test.go` (w tym negative/security tests): odrzuca `; id`, `$(whoami)`, `` `id` ``, `pkg1 && rm -rf /`, `--allow-unauthenticated`, spacje i znaki nowej linii z kodem `INVALID_PACKAGE_NAME`; poprawnie cytuje legalne nazwy (np. `libstdc++6`, `g++`, `linux-image-6.1.0-21-amd64`).
- `executor_test.go` (lokalny serwer SSH w pamięci):
  - równoległe wypisywanie 1 MB na `stdout` i 1 MB na `stderr` nie powoduje deadlocka i zachowuje monotoniczne `seq`;
  - kolejność ramek NDJSON: `PHASE(REFRESH)` → `LOG` → `PHASE(UPGRADE)` → `PHASE(REBOOT_CHECK)` → `RESULT(rebootRequired: true)`;
  - nagłe zamknięcie połączenia SSH bez `exit-status` → ramka `RESULT(status: "CONNECTION_LOST")`;
  - przekroczenie `idleTimeoutSeconds` i `timeoutSeconds` → `IDLE_TIMEOUT` / `COMMAND_TIMEOUT`;
  - wywołanie `POST /v1/executions/{id}/cancel` kończy aktywne wykonanie ze statusem `CANCELLED`.

**Weryfikacja:** `cd gnm-pkg-worker && go vet ./... && go test -race -cover ./...`

---

### 4.2 [ ] Migracja `V27`, `PatchJobService`, `PatchJobLogBroker` i `PatchJobResource` w `gnm-app` — **L**
**Cel:** trwałe zadania i logi w PostgreSQL, konsumpcja strumienia NDJSON z workera oraz uwierzytelniony SSE z `AuthTicket` i bezstratnym reconnectem (`Last-Event-ID`).

**Pliki:**
- nowy `gnm-app/src/main/resources/db/migration/V27__patch_jobs.sql`
- nowe `PatchJob.java`, `PatchJobLog.java`, `PatchJobLogBroker.java`, `PatchJobService.java`
- zmiana `gnm-app/src/main/java/com/gnm/dto/AuthTicket.java` (`PURPOSE_SSE_PATCH_JOB = "sse-patch-job"`)
- nowy `gnm-app/src/main/java/com/gnm/resource/PatchJobResource.java`
- nowe testy: `PatchJobServiceTest.java`, `PatchJobResourceTest.java`

**Testy (TDD — napisz najpierw):**
- `PatchJobServiceTest`:
  - walidacja `requestedPackages` przeciw injection i nieobecności w `device_available_update`;
  - zajęty `DeviceLockManager` → `409 Conflict`;
  - pełny przepływ NDJSON → zapis chunków do `patch_job_log` (limit 50 000 linii / 10 MB z linią `[Log truncated]`), zapis audytu `credential_id` / `credential_username` / `used_fallback` na `patch_job`, ustawienie `physical_device.reboot_required`, automatyczny trigger Inventory → CVE po `SUCCEEDED`;
  - `CONNECTION_LOST` planuje opóźniony re-check Inventory (ADR-4);
  - czyszczenie starych wpisów `patch_job_log` wg retencji 30 dni.
- `PatchJobResourceTest`:
  - macierz RBAC (`gnm-viewer` → `403` na `POST /patch-jobs` i `/cancel`, `200` na `GET`);
  - `GET /api/patch-jobs/{jobId}/stream` bez biletu lub z biletem na inne `jobId` → `401`;
  - reconnect z `Last-Event-ID` odtwarza brakujące linie z `patch_job_log` bez duplikacji i przechodzi na live stream.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*PatchJob*Test"`

---

### 4.3 [ ] Live Log Viewer w UI + wygaszenie starego `UpdateResource.java` — **L**
**Cel:** obsługa ręcznego uruchamiania aktualizacji z GUI, podgląd logów na żywo z reconnectem i usunięcie zahardkodowanego `UpdateResource.java`.

**Pliki:**
- nowy `frontend/src/components/packages/PatchJobLogModal.tsx`
- zmiana `frontend/src/components/packages/DeviceUpdatesTab.tsx`, `frontend/src/pages/DeviceDetail.tsx`
- usunięcie `gnm-app/src/main/java/com/gnm/resource/UpdateResource.java` (i przeniesienie/aktualizacja testu)
- aktualizacja `.agents/context-map.md`

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*PatchJob*"` + `cd frontend && npx tsc -b && npx eslint src/components/packages src/pages/DeviceDetail.tsx`

---

## Etap 5 — Harmonogramy per urządzenie (GUI), Edytor Profili (Dry-Run) i Testy E2E / Kontraktowe

### 5.1 [ ] Migracja `V28`, `DevicePackageSchedule`, `PackageManagementScheduler` i GUI (ADR-5) — **L**
**Cel:** konfiguracja harmonogramów per urządzenie bezpośrednio z GUI (Inventory, Update Check, opt-in Auto-Patch `SECURITY_ONLY`/`FULL_UPGRADE`) z bezpiecznym wykonaniem i audytem.

**Pliki:**
- nowy `gnm-app/src/main/resources/db/migration/V28__device_package_schedule.sql`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/schedule/DevicePackageSchedule.java`
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/schedule/CronScheduleCalculator.java` (wyliczanie `nextRunAt` z uwzględnieniem `timezone`)
- nowy `gnm-app/src/main/java/com/gnm/pkgmgmt/schedule/PackageManagementScheduler.java` (`@Scheduled(every = "60s")` + `Semaphore` limitujący równoległe sesje SSH)
- zmiana `gnm-app/src/main/java/com/gnm/resource/DevicePackageResource.java` (`GET`/`PUT /api/devices/{id}/package-schedule`)
- nowy `frontend/src/components/packages/DeviceScheduleCard.tsx`, zmiana `frontend/src/components/packages/DeviceUpdatesTab.tsx`
- nowe testy: `CronScheduleCalculatorTest.java`, `PackageManagementSchedulerTest.java`, rozszerzenie `DevicePackageResourceTest.java`

**Testy (TDD — napisz najpierw):**
- `CronScheduleCalculatorTest`: wyliczanie 3 najbliższych dat dla presetów i custom crona, zmiana czasu DST w różnych strefach (`Europe/Warsaw`, `UTC`), odrzucenie błędnego crona / nieznanego `timezone` (`400`).
- `PackageManagementSchedulerTest`:
  - uruchamia tylko zadania z `next_*_run_at <= now()`;
  - pomija urządzenia `OFFLINE`, niezaufane TOFU oraz zamknięty `VaultEngine`;
  - nie uruchamia `auto_patch` gdy `auto_patch_enabled == false`;
  - zapisuje `triggered_by = "schedule:<updatedBy>"` w `patch_job`;
  - przy zajętym locku pomija bez duplikacji i przesuwa `next_*_run_at`;
  - respektuje limit `max-concurrent-hosts`.
- RBAC: `gnm-viewer` dostaje `403` na `PUT /package-schedule`, `gnm-operator`/`gnm-admin` dostają `200`.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*CronScheduleCalculatorTest,*PackageManagementSchedulerTest,*DevicePackageResourceTest"` + `cd frontend && npx tsc -b`

---

### 5.2 [ ] Dry-run (`POST /api/os-profiles/test`) i `OsProfilesSettingsTab.tsx` — **L**
**Cel:** dodawanie/edycja customowych profili OS, import/eksport JSON (`schemaVersion: 1`) oraz podgląd dry-run z poziomu GUI (`gnm-admin`).

**Pliki:**
- zmiana `gnm-app/src/main/java/com/gnm/resource/OsProfileResource.java` (`POST /api/os-profiles/test`)
- nowy `frontend/src/pages/OsProfilesSettingsTab.tsx`, zmiana `frontend/src/pages/Settings.tsx`
- nowy test `gnm-app/src/test/java/com/gnm/resource/OsProfileDryRunTest.java`

**Testy (TDD — napisz najpierw):**
- `OsProfileDryRunTest`: wymaga roli `gnm-admin` (`403` dla `gnm-operator`), nie modyfikuje tabeli `device_package`, zwraca pierwsze 50 sparsowanych pakietów + diagnostykę błędnych linii parsera.

**Weryfikacja:** `./gradlew spotlessApply && ./gradlew :gnm-app:test --tests "*OsProfileDryRunTest"` + `cd frontend && npx tsc -b && npx eslint src/pages/OsProfilesSettingsTab.tsx`

---

### 5.3 [ ] Contract Test Suite (`PkgWorkerContractTest`) przeciw kontenerom labowym — **M**
**Cel:** automatyczna weryfikacja czarnej skrzynki gwarantująca, że `gnm-pkg-worker` (oraz każda jego przyszła podmiana) spełnia w 100% `contracts/pkg-worker-v1.openapi.yaml` na realnych obrazach Debian/Ubuntu, CentOS/Rocky i Alpine.

**Pliki:**
- nowy `gnm-app/src/test/java/com/gnm/pkgmgmt/contract/PkgWorkerContractTest.java` (`@Tag("contract")`)

**Przypadki testowe (uruchamiane przeciw `pkg_worker` + `ubuntu_ne`, `centos_ne`, `alpine_ne`):**
- Odrzucenie niepodpisanego żądania (`401`).
- `/v1/ssh/fingerprint` → pobranie klucza; celowy błędny `expectedHostKeySha256` → `HOST_KEY_MISMATCH`.
- `/v1/probe` + dopasowanie profili `debian-apt`, `rhel-dnf`, `alpine-apk`.
- `/v1/inventory` na wszystkich 3 kontenerach → niepusta lista z poprawnymi `purl` i `sourceName`.
- `/v1/updates/check` → poprawna obsługa kodów wyjścia (`0` / `100`).
- `/v1/patch/execute` → odbiór ramek `PHASE`/`LOG`/`RESULT` + odrzucenie `INVALID_PACKAGE_NAME`.

---

### 5.4 [ ] Testy E2E Playwright (`frontend/tests/package-management.spec.ts`) — **M**
**Cel:** automatyczne testy end-to-end kluczowych ścieżek UI.

**Pliki:**
- nowy `frontend/tests/package-management.spec.ts`

**Scenariusze:**
- Wyświetlenie wykrytego profilu OS, uruchomienie `Scan Inventory` i filtrowanie listy pakietów + pobranie SBOM.
- Podgląd podatności CVE, zmiana statusu na `Accepted Risk` i sprawdzenie widoku `/vulnerabilities`.
- Podgląd dostępnych aktualizacji, otwarcie modalu `sudoers.d`, uruchomienie Patch Job i sprawdzenie live logów SSE.
- Ustawienie harmonogramu per urządzenie (preset + podgląd najbliższych dat uruchomienia) i przeładowanie strony (trwałość ustawień).
- Klonowanie profilu OS i eksport JSON w `Settings → OS Profiles` jako `gnm-admin` (oraz brak uprawnień edycji jako `gnm-operator`).

---

## Etap 6 — Rozszerzenia przyszłe (po MVP)

### 6.1 [ ] *(Opcjonalnie — ADR-1)* Sidecar `aquasec/trivy server` dla sieci air-gapped — **M**
- `TrivyServerVulnerabilityProvider` implementujący `VulnerabilityProvider` (przełącznik `gnm.vuln.provider=osv-api|trivy-server`) + profil `offline-cve` w `docker-compose.yml` + test TDD ze zamockowanym API Trivy.

### 6.2 [ ] *(Przyszłe — ADR-3)* Wsparcie `elevationMode: "SUDO_PASSWORD"` przez `stdin` — **M**
- Rozszerzenie `gnm-pkg-worker` o podawanie `sudoSecret` na `stdin` kanału SSH z `clear(buf)` natychmiast po wysłaniu + konfiguracja opt-in per urządzenie w `gnm-app` (zero zmian łamiących kontrakt `v1`) + pełne testy TDD potwierdzające brak hasła w linii poleceń i logach.

---

## Weryfikacja końcowa przed commitem pełnego etapu
1. `cd gnm-pkg-worker && go vet ./... && go test -race -cover ./...`
2. `./gradlew spotlessApply`
3. `./gradlew detectAllIssues`
4. Contract Test Suite (`PkgWorkerContractTest`) + Playwright (`package-management.spec.ts`)
5. Przed commitem pełnego etapu / release: `./run_tests.sh`
