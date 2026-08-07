# ADR 0017 — Adding a fourth language (an Indian language) to Aster CNL

Status: **PHASE 2 (scope 2a) DONE — Hindi usable in both engines, parse-parity proven (PRs core#20 / ts#21). No new repo, no release.**
Date: 2026-06-16

## Phase 2 result (2026-06-16) — Hindi (hi-IN) usable end-to-end, scope 2a

Scope **2a (light)**: make Hindi usable in both engines + parse-parity, **without**
a separate `aster-lang-hi` language-pack repo and **without** a release.

- **`hi-IN` ships as a core builtin** (like `en-US`, not via SPI like zh/de). It
  flows through `exportLexicons` → `generate-lexicons.ts`, so the TS `hi-IN.ts` is
  `@generated` from core's single source of truth.
  - **core PR #20**: `builtin/hi-IN.json` (78 Devanagari keywords, danda `।`
    statement-end, ENGLISH whitespace); `LexiconRegistry` registers it best-effort
    next to the required `en-US` (refactored to `registerEmbedded(path, required)`).
  - **ts PR #21**: `hi-IN.ts` (@generated) + `index.ts` exports/registers `HI_IN`
    in `initializeAllBundledLexicons` (opt-in, same as zh/de).
- **No new transformer needed** (ADR premise was wrong): Hindi equality/comparison
  use the already-implemented `EQUALS_TO` (`बराबर`) + `से अधिक`/`से कम`
  (greater/less than) keywords — **not** the bare `है` (is) semantics. The Phase-1
  worry about an `is`-comparator transformer does not block 2a.
- **Parse-parity proven** by paired tests: `HindiCompileTest` (Java) and
  `hi-IN.test.ts` (TS) compile the **byte-identical** 3 Hindi policies
  (pricing/loan/calc) to Core IR — both engines agree on module name, decl kinds,
  function names. Full suites green: **core 1160 / ts 1099, 0 fail**.

