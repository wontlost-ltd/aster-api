# Zero-Downtime Incremental Rebuild (ADR 0050) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "rebuild the database" unnecessary for the next toolchain or schema change: migrations and release order are contracts (A), stored policy versions get an explicit per-toolchain validity proof and are refused without one (B), old JWT sessions can be revoked by bumping a database epoch (C), and a structural rebuild has a blue/green runbook (D). No new service; everything lands on existing aster-api / aster-cloud admin surfaces.

**Architecture:** Five repos change. (1) aster-cloud + Cloudflare: single Worker release path (`deploy-worker.yml` waits for the k3s migrate Job when main contains drizzle SQL; Workers Builds auto-deploy switched off), PR split check, `safeColumnRead`, `dbEpoch`, admin revalidation proxy/page, "needs fix" badge. (2) k3s: owner-separation handbook rewritten as "one-time superuser handover + callback self-check", blue/green runbook, Workers Builds evidence. (3) aster-api: Flyway `afterMigrate` owner self-check, migration governance metadata, `typecheckVersion`, validation + run tables, `PolicyRevalidationJob`, `PolicyExecutionGate`, admin endpoints. (4) aster-ai-workspace: `ai_workspace` schema owner `DO` block. (5) cross-repo: cloud↔api `sourceHash` check script, image-pin BEHIND auto-rerun.

**Tech Stack:** Quarkus 3.39 / Java 21 / Flyway / Hibernate Panache / PostgreSQL 16 (aster-api); Next.js 16 on Cloudflare Workers, Auth.js 5 beta (`@auth/core` 0.41.3), drizzle, vitest (aster-cloud); Spring Boot sql-init (aster-ai-workspace); Kustomize + Argo CD + CNPG (k3s); GitHub Actions + `wrangler`.

**Spec:** `docs/adr/0050-zero-downtime-incremental-rebuild.md` (ACCEPTED 2026-10-24, rev 4 + §9 ruling). Read §2.1.5, §2.1.6, §2.2, §2.3, §4, §8 before starting. Codex review reports: `.claude/review-report-adr0050*.md` — their remaining items are the acceptance evidence of Tasks 1–3.

## Global Constraints

