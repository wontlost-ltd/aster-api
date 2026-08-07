# ADR 0011 — Consolidate first-party locale packs into aster-lang-locales

Status: **In progress — additive seed DONE, cutover still gated.**
Date: 2026-06-04 (last updated 2026-06-05)

> ✅ **Steps 1–2 complete (2026-06-05).** The `aster-cloud/aster-lang-locales`
> repo is seeded with en/zh/de as a Gradle multi-module build. It builds
> + tests + publishes all three packs to Maven Local under their existing
> coordinates (`cloud.aster-lang:aster-lang-{en,zh,de}`, version 0.1.0).
> CI is green against the core sibling. This was the additive, zero-
> breakage half — **nothing that consumes the packs is switched over yet.**
>
> A pre-existing drift surfaced + fixed during the seed: the en pack's
> `en-US.json` had `meta.updatedAt: "2026-05-29"` (commit P2-R31-6) that
> `aster-lang-core`'s builtin backbone copy never received.
> `aster-lang-core` commit 500c3e0 synced it.
>
> ⛔ **Cutover prerequisites still NOT met as of 2026-06-05.** Switching
> `aster-api` / `aster-deploy` / the parity CI to the new repo would break
> the composite build with no safe rollback until:
>
> 1. **`tier1-parity --mode=parse` green on main for a full week**
>    across all three engine repos. (Green for ~1 day as of 2026-06-05.)
> 2. ~~A new repo `aster-cloud/aster-lang-locales` exists~~ ✅ DONE.
> 3. **Coordination with the ArgoCD/Cloudflare operator** to drain
>    in-flight deploys before the cutover release; not a code action.
> 4. **Tier1-parity `--mode=ir` (report-only)** has run on main for a
>    week so any pre-existing IR drifts in the locale-pack lowering
>    are catalogued before the move.
>
> Do not begin the cutover (migration steps 6–11 below) until 1, 3, 4
> hold. The thin-wrapper deprecation of the old repos + the `aster-api`
> dependency swap are the first cutover actions.

## Context

Today, the first-party Aster language packs ship as three separate Gradle
root projects:

- `aster-lang-en` — English lexicon + SPI plugin
- `aster-lang-zh` — Simplified Chinese (zh-CN v2 keywords; see ADR 0008)
- `aster-lang-de` — German

A fourth project, `aster-lang-template`, scaffolds new third-party packs.

The dual-model architecture review (analyzer sessions
`019e920a-a136-7c13-9640-2881deb64885` /
`019e920a-a1ad-7b82-aa3f-c1c8a81778ef`) flagged this split as the lowest-
cohesion repo group in the workspace:

- `aster-lang-en` is no longer "just a locale pack" — `aster-lang-core`
  embeds `builtin/en-US.json`, and a Gradle task `verifyLexiconParity`
  compares the two byte-for-byte. The repo's identity is split between
  "language pack" and "fallback backbone".
- All three repos have nearly-identical `settings.gradle.kts`,
  `build.gradle.kts`, and `META-INF/services/aster.core.lexicon.LexiconPlugin`
  layouts. Editing them in lock-step is a daily papercut.
- `aster-api/settings.gradle` carries three `includeBuild` lines + three
  Maven dependency lines that all change together. The cross-repo
  dependency surface is the same shape each time.
- `aster-deploy/Taskfile.yml` has `build:pack-en`, `build:pack-zh`,
  `build:pack-de` which all do the same thing.

The split was originally motivated by "each language pack can release
independently." In practice no first-party pack has ever shipped a
release independently of the others — they all move with core's grammar
changes.

## Decision (draft)

Merge `aster-lang-en`, `aster-lang-zh`, `aster-lang-de` into a single
Gradle multi-module project `aster-lang-locales`:

```
aster-lang-locales/
├── settings.gradle.kts            # includes :en, :zh, :de
├── build.gradle.kts               # shared plugins, version catalog
├── locales/
│   ├── en/
│   │   ├── build.gradle.kts
│   │   └── src/main/{java,resources}/
│   ├── zh/
│   │   ├── build.gradle.kts
│   │   └── src/main/{java,resources}/
│   └── de/
│       ├── build.gradle.kts
│       └── src/main/{java,resources}/
└── docs/adr/                      # decision history
```

`aster-lang-template` stays as a standalone repo — it's a contributor
scaffold for **third-party** packs, not a first-party artifact, and
folding it into `aster-lang-locales` would couple external authoring to
internal release cadence.

### Preserved invariants (non-negotiable)

These are recorded in `~/.claude/projects/.../memory/MEMORY.md` and a
regression here breaks the unified parser contract:

- `KW.RULE = 'rule'`, `KW.MODULE_IS = 'module'`, `KW.HAS = 'has'`,
  `KW.GIVEN = 'given'`
- `verifyLexiconParity` task remains active in `:en` (still compares to
  `aster-lang-core/src/main/resources/builtin/en-US.json`)
- SPI registration: each module's jar must contain
  `META-INF/services/aster.core.lexicon.LexiconPlugin`
- Published Maven coordinates `cloud.aster-lang:aster-lang-{en,zh,de}`
  remain consumable for at least one deprecation release

## Migration path

