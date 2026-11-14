# Brief zadania — szablon

Skopiuj, uzupełnij i wklej agentowi. Jeden brief = jeden krok z [hardening-plan.md](hardening-plan.md).
Nie dołączaj całych plików do briefu — agent ma je przeczytać wybiórczo według mapy kontekstu.

---

## Krok: `<ID>` — `<nazwa kroku>`

**Kontekst (wczytaj najpierw):**
- `.agents/invariants.md`
- `.agents/context-map.md` — sekcja `<nazwa obszaru>`

**Cel w jednym zdaniu:**
`<co ma być prawdą po zakończeniu kroku>`

**Pliki do zmiany (tylko te):**
- `ścieżka/do/pliku.java` — `<co konkretnie>`
- `ścieżka/do/pliku.tsx` — `<co konkretnie>`

**Pliki, których NIE dotykać:**
- `<lista — zwykle sąsiednie zasoby, frontend niepowiązany>`

**Wymagania:**
1. `<konkret, nie ogólnik>`
2. `<konkret>`
3. `<konkret>`

**Czego nie robić:**
- nie zmieniać kontraktu API poza wymienionym wyżej
- nie dodawać zależności
- nie refaktorować "przy okazji"
- nie aktualizować `docs/architecture.md`

**Kryterium akceptacji (sprawdzalne):**
- [ ] `<test lub zachowanie, które da się zweryfikować komendą>`
- [ ] `<drugie kryterium>`

**Weryfikacja — uruchom tylko to:**
```bash
./gradlew :gnm-app:compileJava
./gradlew spotlessApply
./gradlew :gnm-app:test --tests "*<NazwaTest>"
```

**Raport końcowy agenta (maksymalnie 10 linii):**
- co zmieniono (pliki + jedna linia opisu każdy)
- wynik weryfikacji
- co odłożono i dlaczego

---

## Przykład wypełnionego briefu

## Krok: 1.5 — Vault bez `@PermitAll`

**Kontekst:** `.agents/invariants.md`, `.agents/context-map.md` → sekcja Vault

**Cel:** Odpieczętowanie vaulta wymaga zalogowanego administratora.

**Pliki do zmiany:**
- `gnm-app/src/main/java/com/gnm/resource/VaultResource.java` — zamień `@PermitAll` na `@RolesAllowed("gnm-admin")`, usuń fallback `vaultPassword`
- `gnm-app/src/main/resources/application.properties` — usuń `/api/vault/*` z `permission.public.paths`

**Pliki, których NIE dotykać:** `VaultEngine.java`, `vault-context.tsx`

**Wymagania:**
1. Hasło wyłącznie z ciała żądania; brak wartości z konfiguracji.
2. `initialize` i `unseal` zwracają 401 bez tokenu.
3. Nie zmieniaj formatu odpowiedzi JSON.

**Kryterium akceptacji:**
- [ ] `POST /api/vault/unseal` bez tokenu → 401
- [ ] test `VaultResourceTest` przechodzi

**Weryfikacja:**
```bash
./gradlew :gnm-app:compileJava
./gradlew spotlessApply
./gradlew :gnm-app:test --tests "*VaultResourceTest"
```