- Order of delivery follows ADR §8: release controls and owner prerequisites (Tasks 1–3) **before** any PR that reads a new column (Tasks 5–8).
- aster-cloud: a PR that adds `drizzle/**/*.sql` must not also change `src/**` (except `src/db/schema.ts`, `src/__tests__/**`, `drizzle/meta/**`) unless labelled `migration-and-code` with a `## 迁移窗口容错` section. Tasks below are already split accordingly.
- aster-api runtime semantics: `invalid` → enforce refuses with diagnostics, warn executes with a warning; **no validation row → synchronous `typecheckVersion`, then same rules**; DB read failure → refuse (also in warn). Only `valid` is cached (process-local, keyed `(versionId, toolchainId)`, no TTL).
- Validation rows are immutable per `(version_id, toolchain_id)`; writes use `INSERT ... ON CONFLICT DO NOTHING`.
- `dbEpoch`: read uncached on every `jwt()` call without `user`; mismatch or read failure → `return null` (Auth.js clears the session cookie). Budget: p95 +15 ms, DB QPS ≤ 3× request QPS; measured in Task 12.
- Admin-only: cloud `requireAdmin()`; api `AdminHmacVerifier` (HMAC over method, path, timestamp, nonce, body hash). `X-User-Role` is never an authorization input for these endpoints.
- Code comments in Simplified Chinese describing intent; no "修改说明" comments; functions ≤ 3 indentation levels; no new npm/Maven dependencies.
- Commit trailer exactly `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`; PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`; no model names elsewhere.
- Tests needing Postgres use sdd-pg `localhost:55433` / sdd-redis `localhost:6380` (api) or a throwaway `postgres:16-alpine` on `localhost:55434` (cloud drizzle). Never the live local stack on 55432/6379.
- Long commands run in the background with a log file; `codex exec` needs `< /dev/null`; use absolute paths / `git -C` (parallel shells share cwd).
- HUMAN STEP tasks (Cloudflare console, production superuser SQL, repo variables) are executed by the user; the plan records the evidence they must produce.

## Review Focus

1. A version with an existing `PolicyArtifact`/`coreJson` and a type error (E011/E012) must still be judged `invalid` by `typecheckVersion` and refused by the gate. Pinned in Task 6 (`PolicyCompilerTypecheckVersionTest.artifactPathStillTypechecks`, `PolicyExecutionGateTest.missingRowSyncValidatesAndRefusesInvalid`).
2. Two api replicas starting on a new toolchain create exactly one run; killing a worker mid-batch leaves no duplicate validation rows and the other replica resumes from `cursor`. Pinned in Task 7 (`PolicyRevalidationJobIT`).
3. After `dbEpoch` bump, a request with an old cookie to `/api/auth/session` returns `null` **and** `Set-Cookie` clears the session; DB outage behaves the same. Pinned in Task 9 (`auth-epoch.test.ts` using `NextAuth(config).handlers`).
4. A main push containing `drizzle/*.sql` never runs `wrangler deploy` before the k3s migrate Job succeeds; Job failure/timeout fails the deploy step. Pinned in Task 1 (workflow test with `act`-style dry run + staging run log).
5. A hot-path query hitting SQLSTATE 42703 falls back once with a warning; any other error propagates. Pinned in Task 2 (`safe-column-read.test.ts`).

---

## Part A — Release controls (aster-cloud + Cloudflare)

Worktree: `git -C /Users/rpang/IdeaProjects/aster-cloud worktree add -b feat/release-controls /Users/rpang/IdeaProjects/aster-cloud-adr50 origin/main`.

### Task 1: Single Worker release path — DONE (aster-cloud#541, 2026-10-24)

**Design amendment (recorded in aster-api `.claude/operations-log.md`):** the plan originally called for a new `wait-migrate-job.sh` polling the k3s Job. Implementation found that `ci.yml`'s `image-pin-pr` job already performs the fail-loud wait (image-pin PR merged → ArgoCD sync → synced revision carries this run's migrate digest and is Healthy, #187). To keep one source of truth, `deploy-worker.yml` now triggers on `workflow_run` of `CI` (completed, main) and only deploys when the CI conclusion is `success`, checking out `workflow_run.head_sha`; no new polling script.

**Files (as merged):**
- Modified: `.github/workflows/deploy-worker.yml` (workflow_run trigger, pre-deploy check, run id in deploy message, post-deploy timestamp check)
- Created: `scripts/ci/deploy-worker-workflow.test.sh` (structure test, wired into `ci.yml` bash test group), `docs/RELEASE.md`
- Modified: `wrangler.toml` (break-glass comment), `package.json` (`deploy` passes `--message github-actions-run-${DEPLOY_RUN_ID}`)

- [x] Steps 1–4 and 7 delivered in #541.
- [ ] **Step 5: HUMAN STEP** — user disables Workers Builds auto-deploy for `aster-cloud` main in the Cloudflare console, sets `gh variable set ENABLE_CI_WORKER_DEPLOY --body true -R wontlost-ltd/aster-cloud`, records date + screenshot path in `k3s-aster-ai/docs/runbooks/release-evidence.md` (k3s#807).
- [ ] **Step 6: Evidence** — first main push after cutover: `wrangler deployments list --name aster-cloud` shows `github-actions-run-<id>`; Cloudflare build history shows no new automatic build.

### Task 2: PR split check and `safeColumnRead` — DONE (aster-cloud#541, 2026-10-24)

**Files:**
- Create: `scripts/ci/check-migration-split.mjs`, `src/__tests__/ci/check-migration-split.test.ts`
- Modify: `.github/workflows/ci.yml` (job `migration-split`, PR events)
- Create: `src/lib/safe-column-read.ts`, `src/__tests__/lib/safe-column-read.test.ts`
- Create: `scripts/eslint-rules/no-stale-safe-column-read.mjs` (fails when `SAFE_COLUMNS` is empty but calls exist)

**Interfaces:**
- `checkMigrationSplit({files, labels, body}) → {ok, reason}`; rule per ADR §2.1.6 with the whitelist `src/db/schema.ts`, `src/__tests__/**`, `drizzle/meta/**`; label `migration-and-code` requires `## 迁移窗口容错` in body.
- `safeColumnRead<T>(run: () => Promise<T>, fallback: () => Promise<T>, column: string): Promise<T>`; only `code === '42703'` (own or `cause`) triggers fallback + one `console.warn('[safe-column-read] …')`; registry `export const SAFE_COLUMNS: readonly string[]`.

- [ ] **Step 1: Tests first** — matrix from ADR §4 (7 cases) for the split check; 42703 own/cause, other error propagates, warning emitted once for `safeColumnRead`.
- [ ] **Step 2: Implement both**; wire the CI job; add the lint rule to the existing eslint config.
- [ ] **Step 3: Run** `pnpm vitest run src/__tests__/ci src/__tests__/lib/safe-column-read.test.ts`, `pnpm lint`, `tsc --noEmit`.
- [ ] **Step 4: Commit** `ci(migration): PR 迁移/代码拆分检查；lib: safeColumnRead 迁移窗口容错`.

## Part B — Owner prerequisites and handbooks (k3s-aster-ai)

### Task 3: Handbook rewrite and evidence file — docs delivered in k3s#807; HUMAN STEP 2 pending

**Files:**
- Modify: `apps/infrastructure/postgres-cluster/OWNER-SEPARATION.md`
- Create: `docs/runbooks/release-evidence.md`, `docs/runbooks/blue-green-postgres.md`

- [ ] **Step 1: OWNER-SEPARATION** — step 2 becomes "one-time superuser handover of **all** objects (schema, tables, sequences, views, `flyway_schema_history`) + default privileges", followed by "Flyway `afterMigrate` self-check keeps it"; add the exact verification queries from ADR §2.1.3.
- [ ] **Step 2: HUMAN STEP** — user runs the handover SQL on production `aster_api` as `postgres` and pastes the verification output into `release-evidence.md`.
- [ ] **Step 3: blue-green-postgres.md** — ADR §2.4 steps including the non-replicated items (DDL, sequences via `setval`, large objects), write freeze, Service vs Hyperdrive switch, rollback window, 7-day retention.
- [ ] **Step 4: Commit** `docs(runbooks): owner 首轮移交前置、发布证据、蓝绿数据库手册`.

## Part C — aster-api foundations

Worktree: `git -C /Users/rpang/IdeaProjects/aster-api worktree add -b feat/adr-0050-foundations /Users/rpang/IdeaProjects/aster-api-adr50 origin/main`.

### Task 4: Toolchain fail-fast, owner self-check callback, governance metadata

**Files:**
- Modify: `src/main/java/io/aster/policy/stability/ToolchainIdentityProvider.java` (+ `@Startup` check bean `ToolchainIdentityGuard`)
- Create: `src/main/resources/db/migration/afterMigrate__owner_selfcheck.sql`
- Modify: `src/main/resources/application.properties` (`aster.db.owner-check=strict` under `%prod`, `lenient` default)
- Modify: `src/test/java/io/aster/db/MigrationGovernanceTest.java`; Create: `src/test/java/io/aster/db/MigrationMetadata.java` (record + parser), fixtures under `src/test/resources/migration-governance/`
- Test: `ToolchainIdentityProviderTest` (extend), `OwnerSelfCheckIT` (Postgres)

**Interfaces:**
- `ToolchainIdentityGuard`: in `%prod`, if `currentToolchainId()` contains `=dev` → throw at startup with a message naming the segment.
- `afterMigrate__owner_selfcheck.sql`: `DO $$ … $$` — if role `aster_migrator` is missing: `RAISE NOTICE` and return; else apply default privileges and audit REVOKEs, then verify schema owner, all tables/sequences/views owner, `audit_logs` ACL `ar`, business table ACL `arwd`; on mismatch `RAISE EXCEPTION` when `current_setting('aster.owner_check', true) = 'strict'` (set via Flyway placeholder → `SET`), else `RAISE NOTICE`.
- `MigrationMetadata.parse(firstLine) → Optional<MigrationMetadata(contractOf, objects)>`; `DropDetector.detect(sqlWithoutCommentsAndStrings) → Set<String>`; rule: `EXECUTE` anywhere in a migration → fail.

- [ ] **Step 1: Governance tests first** — fixtures: header missing / invalid JSON / unknown field / `contractOf` absent / newer than current / objects mismatch (all red); DROP inside `--` comment, inside string literal, `IF EXISTS`, schema-qualified, multiple DROPs (green); `DO $$ EXECUTE 'DROP …' $$` (red via EXECUTE rule).
- [ ] **Step 2: Implement parser + detector + rules**; run `./gradlew --offline test --tests '*MigrationGovernanceTest*'`.
- [ ] **Step 3: Owner self-check** — write the SQL; `OwnerSelfCheckIT` on sdd-pg: fresh DB with roles → passes; flip one table owner to `aster_api_user` → strict fails with the table named; lenient only notices.
- [ ] **Step 4: Toolchain guard** — test `%prod` + `dev` → startup failure; non-prod → notice.
- [ ] **Step 5: Full suite** in background (`DB_JDBC_URL=…55433 … ./gradlew --offline :test --continue`), baseline failure is only `PrismaDatabaseE2ETest`.
- [ ] **Step 6: Commit** `feat(db): owner 自检回调、迁移治理元数据契约、工具链 dev 身份 fail-fast`.

### Task 5: Validation and run tables (migration-only PR)

**Files:**
- Create: `src/main/resources/db/migration/V6.25.0__policy_version_validation.sql`
- Modify: `src/main/java/io/aster/policy/entity/PolicyVersionValidation.java`, `PolicyRevalidationRun.java` (Panache entities, no readers yet)

**Interfaces:**
- `policy_version_validation(version_id bigint, toolchain_id text, status text check in ('valid','invalid'), diagnostics jsonb, diagnostics_schema int, run_id bigint null, checked_at timestamptz, primary key (version_id, toolchain_id))`.
- `policy_revalidation_run(id bigserial, toolchain_id text, scope text, scope_tenant text null, status text, cursor bigint default 0, scope_max_version_id bigint, heartbeat_at timestamptz, total int, checked int, invalid_count int, started_at, finished_at, error text)`; unique partial index `(toolchain_id, scope, coalesce(scope_tenant,'')) where status in ('queued','running')`.

- [ ] **Step 1: Write the migration + entities**; `MigrationGovernanceTest` stays green (no DROP).
- [ ] **Step 2: Flyway on sdd-pg** applies cleanly; `afterMigrate` self-check grants `arwd` to `aster_api_user` on both tables (verify ACL).
- [ ] **Step 3: Commit** `feat(db): 策略版本验证表与重验 run 表`.

### Task 6: `typecheckVersion` and `PolicyExecutionGate`

**Files:**
- Modify: `src/main/java/io/aster/policy/compiler/PolicyCompiler.java` (add `typecheckVersion(PolicyVersion)`; `compile(Long)` unchanged)
- Create: `src/main/java/io/aster/policy/compiler/PolicyExecutionGate.java`, `ValidationRepository.java`
- Modify: evaluate decision path (`PolicyEvaluationResource` / the service that calls `compile(Long)`) to call `gate.check(versionId)` first
- Modify: `application.properties` (`aster.compile.gate=on|off`, default on)
- Test: `PolicyCompilerTypecheckVersionTest`, `PolicyExecutionGateTest`, `PolicyEvaluationGateIT`

**Interfaces:**
- `CompilationResult typecheckVersion(PolicyVersion v)` — always `compile(v.content, v.locale, v.aliasSet)`; never reads artifact/coreJson; not cached.
- `GateDecision check(long versionId)` → `ALLOW | REFUSE(diagnostics)`; logic per Global Constraints; cache `ConcurrentHashMap<(versionId,toolchainId), Boolean>` for `valid` only.
- Refusal response: HTTP 422 `{"error":"policy_version_invalid","toolchainId":…, "diagnostics":[…]}`.

- [ ] **Step 1: Tests first** — artifact-present + E012 source → `typecheckVersion` returns E012; gate matrix (valid/invalid/missing × enforce/warn × db ok/db error); cache only populated for valid; `aster.compile.gate=off` restores old behaviour.
- [ ] **Step 2: Implement**; `PolicyEvaluationGateIT` end-to-end: deployed invalid version → 422 with diagnostics in enforce; executes with warning in warn.
- [ ] **Step 3: Full suite** background; commit `feat(policy): typecheckVersion 专用入口与执行门禁（无证明不执行）`.

### Task 7: `PolicyRevalidationJob` and admin endpoints

**Files:**
- Create: `src/main/java/io/aster/policy/revalidation/PolicyRevalidationJob.java`, `RevalidationRunService.java`, `RevalidationAdminResource.java`, `StartupRevalidationTrigger.java`
- Test: `PolicyRevalidationJobIT` (two in-process workers + kill simulation), `RevalidationAdminResourceTest` (HMAC + role header ignored), `StartupRevalidationTriggerIT`

**Interfaces:**
- `@Scheduled(every="30s", concurrentExecution=SKIP) void tick()` → `runService.claimAndProcessBatch(batchSize)`; claim query `… FOR UPDATE SKIP LOCKED LIMIT 1` over `queued` or `running AND heartbeat_at < now() - interval '2 minutes'`; batch = versions `id > cursor AND id <= scope_max_version_id` (+ tenant filter); results `INSERT … ON CONFLICT DO NOTHING`; same transaction updates `cursor`, `heartbeat_at`, counters; `done` when `cursor >= scope_max_version_id`.
- Startup trigger: compare `currentToolchainId()` with latest `done` run; insert `queued` run via `INSERT … ON CONFLICT DO NOTHING` (relies on the partial unique index).
- Endpoints: `POST /api/v1/admin/revalidation/runs {scope, tenantId?}` → 202 `{runId}` or 409 if active; `GET /api/v1/admin/revalidation/runs/{id}`; `GET /api/v1/admin/revalidation/invalid?toolchainId=` (paged). Audit `audit_logs` actions `policy.revalidation.{start,done,failed}`.

- [ ] **Step 1: Tests first** — two workers claim once; kill after writing results but before commit → no rows; after commit → cursor advanced, no duplicates on resume; heartbeat takeover; startup trigger idempotent across two starts.
- [ ] **Step 2: Implement**; run IT on sdd-pg; full suite background.
- [ ] **Step 3: Commit** `feat(policy): 工具链变化触发的增量重验任务与 admin 端点`.

## Part D — aster-cloud features

### Task 8: `dbEpoch` setting + admin proxy (code PR; no migration needed)

**Files:**
- Modify: `src/lib/platform-settings.ts` (`PLATFORM_SETTING_KEYS.AUTH_DB_EPOCH = 'auth.dbEpoch'`, `getSettingUncached`)
- Modify: `src/app/api/admin/platform-settings/route.ts` (positive int, monotonic, `confirm: "logout-all"`, `AuditLog` row with old/new)
- Modify: `src/auth.ts` (`jwt()`: write epoch on first login; compare uncached on later calls; `return null` on mismatch or read failure)
- Create: `src/app/api/admin/revalidation/[...path]/route.ts` (proxy via `signRequest`), `src/app/[locale]/(dashboard)/admin/revalidation/page.tsx` + content, sidebar + overview card + i18n (en/zh/de/hi), `config/routes.yaml` + `pnpm routes:build`
- Modify: policy detail page — "需修复" badge from `/api/admin/revalidation/invalid` lookup (owner-visible variant via a non-admin read endpoint `GET /api/policies/{id}/validation`)
- Test: `src/__tests__/lib/auth-epoch.test.ts` (real `NextAuth(config).handlers`), `admin-platform-settings-epoch.test.ts`, `admin-revalidation-proxy.test.ts`

- [ ] **Step 1: Tests first** per Review Focus 3; proxy 403 for non-admin; settings validation matrix.
- [ ] **Step 2: Implement**; `pnpm typecheck`-equivalent `tsc --noEmit`, `pnpm lint`, `pnpm routes:check`, `pnpm check:locales`, `pnpm check:message-keys`, full `pnpm test:run` in background.
- [ ] **Step 3: Commit** `feat(auth): 会话纪元 dbEpoch 与 admin 重验代理页`.

## Part E — aster-ai-workspace

### Task 9: `ai_workspace` owner/default-privilege block

**Files:**
- Modify: `src/main/resources/schema-aiw.sql`; `application.yml` (`aiw.db.owner-check: ${AIW_DB_OWNER_CHECK:lenient}` → prod `strict` via k3s env)
- Test: `SchemaOwnerCheckIT` (Testcontainers Postgres if present in repo, else H2-skipped + documented)

- [ ] **Step 1:** `DO $$ … $$` block: if `current_user` owns schema `ai_workspace` → `ALTER DEFAULT PRIVILEGES IN SCHEMA ai_workspace GRANT … TO current_user` (idempotent); else strict → `RAISE EXCEPTION`, lenient → `NOTICE`.
- [ ] **Step 2:** k3s `apps/aster-lang/ai/deployment.yaml` adds `AIW_DB_OWNER_CHECK=strict`.
- [ ] **Step 3: Commit** `feat(db): ai_workspace schema owner 自检`.

## Part F — Cross-repo scripts

### Task 10: `sourceHash` consistency script and image-pin auto-rerun

**Files:**
- Create: `aster-cloud/scripts/ops/check-policy-source-hash.mjs` (reads cloud `PolicyVersion.sourceHash` via `DATABASE_URL`, calls api `GET /api/v1/admin/policies/versions?ids=` via HMAC; prints mismatches; exit 1 if any)
- Modify: `k3s-aster-ai/.github/workflows/verify-image-pin.yml` — on `BEHIND` open PR, dispatch source repo `image-pin-pr` rerun via existing cross-repo token
- Modify: `scripts/ci/open-image-pin-pr-flow.test.sh` (both repos) — fixture "bot branch behind main"

- [ ] **Step 1:** tests/fixtures first; implement; run flow tests.
- [ ] **Step 2: Commit** `ci(image-pin): BEHIND 自动重跑；ops: 双库 sourceHash 校验`.

## Part G — Rehearsal and acceptance

### Task 11: Local stack rehearsal (no DB rebuild)

- [ ] Build api image from `feat/adr-0050-*` on top of core 1.0.34; start against the local stack (`~/.aster-stack`, aster-pg 55432) **without** wiping; seed an E012 version via SQL (as in `policy-20261013.sql` pattern).
- [ ] Restart api → startup run queued (toolchain changed because build SHA changed) → run completes → E012 version `invalid` → HMAC `evaluate` on it returns 422; guard sample executes.
- [ ] Restart api with the same image → no new run.
- [ ] Record results in `prod-e2e-20261023/findings.md` (or a new `ops/rehearsals/2026-10-xx.md`).

### Task 12: Staging acceptance

- [ ] Task 1 evidence (deploy waits for Job; Cloudflare history clean).
- [ ] `dbEpoch` bump on staging: old session page redirects to login; users/teams/Execution/AuditLog counts unchanged; p95 and DB QPS within budget (record numbers).
- [ ] Owner self-check on staging after a deliberate owner flip → Pod not ready with the named table.
- [ ] Ask Codex for the "implementation evidence" re-review promised in ADR §9; attach report path to the ADR PR.

---

## Done criteria

- All Review Focus tests exist and pass; full suites green (api baseline exception only; cloud 0 failures).
- ADR §4 acceptance rows each map to a test or an evidence line in `release-evidence.md`.
- No production DB rebuild was needed to enable any step; `aster_api`/`aster_cloud` row counts for the retained set unchanged before/after Tasks 5–8.