1. **Block on Task 1.** This change cannot land until
   `tier1-parity --mode=parse` is PR-blocking and green across all three
   engines. Without that gate, a botched SPI migration could silently
   regress parser surface and not be caught.
2. **Create `aster-lang-locales` repo** with the multi-module layout
   above. Initial population is `git mv` from the three source repos so
   history is preserved (use `git-filter-repo` per-module then merge).
3. **Old repos become thin wrappers** for one release: each old
   `settings.gradle.kts` declares the new module as a Maven dependency
   and re-publishes the same coordinates. Consumers that pin to old
   coordinates get the new content with a deprecation warning.
4. **Update `aster-api`:**
   - `settings.gradle`: replace the three `includeBuild('../aster-lang-en')`
     etc. with one `includeBuild('../aster-lang-locales')`. Add explicit
     `dependencySubstitution` entries mapping each old coordinate to the
     new module path.
   - `build.gradle`: runtime deps swap from old to new coordinates.
5. **Update `aster-lang-core`:** Its test runtime needs the SPI plugins
   on the classpath. Update `build.gradle.kts` to depend on the
   `:en`, `:zh`, `:de` modules (or pull them via Maven Local).
6. **Update `aster-deploy`:** Collapse `build:pack-en|zh|de` into a single
   `build:locales` task that builds all three modules.
7. **Update CI workflows:**
   - `aster-lang-test/.github/workflows/ci.yml` — checkout
     `aster-lang-locales` instead of the three separate repos.
   - `aster-lang-core/.github/workflows/ci.yml` — same.
   - `aster-lang-ts/.github/workflows/ci.yml` (parity-tier1 job from Task 1A.4)
     — same.
8. **Deprecation release of the three old repos**: tag, publish a final
   wrapper artifact, archive the repos with a README pointer.
9. **Cutover release of `aster-lang-locales`** with synchronized version
   `0.1.0`.

## What this ADR does NOT decide yet

- Whether `aster-lang-en` keeps its dual role as both "locale pack" AND
  "fallback backbone" inside `aster-lang-locales`, or whether the
  fallback `en-US.json` migrates fully into `aster-lang-core`. The
  cleaner story is the latter, but it expands the blast radius. Defer
  to a Phase 2 of this ADR after the cutover stabilises.
- Whether the new repo lives under the `aster-cloud` GitHub org or stays
  under whatever org currently hosts the three source repos. (Currently
  `aster-cloud/aster-lang-{en,zh,de}` per the CI workflows.)
- GitHub Packages publishing strategy across the cutover. Needs
  coordination with the maintainer who controls `secrets.GITHUB_TOKEN`
  scopes.

## Consequences

Positive:
- One Gradle invocation builds all three packs.
- Lock-step releases get easier (synchronised version across modules).
- `aster-api/settings.gradle` shrinks from 3 lines to 1.
- Maintenance papercuts (identical `build.gradle.kts` edits) disappear.
- Third-party language pack story stays clear via `aster-lang-template`.

Negative:
- **Breaking change for any consumer that pins old coordinates** — the
  thin-wrapper deprecation release softens this but does not eliminate
  it. Consumers that build from source against `includeBuild` will break
  immediately; consumers that consume via Maven get a deprecation
  cycle.
- Repo split decision is hard to reverse. If we discover one locale
  needs an independent release cadence later, undoing this is more
  expensive than the original split.
- ArgoCD / Cloudflare / external integrations that reference the old
  repos by name (workflow dispatch event targets, e.g.) need updating
  in lock-step.

## Rejected alternatives

- **Hard cutover (no deprecation wrappers).** Faster, but breaks
  consumers on Maven Local immediately. Rejected — we have at least one
  external consumer (`aster-api` CI) that's mid-migration and can't
  absorb a same-day break.
- **Keep the three repos, just share a Gradle convention plugin.** Solves
  the lock-step `build.gradle.kts` problem but not the three-way include
  problem in `aster-api`, and doesn't reduce the conceptual repo count.
  The duplication-reduction is partial; not worth a full migration
  effort for a partial win.
- **Merge `aster-lang-template` in too.** Conflates first-party runtime
  artifacts with a contributor scaffold. Template's audience is
  third-party language pack authors; first-party packs' audience is the
  Aster runtime. Keeping them separate keeps the SPI extension story
  clear.
- **Merge all of `aster-lang-{core,runtime,truffle,validation,locales}`
  into one monorepo.** Tempting from a workspace-tidiness standpoint
  but explodes the blast radius of every PR and erases the browser-safe
  / JVM-only / GraalVM-Truffle module boundaries that exist for good
  technical reasons. Out of scope.

## Operational notes

- After cutover, the dual-engine parity CI (Task 1A.3) needs to keep
  checking out `aster-lang-{en,zh,de}` for one deprecation release, then
  switch to `aster-lang-locales`. This is a coordination point — the
  switch must happen in the same PR series that flips `aster-api` to the
  new coordinates.
- `aster-lang-test`'s corpus repo is unaffected: it consumes lexicons via
  SPI discovery at runtime, not by direct artifact dependency.
- `aster-lang-truffle` may have a runtime-only dependency on locale
  packs (for evaluation tests). Verify before cutover that the SPI
  classpath still resolves all three.