**⚠️ Real bug found in Phase 2 (the ANTLR path, which Phase-0 POC never exercised).**
Phase 1 fixed the hand-written `Lexer.lex` path (the POC's path). But the
**production ANTLR/Canonicalizer path** normalizes punctuation via a hardcoded CJK
`switch` that **only ran when `whitespaceMode == CHINESE`**. Devanagari uses
ENGLISH mode → that step was skipped → danda survived into the parser → `missing
'.'`. Fix: a `Canonicalizer` step that maps a lexicon-configured non-ASCII
single-char statement-end (danda U+0964) → ASCII `.`, gated to **non-CHINESE +
non-ASCII** so it never overlaps the CJK path and is a **no-op for en/de**. Lesson:
**the POC's compile path is not the production compile path** — verify the ANTLR
pipeline, not just `Lexer.lex`.

**Out of 2a (deferred):** full language pack (`aster-lang-hi` repo with overlays +
vocabularies), aster-cloud surface (locale routing, language switcher, demo data),
corpus-level dual-engine samples wired into the **PR-blocking parity manifest**
(the manifest + `parity-tier1.mjs` + Java `TsSampleParseInventoryTest` are
structurally en-US-only — threading a per-sample locale is its own infra task).

---

## Phase 1 result (2026-06-16) — dual-engine Devanagari lexer

The two POC-identified lexer fixes are now permanent in both engines, parity-locked:
- **aster-lang-core (Java) PR #19**: `Lexer.isIdentifierChar` accepts Devanagari
  marks via a **precise range** `isDevanagariMark` (0x0900–0x097F, excluding danda
  U+0964/U+0965); statement-end/colon/comma branches now also honor the lexicon's
  configured `punctuation.statementEnd`/`blockStart`/`listSeparator`/`enumSeparator`
  (new `isPunct` helper) so danda `।` tokenizes as DOT. `DevanagariLexerTest` (4).
- **aster-lang-ts PR #20**: `lexer.ts isLetter()` english branch adds the same
  Devanagari range (excluding danda). `devanagari.test.ts` (4).
- **Both PRs: build/test + "Strict tier1 parity (TS ↔ Java)" GREEN.** Full Java
  suite **1149 ran / 0 fail**.

**⚠️ Key lesson — do NOT use `Character.isUnicodeIdentifierPart`.** My first Java
attempt used it (it accepts abugida marks AND excludes danda — looked perfect).
But it ALSO returns true for BOM (U+FEFF), ZWNJ/ZWJ, and format chars, so existing
test inputs with zero-width/format chars got swallowed into identifiers → lexer
went into runaway allocation → **test JVM OOM ("Java heap space")**. CI build
failed in 14s; `git stash` + checkout-main bisection proved my change caused it.
Fixed by switching to a precise Devanagari range — which ALSO matches the TS engine
(range-based), so the two engines now use identical logic. Precise > broad here.

Out of Phase 1 (Phase 2): `है` (is) comparison transformer; full Hindi language
pack (`aster-lang-hi` repo) + corpus-based dual-engine IR parity samples.

---

## Phase 0 result (2026-06-16) — Hindi (Devanagari) feasibility

## Phase 0 POC result (2026-06-16) — Hindi (Devanagari), aster-lang-ts only

Hand-wrote a throwaway `HI_IN_POC` lexicon (78 keywords, danda `।` statement-end,
`whitespaceMode: 'english'`) and ran 3 Hindi CNL examples through
`compile`/`tokenize`/`validateSyntaxWithSpan`. **Result: 3/3 compile to Core IR**
(Module/Rule/Define/struct/arithmetic/comparators). **Devanagari as a 4th locale
is technically viable.** The POC found and pinpointed exactly two `aster-lang-core`-
side lexer changes needed (both small, located in `aster-lang-ts/src/frontend/lexer.ts`
`isLetter()`, with a Java mirror needed in core):

1. **Character class doesn't include Devanagari.** `isLetter()`'s `english` branch
   accepts only ASCII + Latin-Extended (0x00C0–0x017F) then `return false`; the
   `\p{L}` Unicode fallback (line ~87) is only reached in `mixed` mode. Devanagari
   (0x0900–0x097F) hit `english` mode → "Unexpected character 'म'". **Fix: add
   Devanagari range to the `english` branch** (or route non-ASCII to `\p{L}`).
2. **Danda is in the Devanagari block.** U+0964 (danda `।`) and U+0965 (double-danda)
   live inside 0x0900–0x097F. Naively whitelisting the whole block swallowed the
   statement-end danda into the preceding identifier (`pricing।` → one IDENT).
   **Fix: exclude 0x0964/0x0965** from the letter range (they're punctuation).
   Good news: lexer.ts:335 already tokenizes `lexicon.punctuation.statementEnd`
   as DOT, so once danda is not a letter, the danda statement-end works.

**Out of Phase 0 (deferred to Phase 1, as predicted):** `है` (is) comparison
needs a Hindi-specific `is-comparator` transformer (SOV/word-order risk from the
risk table); POC sidestepped it by using `से अधिक` (greater than). This confirms
the ADR's "SOV → language-specific transformers" prediction.

**The POC modified production lexer.ts only temporarily; it was reverted.** The
real lexer fix is Phase 1 work (Java core mirror + TS, with tests + dual-engine
parity). POC scripts live in `aster-lang-ts/scripts/poc-hindi/` (untracked).

---

## Original feasibility analysis (pre-POC)

Opened during an autonomous "explore adding an Indian language as the fourth
locale" session. This ADR records the feasibility analysis: the work surface
across the ecosystem, the engine extension points (good news: the core is
designed for this), the Indian-language-specific technical risks (non-Latin
scripts, SOV word order), a per-language difficulty matrix, and a phased plan.
**No code was written.** The single biggest open decision — *which* Indian
language — is a product call that determines script, word order, and effort,
and cannot be derived from the codebase.

## Context

Aster CNL today ships three first-party locales: `EN_US` (English), `ZH_CN`
(Simplified Chinese), `DE_DE` (German). Each is a keyword lexicon + canonical-
ization config + domain vocabularies + diagnostic/LSP overlays, packaged as a
JVM library repo (`aster-lang-{en,zh,de}`) and mirrored as a TypeScript lexicon
in `aster-lang-ts`. Policies authored in any locale compile to the same
deterministic Core IR; the dual-engine parity gate proves the Java and TS
front-ends agree.

"India" is not one language. The choice (Hindi / Tamil / Bengali / Indian
English / …) drives script (Devanagari vs Tamil vs Latin), word order, and
therefore both feasibility and effort. This ADR is script/language-agnostic
where possible and flags where the choice matters.

## Feasibility: the engine is built for this

The core engine's locale extension points are **configuration-driven and
pluggable** — adding a locale should NOT require editing core hard-code,
except possibly the segmenter for an unusual script.

| Extension point | Current design | Impact of a 4th locale |
| --- | --- | --- |
| **Lexicon registration** | `LexiconRegistry.register()` (TS) is opt-in; zh/de are NOT auto-registered, consumers register on demand. Java side loads from language-pack JARs. | New `xx-XX` lexicon (~77 keywords + canonicalization block) + register. Clean. |
| **Syntax transformers** | `TransformerRegistry` (aster-lang-core) is a `ConcurrentHashMap` keyed by name; built-in English transformers register at class-load, **language packs register their own via `registerAll`/`registerAllWithOwner`** (owner classloader → unloadable). zh ships `chinese-*` transformers this way. | A 4th locale's language-specific transformers register from its language pack. **No core edit** for normal cases. |
| **Whitespace / segmentation** | `CanonicalizationConfig.WhitespaceMode` has only `ENGLISH` and `CHINESE`. zh uses `CHINESE` (no inter-word spaces). | Most Indian scripts (Devanagari, Tamil, Bengali) **do use inter-word spaces** → likely reuse `ENGLISH`. ⚠️ Must verify `StringSegmenter` / identifier recognition does not mis-handle abugida vowel-sign + conjunct sequences. Worst case: add a 3rd whitespace mode. |

Lexicon anatomy (from `zh-CN.json`): `meta` + **`keywords` (77 entries)** +
`punctuation` (6) + `canonicalization` (transformer pipeline + allowedDuplicates
+ compoundPatterns) + `messages`. Plus per-pack `overlays/*` (diagnostic-help,
diagnostic-messages, lsp-ui-texts, type-inference-rules, input-generation-rules)
and `vocabularies/*` (domain term packs).

## Work surface (dual-engine + full-stack, ~7 repos)

1. **aster-lang-ts** — new `src/config/lexicons/xx-XX.ts` (77 keywords +
   canonicalization config) + register in `lexicons/index.ts` + export const.
2. **aster-lang-core (Java)** — lexicon JSON + **possibly new `SyntaxTransformer`
   classes** for language-specific syntax (word order, postpositions, script
   punctuation), registered from the language pack.
3. **New `aster-lang-xx` repo** — Gradle JVM library, mirror of `aster-lang-zh`:
   `lexicons/xx-XX.json` + `overlays/*` + `vocabularies/*` + build wiring
   (consume `aster-lang-platform` version catalog per ADR 0012).
4. **aster-lang-locales** — add `locales/xx/` directory (ADR 0011 consolidation
   target; en-US byte-parity invariant must be respected if mirroring).
5. **aster-cloud** (~23 files touch the 3-locale enum, mostly mechanical):
   - **Core ~4**: `lib/aster-lexicon.ts` (display copy), `hooks/useAsterCompiler.ts`,
     `hooks/useAsterLSP.ts`, `lib/monaco-aster.ts` (editor lexicon registration).
   - **Routing/UI**: `[locale]` routes, `components/language-switcher.tsx`.
   - **Demos** (cat-mood, credit-risk-demo, vocab-demo, whitepaper, cnl-demo-
     snippets): three-language demo data — NOT required to add the 4th up front;
     can lag (demo stays tri-lingual, backfill for full experience later).
   - i18n message catalogs (`messages/*.json`) for UI strings.
6. **aster-lang-test (parity/corpus)** — add 4th-locale samples to the dual-engine
   parity gate (parse PR-blocking + ir/eval report-only) so Java↔TS agreement is
   proven for the new locale.
7. **aster-api** — lexicon registration (~2 files).

## Indian-language-specific technical risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| **Non-Latin abugida scripts** (Devanagari, Tamil, Bengali) — consonant + vowel-sign + conjunct (`conjunct`/ligature) sequences | `StringSegmenter` / identifier tokenizer must not split a grapheme cluster or mis-classify a vowel sign as punctuation | POC: lex/parse/compile a sample; test vowel-sign + conjunct identifiers |
| **SOV word order + postpositions** | English/German are SVO; the canonicalization transformer pipeline may need MORE reordering than zh (which kept SVO-ish surface) | Likely new `xx-*` transformers; budget > zh's transformer effort |
| **Devanagari punctuation** `।` (danda), `॥` (double danda) | `punctuation.statementEnd` differs from `.`/`。`; possible new transformer | Lexicon `punctuation` config + transformer if statement-end logic differs |
| **77 keyword translation** must be idiomatic, unambiguous, and not collide with identifiers or common words | Prior canonicalizer footguns: single-letter article-stripping swallowing identifiers (`given a` → `given`), `and`/`or` colliding with module-name substrings | Native-speaker translation + corpus tests; avoid keywords that are common standalone words |

## Per-language difficulty (the key product decision)

| Option | Script | Engine difficulty | Reach / value |
| --- | --- | --- | --- |
| **Indian English** | Latin (reuse `EN_US` tokenizer) | **Lowest** — ~zero lexer change; add Indianisms (`crore`/`lakh` number units) | "4th locale" is weak (it's an EN variant) |
| **Hindi (Devanagari)** | Non-Latin abugida | **Medium-high** | **Highest** — ~500M speakers, official language |
| **Tamil / Bengali** | Own scripts | Medium-high (similar to Hindi) | Regional; narrower than Hindi |

## Phased plan (when a language is chosen)

- **Phase 0 — POC (single repo, aster-lang-ts):** translate 77 keywords +
  canonicalization for the chosen language; use existing `compileAndTypecheck`
  to verify the script lexes/parses/compiles a representative example. Proves
  the #1 risk (non-Latin script) before touching the other 6 repos. ~1 repo,
  reversible, high signal.
- **Phase 1 — Java engine + parity:** lexicon JSON + transformers in
  aster-lang-core; add dual-engine parity samples; confirm Java↔TS agree. This
  is the dual-engine gate — both front-ends must produce identical IR.
- **Phase 2 — language pack + locales:** new `aster-lang-xx` repo (overlays,
  vocabularies) + `aster-lang-locales/locales/xx/`; publish to mavenLocal/GH
  Packages following the v1.0.0 release order (platform → … → core → ts).
- **Phase 3 — aster-cloud surface:** lexicon registration + editor + locale
  route + language switcher; demos and full i18n message backfill can follow.

## What this ADR does NOT decide

- **Which Indian language.** Product decision; drives script/word-order/effort.
- **Whether to do it at all.** This is a feasibility record, not a commitment.
- **Translation sourcing** (native speaker vs LLM-assisted + review).

## Consequences

- The engine's registry/plugin design means a 4th locale is **additive** — it
  does not change EN/ZH/DE behavior (zh/de already prove the multi-locale
  contract). Low blast radius on existing locales.
- A non-Latin script is the one place that could require a **core** change (a
  3rd `WhitespaceMode` or segmenter tweak); the Phase-0 POC de-risks this
  cheaply before the full-stack investment.
- Effort is **XL** (7 repos, dual-engine, parity gate, native translation), but
  cleanly **phaseable** — Phase 0 alone answers "is this language viable?".

## Rejected alternatives

- **Indian English as the "4th language."** Lowest effort (Latin script, reuse
  EN_US) but conceptually weak — it's an English variant, not a genuinely new
  locale; adds little to the "Policy in plain English/中文/Deutsch/…" story.
- **Skipping the dual-engine parity gate for the new locale.** Faster, but
  breaks the project's core invariant that Java and TS front-ends agree;
  divergence would surface as silent miscompiles in production. Non-negotiable.
- **Hard-coding the new locale into core** instead of using the language-pack
  registry. Contradicts the pluggable design (zh/de register from packs); would
  couple core to every future locale.

## See also

- ADR 0011 (locale-pack consolidation into aster-lang-locales)
- ADR 0012 (shared version catalog — language-pack build wiring)
- ADR 0014 (domain vocabulary fusion — `vocabularies/*` per locale)
