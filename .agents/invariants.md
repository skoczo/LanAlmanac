# Inwarianty projektu — czytaj to zawsze, przed każdą zmianą

Twarde reguły. Jeśli proponowana zmiana łamie którąkolwiek — zatrzymaj się i zgłoś konflikt,
zamiast obchodzić regułę. Maksymalny rozmiar tego pliku: 100 linii. Nie rozbudowuj go.

## Architektura (nienegocjowalne)

1. Modular monolith na Quarkus. Zero mikroserwisów, zero nowych procesów sieciowych.
2. Java 21. Współbieżność przez wirtualne wątki i `StructuredTaskScope`.
   Zakazane: `new Thread(...)`, `Executors.newCachedThreadPool()`, `newFixedThreadPool`, `ManagedExecutor`.
   Dozwolone: `Executors.newVirtualThreadPerTaskExecutor()`, `Thread.startVirtualThread`.
3. PostgreSQL + TimescaleDB. Schemat wyłącznie przez migracje Flyway — nigdy `schema-management`.
4. Każda encja JPA zwracana przez REST musi iść przez DTO. Ciało żądania nigdy nie jest encją.
5. Cały kod, logi, komunikaty błędów i UI po angielsku.

## Bezpieczeństwo (nienegocjowalne)

6. Nowy endpoint REST startuje jako `deny`. Dodanie `@PermitAll` wymaga wpisu w planie i uzasadnienia.
7. Każdy zasób w `com.gnm.resource` ma jawną adnotację `@RolesAllowed` lub `@Authenticated`.
   Brak adnotacji = błąd, nie "domyślnie authenticated".
8. `sshHostKeyTrusted` ustawia wyłącznie dedykowany endpoint zaufania. Nigdy przez mass assignment.
9. Sekrety nie trafiają do logów, wyjątków, odpowiedzi HTTP ani do repozytorium.
   Logowanie sekretu = błąd krytyczny, nie "drobiazg do poprawy później".
10. Klucze kryptograficzne żyją w `byte[]`/`char[]` i są zerowane. Nigdy `String` na hasło lub klucz.
11. Nowe hasło, token lub klucz: minimum AES-256-GCM / Argon2id z parametrami nie słabszymi niż
    `m=65536, t=3, p=1`. Nie wymyślaj własnej kryptografii.
12. Każde wywołanie zewnętrznego procesu (`ProcessBuilder`) — argumenty jako lista, nigdy `sh -c`.

## Kontrakty (nienegocjowalne)

13. `docs/architecture.md` to dokument opisowy, nie źródło prawdy o kodzie. Nie edytuj "przy okazji".
14. Zmiana kontraktu API = zmiana frontendu w tym samym kroku. Nie zostawiaj niedziałającego UI.
15. Nowa zależność wymaga wpisu w planie. Bez tego — nie dodawaj.

## Higiena pracy

16. Jedna sesja = jeden krok z `plans/hardening-plan.md`. Nie łącz refaktoru z poprawką bezpieczeństwa.
17. Czytaj minimum: `search_files` po sygnaturze, potem `read_file` z `offset`/`limit`.
    Nie czytaj pliku w całości, jeśli potrzebujesz jednej metody.
18. Po edycji weryfikuj przez `git diff`, nie przez ponowne czytanie pliku.
19. Testy uruchamiaj wybiórczo: `--tests "*NazwaTest"`. Pełna suita tylko przed commitem etapu.
20. Zanim oznaczysz krok jako gotowy: `./gradlew :gnm-app:compileJava` i `./gradlew spotlessApply`.

## Znane pułapki tego repo

21. `build.gradle.kts` ma obecnie `ignoreFailures = true` — testy nie blokują builda.
    Nie traktuj "build zielony" jako dowodu poprawności.
22. `application.properties` ma historycznie otwarte ścieżki publiczne (`/api/vault/*`,
    `/ws/terminal/*`, `/api/devices/*/update`). Nie kopiuj tego wzorca do nowych endpointów.
23. Frontend ustawia cookie `jwt` z JavaScriptu. Nie rozszerzaj tego mechanizmu.
24. `MockDataLoader` nie robi nic, ale loguje sukces. Nie ufaj logom startowym.
25. Kolejka `NetworkSightingQueue` jest jedynym wejściem do fingerprintowania — nie omijaj jej.
