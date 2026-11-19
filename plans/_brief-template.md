# Task Brief — Template

Copy, fill out, and paste to the agent. One brief = one step from [hardening-plan.md](hardening-plan.md) or [package-management-plan.md](package-management-plan.md).
Do not paste full source files into the brief — the agent should read them selectively using the context map.

---

## Step: `<ID>` — `<Step Name>`

**Context (read first):**
- `.agents/invariants.md`
- `.agents/context-map.md` — section `<Area Name>`

**Goal in One Sentence:**
`<what must be true after completing this step>`

**Files to Modify (only these):**
- `path/to/File.java` — `<what specifically>`
- `path/to/Component.tsx` — `<what specifically>`

**Files NOT to Touch:**
- `<list — usually neighboring resources or unrelated frontend files>`

**Requirements:**
1. `<specific requirement, not vague>`
2. `<specific requirement>`
3. `<specific requirement>`

**What NOT to Do:**
- Do not change API contracts outside those listed above
- Do not add dependencies
- Do not refactor unrelated code "while you are there"
- Do not update `docs/architecture.md`

**Acceptance Criteria (verifiable):**
- [ ] `<test or behavior verifiable by a command>`
- [ ] `<second criterion>`

**Verification — Run Only This:**
```bash
./gradlew :gnm-app:compileJava
./gradlew spotlessApply
./gradlew :gnm-app:test --tests "*<TestName>"
```

**Agent Final Report (maximum 10 lines):**
- what changed (files + one-line description each)
- verification result
- what was deferred and why

---

## Example Filled Brief

## Step: 1.5 — Vault Without `@PermitAll`

**Context:** `.agents/invariants.md`, `.agents/context-map.md` → Vault section

**Goal:** Unsealing the vault requires an authenticated administrator.

**Files to Modify:**
- `gnm-app/src/main/java/com/gnm/resource/VaultResource.java` — replace `@PermitAll` with `@RolesAllowed("gnm-admin")`, remove `vaultPassword` fallback
- `gnm-app/src/main/resources/application.properties` — remove `/api/vault/*` from `permission.public.paths`

**Files NOT to Touch:** `VaultEngine.java`, `vault-context.tsx`

**Requirements:**
1. Passcode strictly from request body; no configuration fallback.
2. `initialize` and `unseal` return 401 without a token.
3. Do not change the JSON response format.

**Acceptance Criteria:**
- [ ] `POST /api/vault/unseal` without token → 401
- [ ] `VaultResourceTest` passes

**Verification:**
```bash
./gradlew :gnm-app:compileJava
./gradlew spotlessApply
./gradlew :gnm-app:test --tests "*VaultResourceTest"
```
