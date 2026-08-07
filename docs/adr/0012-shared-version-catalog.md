# ADR 0012 — Shared version catalog (aster-lang-platform)

Status: **DONE (2026-06-06) — platform published; all 4 real consumers migrated + CI green; template deliberately excluded (documented in-code).**
Date: 2026-06-05 (completed 2026-06-06)

## Context

~20 hardcoded `cloud.aster-lang:aster-lang-*:0.0.1` version literals were
scattered across 5 repos' build files (aster-lang-core, -runtime, -truffle,
-validation, aster-api, plus locales). Bumping the ecosystem version meant
editing every one — error-prone, easy to miss one.

User asked to unify versions to a single source. Chosen approach (after
ruling out per-repo catalogs and a sed bump script): a **published Gradle
version catalog**, `cloud.aster-lang:aster-lang-platform`.

## Decision

`aster-cloud/aster-lang-platform` is a `version-catalog` Gradle project that
publishes a generated `libs.versions.toml` as a Maven artifact. Consumers
import it in `settings.gradle`:

```kotlin
dependencyResolutionManagement {
  versionCatalogs {
    create("asterLibs") { from("cloud.aster-lang:aster-lang-platform:0.1.0") }
  }
}
```

and reference deps by alias (`asterLibs.core`, `asterLibs.bundles.locales`)
instead of hardcoded coordinates.

**Single source of truth**: the `asterLang` version in
`aster-lang-platform/build.gradle.kts` (currently `0.0.1`, matching the
published baseline → zero behavior change on adoption).

### Scope: JVM only

TypeScript packages (aster-lang-ts 0.2.1 etc.) publish to npm on an
independent cadence and are **deliberately excluded**. Binding the two
ecosystems to one version number is a false coupling (a TS typo fix should
not force a JVM version bump).

### Honest limitation

Multi-repo can't do literal "edit one line, everything updates" — each
consumer still pins which platform version it imports (`from("...:0.1.0")`).
This collapses ~20 scattered literals down to **one catalog-import line per
repo**, and centralizes version *semantics*. That's the achievable ceiling
for single-source in a multi-repo architecture (only a monorepo gets true
one-line-updates-all).

## What's done (2026-06-05)

- `aster-lang-platform` repo created + populated. Catalog has core/runtime/
  truffle/validation/test + en/zh/de + a `locales` bundle, all → `asterLang`.
- Published to GitHub Packages via `v0.1.0` tag (release.yml on `v*`).
- **Pilot: aster-lang-truffle** consumes it (settings import + build aliases).
  CI checks out aster-lang-platform + `publishToMavenLocal` alongside other
  deps (no GH Packages round-trip in CI). `./gradlew dependencies` confirms
  core/runtime/en/zh/de resolve via the catalog. CI green (after async-test
  flake reruns — see below).

## Rollout — CORRECTED dependency facts (verified from build files, not memory)

Actual `cloud.aster-lang:aster-lang-*:0.0.1` literal counts per repo:

| repo | aster-lang dep refs | status |
|---|---|---|
| aster-lang-truffle | 5 | ✅ done (pilot, d625b01) |
| aster-lang-locales | 1 (core, in subprojects) | ✅ done (77e5566) |
| aster-lang-core | 7 (test en/zh/de/test + langPacks config) | ✅ done (a4046ff) |
| aster-api | 7 (groovy + composite build, 4 workflows) | ✅ done (c58c033 + 12626a5) |
| aster-lang-runtime | **0** | N/A — only Quarkus/Jakarta deps, nothing to migrate |
| aster-lang-validation | **0** | N/A — nothing to migrate |
| aster-lang-template | 1 (core) | ✅ resolved — deliberately NOT migrated; rationale pinned in its build.gradle.kts (41bea96) |

**Rollout COMPLETE (2026-06-06).** All 4 real consumers migrated + CI green.
aster-lang-template intentionally keeps a hardcoded core version (it's a
fork scaffold; a catalog import would burden external forkers) — that
decision is now documented in-code so nobody "finishes the rollout" by
migrating it. Status: DONE.

