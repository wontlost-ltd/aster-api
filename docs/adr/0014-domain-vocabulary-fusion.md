# ADR 0014 — Domain Vocabulary × Aster Lang fusion (investigation + plan)

Status: **IMPLEMENTED (A+B+C, dual-engine + parity-locked)** — 2026-06-07.
Opened during the autonomous "breakout CNL" session after shipping the
`is`-connector comparators (ADR 0013 #1b-i); investigated, design-confirmed
(full fusion, `tenantId` + `getWithCustom`), and shipped the same day.

## What shipped (2026-06-07)

All three threads from the END-TO-END CONFIRMATION section below are wired,
unit-tested, and parity-locked:

- **Thread A (aster-lang-ts)**: `tenantId` added to `CanonicalizerOptions` +
  `CompileOptions`; `canonicalize` resolves the domain index via
  `getWithCustom(tenantId, domain, locale)` (custom-first, builtin fallback)
  instead of builtin-only `getIndex`; `compile`/`compileAndTypecheck` thread it.
  Registry gained `unregisterCustom` (mirrors Java). +4 canonicalizer tests
  (incl. a byte-parity test vs the Java baseline). 33/33 green.
- **Thread B (aster-cloud)**: new pure leaf `domain-vocabulary-assemble.ts`
  (node-free, browser-safe; `domain-vocabulary-validation.ts` re-exports it,
  DRY); new `useUserVocabularyRegistration` hook (fetch user terms → assemble →
  `registerCustom(tenantId, vocab)` → re-register on vocab SSE tick);
  `monaco-policy-editor` derives `tenantId` from `useSession`, registers user
  vocab, prefers it for highlight, and passes `tenantId` to `useAsterCompiler`
  → `compileAndTypecheck`. Tarball repacked + revendored. tsc 0 err, vendor
  browser-entry guard green, validation+golden+snapshot tests green.
- **Thread C (aster-api + aster-cloud)**: `SourcePolicyRequest.vocabulary`
  (Map) → `PolicyEvaluationResource.buildVocabularyIndex` (VocabularyLoader +
  IdentifierIndex.build) → `DynamicCnlExecutor.executeWithContext(..., index)`
  → `InProcessCnlParser.parse(..., index)` → `Canonicalizer(lexicon, index)`.
  Cloud `loadVocabularyForExecution(vocabularySnapshotIds)` merges the version's
  frozen snapshot vocab and `secure-executor` passes it to `evaluateSource`.
  `VocabularyExecutionTest` 3/3 (localized-with-vocab→42, without→throws), no
  regression in CalcProbe/CnlSourceLimits.

**Parity:** TS `canonicalize(localizedSrc, {domain,locale,tenantId})` is
byte-identical to the Java execution baseline (`Fahrer→Driver`, `alter→age`),
asserted in both engines' tests.

**Not done (deliberate, proportionate):** the PR-blocking `tier1-parity`
corpus gate was NOT extended to thread per-fixture vocab — its fixtures are
vocab-free and the harness calls `canonicalize(src)` with no options; reshaping
a mature PR-blocking gate (+ JVM Maven corpus artifact republish) is
disproportionate. The feature-specific cross-engine parity is locked by the
mirrored unit tests above instead.

---

## Original investigation (below) — kept for the audit trail

## Why this matters (the pitch)

Domain vocabulary is one of Aster Lang's core differentiators: *"write rules in
YOUR business terms."* A compliance officer types `驾驶员` / `policyholder` /
`Versicherungsnehmer` and the engine understands it as a domain identifier. If
the user's custom vocabulary is NOT actually fed to the compiler at compile/eval
time, the pitch is only half-true (the words highlight but don't translate).

## What already exists (surveyed 2026-06-07 — substantial!)

Both engines + the cloud product already have most of the machinery:

### Engines (both support domain vocab)
- **Java (aster-lang-core)** `aster/core/identifier/`: `DomainVocabulary`,
  `VocabularyRegistry`, `IdentifierIndex`, `VocabularyPlugin`, `VocabularyLoader`,
  `VocabularyExporter`. `Canonicalizer(lexicon, IdentifierIndex)` runs
  `translateIdentifiers` (step 8.5) — domain words → canonical identifiers.
  Index loaded via `VocabularyRegistry.getInstance().getIndex(domains, locale)`.
- **TS (aster-lang-ts)** `src/frontend/canonicalizer.ts` +
  `src/config/lexicons/identifiers/registry.ts`: `canonicalize(input, {domain,
  locale})` → `vocabularyRegistry.getIndex(domain, locale)` →
  `translateIdentifiers`. Registry has BOTH `register(vocab)` (builtin) AND
  **`registerCustom(tenantId, vocab)`** (+ `customVocabularies` map; `getIndex`
  checks custom first). So per-tenant user vocab is a first-class concept in the
  engine.

### aster-cloud (full product layer)
- DB: `domainTerms` (global dedup catalogue), `userDomainTerms` (per-user,
  soft-delete + 90d archive), `userVocabularySnapshots` (publish-time frozen,
  content-hash deduped, ref-counted), `policyVersions.vocabularySnapshotIds`.
- Libs: `domain-vocabulary{,-admin,-snapshot,-job-runner,-retention,-validation,
  -jobs,-events}.ts` + heavy test coverage (golden/integration/bulk/snapshot).
- Editor: `monaco-policy-editor.tsx` takes a `vocabulary` prop → highlights
  domain terms (sky italic) + subscribes to SSE invalidates; passes `domain` to
  `useAsterCompiler` which calls `compileAndTypecheck(source, {lexicon, domain})`
  → into the TS engine's `canonicalize({domain})`.

## THE GAP (confirmed by grep, needs final runtime confirmation)

**aster-cloud never calls `vocabularyRegistry.registerCustom(...)`.** Only
`initBuiltinVocabularies()` + `vocabularyRegistry.get(domain)` (read) appear in
`src` (rg found 0 `registerCustom`, 0 user-vocab `register` calls). So:
- The editor HIGHLIGHTS user terms (via the `vocabulary` prop / a separate match
  path) — looks like it works.
- BUT at compile time, `canonicalize({domain})` → `getIndex(domain)` can only
  hit BUILTIN vocabularies; **the user's DB-defined terms were never injected
  into the engine registry**, so `translateIdentifiers` won't translate them.

→ Likely outcome: user domain words highlight but don't actually compile/
translate as identifiers (unless they happen to match a builtin domain). The
fusion is wired at the highlight layer but **broken at the compile/translate
layer** for user-defined vocab.

**Refinement (confirmed 2026-06-07):** the API outlet IS wired —
`aster-cloud/src/lib/aster-lexicon.ts` already imports + re-exports
`vocabularyRegistry` + `initBuiltinVocabularies` from
`@aster-cloud/aster-lang-ts/lexicons/identifiers/registry` (the registry class
HAS `registerCustom`). So the engine, the registry, the export path, AND the
re-export in aster-cloud all exist — **only the final call `registerCustom(
tenantId, userVocab)` is missing.** The wire is run to the wall; the last plug
is unplugged. Fix is therefore small + focused: call registerCustom where user
vocab loads / on SSE invalidate. (Note: `vocabularyRegistry` is NOT exported
from the `browser.js` bundle — only via the `/lexicons/identifiers/registry`
subpath, which aster-cloud already uses — so no packaging change needed.)

**STILL MUST confirm before fixing** (don't trust grep alone): write a custom
term in the cloud UI, then check whether `compileSource()` actually translates
it. Possible there's a runtime init path (SSR/hydration) I didn't see, or it's
intentionally builtin-only today pending this work.

## Also unconfirmed
- **Java execution side** (aster-api `DynamicCnlExecutor`): grep found no
  vocab/domain/IdentifierIndex — published policies executed server-side may not
  carry the user's `vocabularySnapshotIds` vocab into the Canonicalizer. The
  snapshot is stored on policyVersions but its consumption at execute-source time
  is unverified. (Note: published policies are pre-compiled to Core IR, so vocab
  translation may already be baked in at publish time — needs checking which
  path publish uses.)

## Proposed plan (when greenlit — dual-engine + parity, per ADR 0013 guardrails)
1. **Confirm the gap end-to-end** (cloud UI custom term → compileSource →
   does it translate? Java execute path → does snapshot vocab apply?).
2. If broken at compile layer: on editor mount / vocab-SSE-invalidate, call
   `vocabularyRegistry.registerCustom(tenantId, userVocab)` from the user's
   active DomainTerms (the data already flows to the `vocabulary` prop — reuse
   it), keyed so `getIndex(domain)` resolves it. Invalidate on SSE tick.
3. Java execute side: ensure published policies' `vocabularySnapshotIds` →
   `IdentifierIndex` → Canonicalizer at evaluate-source time (or confirm it's
   baked at publish — then nothing to do).
4. Golden + parity fixtures: a user term (e.g. `驾驶员`→`driver`) compiles+
   evaluates identically on both engines.
5. Update docs/examples to show "rules in your own terms."

## END-TO-END CONFIRMATION (2026-06-07 — runtime, not grep)

Step 1 of the plan is **done**. Both sides confirmed broken at the
compile/translate layer; the gap is wider than "one missing `registerCustom`
call" — it is **three threads**, all in the data-flow path:

### TS engine (proved by runtime probe against `dist/`)
- `vocabularyRegistry.registerCustom('tenant-42', vocab)` succeeds and
  `getWithCustom('tenant-42', id, locale)` returns the entry. ✅
- BUT `getIndex(id, locale)` returns `undefined` — it routes through `get()`
  which reads **only** `vocabularies` (builtin), never `customVocabularies`.
- `canonicalize(src, { lexicon, domain, locale })` (canonicalizer.ts:197-200)
  calls **`getIndex`** (builtin-only) and has **no `tenantId` param at all**.
  Probe: a custom `pilot→Driver` term left `pilot` untranslated in the output.
  → Even after `registerCustom`, the canonicalizer cannot see custom vocab.
  **Thread A: `CanonicalizerOptions`/`CompileOptions` need `tenantId`, and the
  domain lookup must use `getWithCustom(tenantId, domain, locale)`.**
  (`compile`/`compileAndTypecheck` in browser.ts:144-193, 400 also drop it.)

### aster-cloud (grep, 0 hits — consistent with above)
- `registerCustom` / `getWithCustom`: **0 calls** in `src`. Only
  `initBuiltinVocabularies()` + `vocabularyRegistry.get()`. `useAsterCompiler`
  calls `compileAndTypecheck(source, { lexicon, domain })` (no tenant, no
  custom vocab). The `vocabulary` prop feeds **highlighting only**.
  **Thread B: cloud must `registerCustom(tenantId, userVocab)` from the user's
  active DomainTerms (data already flows to the `vocabulary` prop — reuse it)
  on editor mount / vocab-SSE-invalidate, AND pass `tenantId` into compile.**

### Java execute side (aster-api → aster-lang-core) — ALSO broken
- `secure-executor.ts` sends **raw source** to Java
  (`apiClient.evaluateSource(sourceCode, input)` — no domain, no locale, no
  snapshot vocab). It does NOT pre-compile to Core IR, so vocab is NOT
  "baked at publish". The `vocabularySnapshotIds` on `policyVersions` is stored
  but **never consumed at execute time**.
- `InProcessCnlParser.parse(source, locale)` builds `new Canonicalizer(lexicon)`
  — the **single-arg ctor, no `IdentifierIndex`** — so step 8.5
  `translateIdentifiers` never runs server-side.
- Java engine HAS the full mirror: `Canonicalizer(lexicon, IdentifierIndex)`
  (Canonicalizer.java:143), `VocabularyRegistry.registerCustom/getWithCustom/
  getIndex`. Mechanism exists; wiring missing.
  **Thread C: published-policy execute must carry the snapshot vocab →
  `IdentifierIndex` → `Canonicalizer(lexicon, index)`. Requires passing
  domain/locale (+ snapshot vocab) from cloud through `evaluateSource` into
  `DynamicCnlExecutor` → `InProcessCnlParser`.**

**Net:** highlight layer works; compile + execute layers are both vocab-blind
for user-defined terms. Three small, focused wirings (A, B, C) close it.

## Guardrails
Mature subsystem with heavy existing tests — do NOT refactor broadly. Confirm
the precise gap first, make the minimal injection wiring, lock with golden +
parity, Codex cross-review, per-repo commit. Never break the existing snapshot/
retention/publish flows.
