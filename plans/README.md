# Jak wykonywać ten plan

Plan merytoryczny: [hardening-plan.md](hardening-plan.md).
Baza wiedzy agenta: [`.agents/invariants.md`](../.agents/invariants.md), [`.agents/context-map.md`](../.agents/context-map.md).

## Zasada: jeden krok = jedna sesja

Nie wykonuj całego etapu naraz. Jedna sesja agenta = jeden checkbox z planu.
Powód: przy dużym zakresie agent traci kontekst, powtarza odczyty plików i psuje wcześniejsze zmiany.

## Pętla pracy (dla każdego kroku)

1. **Brief** — wklej agentowi szablon z `plans/_brief-template.md`, uzupełniony o ID kroku.
2. **Implementacja** — tryb Code, tylko pliki z briefu.
3. **Weryfikacja** — dokładnie ta komenda z briefu, nie cała suita.
4. **Przegląd** — `git diff --stat` zamiast ponownego czytania plików.
5. **Domknięcie** — odhacz checkbox w `hardening-plan.md`, `git commit`.

## Dyscyplina tokenowa

| Zamiast | Rób |
|:---|:---|
| `read_file` całego 500-liniowego zasobu | `search_files` po sygnaturze, potem `read_file` z `offset`/`limit` |
| ponowne czytanie pliku po edycji | `git diff <plik>` |
| `./gradlew :gnm-app:test` | `./gradlew :gnm-app:test --tests "*NazwaTest"` |
| `./gradlew detectAllIssues` | pojedynczy `tsc -b` lub `eslint <plik>` |
| [`docs/architecture.md`](../docs/architecture.md) (1778 linii) | [`.agents/context-map.md`](../.agents/context-map.md) |
| opisywanie problemu od zera | odwołanie do ID kroku w planie |

## Czego nie robić w jednym kroku

- nie łącz refaktoru z poprawką bezpieczeństwa
- nie zmieniaj sygnatur publicznych, jeśli krok tego nie wymaga
- nie dodawaj zależności bez wpisu w planie
- nie zmieniaj [`docs/architecture.md`](../docs/architecture.md) "przy okazji"

## Definition of Done (każdy krok)

- [ ] Kompiluje się: `./gradlew :gnm-app:compileJava`
- [ ] Formatowanie: `./gradlew spotlessApply`
- [ ] Testy wskazane w briefie przechodzą
- [ ] `git diff` zawiera wyłącznie pliki z briefu
- [ ] Jeśli zmieniono kontrakt API — frontend zaktualizowany w tym samym kroku
- [ ] Checkbox w planie odhaczony