(The earlier order guessed runtime/validation had a core dep — wrong. They
have none. Skip them.)

Remaining: only **aster-lang-template** (1 ref, optional — left as a literal
on purpose; it's a standalone fork scaffold where a hardcoded version is
clearer than a catalog import a forker would have to wire up).

### aster-api migration notes (done) — groovy + composite + token gotcha

- groovy `asterLibs.core` accessor works identically to kts. settings.gradle
  (groovy) catalog import: `versionCatalogs { create('asterLibs') { from('...') } }`.
- composite build (includeBuild + dependencySubstitution) coexists with the
  catalog: catalog supplies coordinate+version, substitution matches module
  identity → redirects to sibling project. Verified in dep tree.
- 4 workflows / 5 jobs (ci, deploy, perf, nightly×2) each got platform
  checkout + publishToMavenLocal before the first gradle call.
- **TOKEN GOTCHA**: aster-api workflows use bare `secrets.CROSS_REPO_TOKEN`
  (no fallback) for sibling checkouts. That PAT predates aster-lang-platform
  and isn't scoped to the new repo → empty token → "Input required and not
  supplied" → checkout fast-fails the whole job. Fix: platform checkouts use
  `${{ secrets.CROSS_REPO_TOKEN || github.token }}` (platform is public, so
  github.token reads it). Any FUTURE new shared repo added to these
  workflows needs the same fallback, or the PAT must be re-scoped.

### core migration notes (done, a4046ff) — lessons for aster-api

- core had NO `dependencyResolutionManagement` in settings.gradle.kts; added
  one with mavenLocal+mavenCentral repos so the catalog itself resolves.
  RepositoriesMode default PREFER_PROJECT means the build.gradle.kts
  `repositories{}` still works — no conflict.
- core has 4 workflows (ci/codegen/corpus-regression/release) AND ci.yml has
  2 jobs (build + parity-tier1). EVERY gradle-invoking job needed a "publish
  catalog to Maven Local" step BEFORE the first core gradle call (settings
  evaluation needs the catalog present). Missing even one = that job 401s/fails.
  aster-api will have the same fan-out — audit all its workflows.
- top-level kts scope uses `asterLibs.en`/`.test` accessors directly (unlike
  locales' subprojects{} which needed the VersionCatalogsExtension lookup).

Per-repo recipe: add `asterLibs` catalog import to settings, swap literals
for catalog refs (kts root/module: `asterLibs.core`; kts subprojects block:
`extensions.getByType<VersionCatalogsExtension>().named("asterLibs").findLibrary("core").get()`;
groovy: verify), add aster-lang-platform to the CI checkout +
publishToMavenLocal list, run FULL test/parity suite locally, then push.

## Operational notes

- **CI pattern**: consumers don't pull the catalog from GH Packages in CI;
  they check out aster-lang-platform + `publishToMavenLocal` (same as every
  other aster-lang dep). This avoids a GH-Packages-auth + ordering dependency
  in CI and matches the existing convention. (GH Packages publish via the
  `v*` tag is still needed for any external/non-CI consumer.)
- **Bumping the ecosystem**: change `asterLang` in platform's build.gradle.kts
  → bump platform's own `version` → tag `v*` (publishes) → point each
  consumer's `from("...:X")` at the new version (one line per repo).
- **Flaky async tests** in aster-lang-truffle (DelayedTaskTest,
  WorkflowSchedulerTest, AsyncTaskRegistryTest concurrent/timeout cases)
  intermittently fail under CI runner load; pass locally + on rerun. NOT
  related to the catalog. Separate test-stability follow-up: tag them and
  exclude from the default CI lane, or add retry, like `-PexcludeBenchmarks`.

## Rejected alternatives

- **Per-repo `libs.versions.toml` (no shared artifact)**: each repo gets a
  catalog but they're independent copies → still N sources, just tidier per
  repo. Doesn't meet "single source".
- **sed bump script in aster-deploy**: zero architecture change, works, but
  text-substitution not declarative; doesn't centralize semantics.
- **Unified cross-ecosystem version (JVM + TS one number)**: false coupling,
  rejected (see Scope).
