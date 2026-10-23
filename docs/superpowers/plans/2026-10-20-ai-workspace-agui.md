# AI Workspace on AG-UI (ADR 0049) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace aster-cloud's in-page React "AI 助手" with a new Spring Boot 4 + Vaadin 25 service (`aster-ai-workspace`, `ai.aster-lang.cloud`) built on vaadin-agui + agui-vaadin-pro, shown as a standalone AI workspace and as a compact iframe panel in the policy editor, with persistent conversations, while every model call still goes through aster-cloud `/api/llm/*`.

**Architecture:** Four repos change. (A) vaadin-agui gains per-code-block actions (0.2.0). (B) aster-cloud issues a one-time Ed25519 handoff token, accepts a service-signed (HMAC) user identity on `/api/llm/*`, adds CSP/nav/feature-flag, and hosts the iframe panel with a postMessage bridge into Monaco. (C) the new `aster-ai-workspace` service translates cloud SSE frames into AG-UI events (`AsterAgent`), persists conversations/audit via Pro, and serves `WorkspaceView` and `PanelView`. (D) k3s manifests deploy it next to aster-api, using the shared CNPG Postgres (`aster_cloud` database, schema `ai_workspace`).

**Tech Stack:** Java 21, Spring Boot 4.1, Vaadin 25.3, com.wontlost:agui-vaadin 0.2.0, agui-vaadin-pro 0.1.2 (conversations, audit, ee-governance, ee-observability), Jackson 3 (`tools.jackson`), PostgreSQL 16 / H2 (tests); aster-cloud Next.js 16 + vitest; Lit 3 + TypeScript (agui-chat); Kustomize + Argo CD + External Secrets (k3s).

**Spec:** `docs/adr/0049-ai-workspace-agui.md` (ACCEPTED 2026-10-20). Read it first; this plan implements §2–§3 for SaaS. On-prem switching (§2.8 last bullet) is a follow-up plan after SaaS acceptance; Task 12 already builds the license gate it needs.

## Global Constraints

- Every model call goes through aster-cloud `/api/llm/generate|suggest|assistant`; the service never calls aster-api or a model provider directly and keeps no usage ledger (no Pro Metering, no Pro Approvals).
- Exactly one cloud `/api/llm/*` call per AG-UI run; billing semantics of cloud are unchanged.
- Identity on cloud is the user id (tenant = user id in cloud). The service maps `ConversationScope(tenantId=userId, ownerId=userId)`.
- Handoff token: Ed25519, TTL 60 s, single use (`jti`), audience `ai-workspace`; cloud signs with `AI_WORKSPACE_HANDOFF_PRIVATE_KEY` (base64 PKCS#8 DER), service verifies with `AI_WORKSPACE_HANDOFF_PUBLIC_KEY` (base64 X.509 DER).
- Service → cloud signature: HMAC-SHA256 hex with `AI_WORKSPACE_HMAC_KEY` over `[METHOD, path, ts, nonce, sha256hex(body), userId].join("\n")`; headers `X-Internal-Caller: ai-workspace`, `X-Aster-Timestamp` (unix seconds), `X-Aster-Nonce` (32 hex chars), `X-Aster-User-Id`, `X-Internal-Signature`; window 300 s; nonce single-use via cloud `claimNonce`.
- When `X-Internal-Caller: ai-workspace` is present on `/api/llm/*`, cloud MUST verify the signature and MUST NOT fall back to the cookie session (invalid → 401).
- Cloud feature flag: `NEXT_PUBLIC_AI_WORKSPACE_URL` (base URL, e.g. `https://ai.aster-lang.cloud`) + `AI_WORKSPACE_ENABLED_FOR` (`all` or comma-separated user ids). Unset URL ⇒ old React panel exactly as today. SaaS only (`IS_SAAS`).
- postMessage protocol `v: 1`, types `aster.ai.ready`, `aster.ai.context`, `aster.ai.apply`, `aster.ai.applied`, `aster.ai.reauth`; parent accepts only messages whose `origin` equals the workspace origin; the service posts only to `ASTER_CLOUD_ORIGIN`.
- Editor-panel default thread id: `policy:{policyId}`; `generate` never auto-applies (explicit "Apply to editor").
- Conversations persist in Postgres database `aster_cloud`, schema `ai_workspace` (JDBC URL `?currentSchema=ai_workspace`); retention 180 days; redaction on stored copies (Pro defaults + API-key-like pattern).
- Pro features are enabled only if `License.status().valid()` and the key `covers(feature)`; otherwise the service runs vaadin-agui base chat with session-memory history.
- Service runs on arm64 nodes, read-only root FS, non-root uid 1001, `/tmp` emptyDir; requests 384Mi/100m, limits 768Mi/500m; health `/actuator/health/{liveness,readiness}`.
- UI strings in 4 locales: en, zh, de, hi (hi in Devanagari).
- Code comments in Simplified Chinese describing intent; no "修改说明" comments; functions ≤ 3 indentation levels.
- Commit trailer exactly `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`; no model names elsewhere in committed content; no push/tag/publish/repo creation/secret writes without the user's explicit go-ahead (HUMAN STEP tasks).
- Tests needing Postgres use sdd-pg `localhost:55433` / sdd-redis `localhost:6380` only.
- Long commands (builds, full suites) run in the background with a log; never block > 10 min without output.

## Review Focus

1. Handoff token expired or already used (slow iframe, browser back, double load) → the user is transparently sent back through cloud's handoff route and lands on the intended page, never on an error page. Pinned in Task 11 (`HandoffControllerTest.expiredTokenRedirectsToCloudHandoff`, `replayedTokenRedirectsToCloudHandoff`).
2. Cloud answers the service with a non-2xx JSON denial (`ai_quota_exhausted`, `ai_email_unverified`, `byok_unavailable`, 401) → the chat shows a localized, specific error with the code, not "endpoint responded 403". Pinned in Task 10 (`AsterAgentTest.denialBecomesLocalizedRunError`).
3. Policy source larger than 64 KiB in the editor → the run fails with a readable error and the panel stays usable; nothing crashes. Pinned in Task 10 (`AsterAgentTest.oversizedSourceIsRejectedBeforeCallingCloud`).
4. A postMessage from a foreign origin, or an `aster.ai.apply` whose `policyId` differs from the open editor → ignored, no Monaco edit, no exception. Pinned in Task 7 (`ai-workspace-bridge.test.ts`).
5. No (or non-covering) Pro license → the service still serves chat with session-memory history and no Pro binders are attached. Pinned in Task 12 (`ProFeaturesTest`, `HistoryBindingTest`).

---

## Part A — vaadin-agui 0.2.0 (repo `/Users/rpang/IdeaProjects/vaadin-agui`)

Worktree: `git -C /Users/rpang/IdeaProjects/vaadin-agui worktree add -b feat/code-block-actions /Users/rpang/IdeaProjects/vaadin-agui-cba origin/main`.

### Task 1: Per-code-block actions in `<agui-chat>`

**Files:**
- Modify: `src/main/resources/META-INF/frontend/agui-vaadin/agui-markdown.ts`
- Modify: `src/main/resources/META-INF/frontend/agui-vaadin/agui-chat.ts`
- Modify: `src/main/java/com/wontlost/agui/AgUiChat.java`, `src/main/java/com/wontlost/agui/AgUiChatI18n.java`
- Create: `src/main/java/com/wontlost/agui/AgUiCodeAction.java`
- Test: `src/test/java/com/wontlost/agui/AgUiChatCodeActionTest.java`

**Interfaces:**
- Produces (TS): property `codeActions: string[]` (default `['copy']`, allowed `'copy'|'insert'|'replace'`); DOM event `agui-code-action` with `detail {action, code, language, messageId}` (bubbles, composed); message element attribute `data-message-id`.
- Produces (Java): `enum AgUiCodeAction { COPY("copy"), INSERT("insert"), REPLACE("replace") }` with `String getValue()`; `AgUiChat.setCodeActions(AgUiCodeAction... actions)`; `AgUiChat.CodeActionEvent` with `getAction() : String`, `getCode()`, `getLanguage()`, `getMessageId()`; `Registration addCodeActionListener(ComponentEventListener<CodeActionEvent>)`; `AgUiChatI18n` new fields `copyCode` ("Copy code"), `insertCode` ("Insert"), `replaceCode` ("Replace").

- [ ] **Step 1: Write the failing Java test**

```java
package com.wontlost.agui;

import com.vaadin.flow.component.ComponentUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgUiChatCodeActionTest {

    @Test
    void setCodeActionsWritesPropertyList() {
        AgUiChat chat = new AgUiChat("/agui/x");
        chat.setCodeActions(AgUiCodeAction.COPY, AgUiCodeAction.INSERT, AgUiCodeAction.REPLACE);
        String json = chat.getElement().getPropertyRaw("codeActions").toString();
        assertTrue(json.contains("copy") && json.contains("insert") && json.contains("replace"), json);
    }

    @Test
    void codeActionEventCarriesDetail() {
        AgUiChat chat = new AgUiChat("/agui/x");
        List<AgUiChat.CodeActionEvent> seen = new ArrayList<>();
        chat.addCodeActionListener(seen::add);
        ComponentUtil.fireEvent(chat, new AgUiChat.CodeActionEvent(chat, true, "insert", "Rule a.", "aster", "m-1"));
        assertEquals(1, seen.size());
        AgUiChat.CodeActionEvent e = seen.get(0);
        assertEquals("insert", e.getAction());
        assertEquals("Rule a.", e.getCode());
        assertEquals("aster", e.getLanguage());
        assertEquals("m-1", e.getMessageId());
    }

    @Test
    void i18nHasCodeActionLabels() {
        AgUiChatI18n i18n = new AgUiChatI18n();
        assertEquals("Copy code", i18n.getCopyCode());
        assertEquals("Insert", i18n.getInsertCode());
        assertEquals("Replace", i18n.getReplaceCode());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -Dtest=AgUiChatCodeActionTest test`
Expected: compilation FAIL (`AgUiCodeAction`, `setCodeActions`, `CodeActionEvent` missing).

- [ ] **Step 3: Implement the Java side**

`AgUiCodeAction.java`:

```java
package com.wontlost.agui;

/** 代码块上可显示的动作；值与前端 codeActions 属性一致。 */
public enum AgUiCodeAction {
    COPY("copy"), INSERT("insert"), REPLACE("replace");

    private final String value;

    AgUiCodeAction(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
```

In `AgUiChat.java`, next to the other setters/listeners (follow the existing `FeedbackEvent` pattern):

```java
    /** 设置每个代码块工具栏上显示的动作（默认只有复制）。 */
    public void setCodeActions(AgUiCodeAction... actions) {
        tools.jackson.databind.node.ArrayNode arr = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (AgUiCodeAction a : actions) {
            arr.add(a.getValue());
        }
        getElement().setPropertyJson("codeActions", arr);
    }

    public Registration addCodeActionListener(ComponentEventListener<CodeActionEvent> listener) {
        return addListener(CodeActionEvent.class, listener);
    }

    /** 用户点击代码块上的「插入/替换/复制」时触发；宿主据此把代码写回编辑器等。 */
    @DomEvent("agui-code-action")
    public static class CodeActionEvent extends ComponentEvent<AgUiChat> {
        private final String action;
        private final String code;
        private final String language;
        private final String messageId;

        public CodeActionEvent(AgUiChat source, boolean fromClient,
                               @EventData("event.detail.action") String action,
                               @EventData("event.detail.code") String code,
                               @EventData("event.detail.language") String language,
                               @EventData("event.detail.messageId") String messageId) {
            super(source, fromClient);
            this.action = action;
            this.code = code;
            this.language = language;
            this.messageId = messageId;
        }

        public String getAction() { return action; }
        public String getCode() { return code; }
        public String getLanguage() { return language; }
        public String getMessageId() { return messageId; }
    }
```

(If the existing class uses a different JSON-property helper than `setPropertyJson` for arrays — check how `setFrontendTools` writes a list — use that helper instead; the test reads `getPropertyRaw`.)

In `AgUiChatI18n.java` add three fields with fluent setters and getters, following the existing field style:

```java
    private String copyCode = "Copy code";
    private String insertCode = "Insert";
    private String replaceCode = "Replace";

    public String getCopyCode() { return copyCode; }
    public AgUiChatI18n setCopyCode(String v) { this.copyCode = v; return this; }
    public String getInsertCode() { return insertCode; }
    public AgUiChatI18n setInsertCode(String v) { this.insertCode = v; return this; }
    public String getReplaceCode() { return replaceCode; }
    public AgUiChatI18n setReplaceCode(String v) { this.replaceCode = v; return this; }
```

- [ ] **Step 4: Implement the browser side**

In `agui-markdown.ts` add a pure DOM helper (runs after `unsafeHTML` rendered the sanitized markdown; idempotent so repeated `updated()` calls are safe):

```ts
/** 代码块动作：copy | insert | replace */
export type CodeAction = 'copy' | 'insert' | 'replace';

/**
 * 给已渲染气泡中的每个 <pre> 加一行动作工具栏（幂等：已处理的块跳过）。
 * 在 DOMPurify 之后执行，按钮不进入消毒流程；点击由宿主元素委托处理。
 */
export function decorateCodeBlocks(
  root: ParentNode,
  actions: CodeAction[],
  labels: Record<CodeAction, string>,
): void {
  if (actions.length === 0) return;
  root.querySelectorAll<HTMLPreElement>('.bubble pre:not([data-code-decorated])').forEach((pre) => {
    pre.setAttribute('data-code-decorated', '');
    const bar = document.createElement('div');
    bar.className = 'code-actions';
    bar.setAttribute('part', 'code-actions');
    for (const action of actions) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.dataset.codeAction = action;
      btn.textContent = labels[action];
      bar.appendChild(btn);
    }
    pre.insertAdjacentElement('afterend', bar);
  });
}

/** 从 <pre><code class="language-x"> 取语言名。 */
export function codeLanguage(pre: HTMLPreElement): string {
  const cls = pre.querySelector('code')?.className ?? '';
  return /language-(\S+)/.exec(cls)?.[1] ?? '';
}
```

In `agui-chat.ts`:
1. Add next to the other properties: `@property({ type: Array, attribute: false }) codeActions: CodeAction[] = ['copy'];` and extend `DEFAULT_I18N` with `copyCode: 'Copy code', insertCode: 'Insert', replaceCode: 'Replace'` (mirror `AgUiChatI18n`).
2. In `_renderMessage`, add `data-message-id=${m.id}` on the message's root element.
3. In `updated()`, after the existing scroll logic, call `decorateCodeBlocks(this.renderRoot, this.running ? [] : this.codeActions, {copy: this.i18n.copyCode, insert: this.i18n.insertCode, replace: this.i18n.replaceCode})` — skip while a run is streaming because the streaming bubble is re-rendered on every chunk.
4. Add one delegated click handler on the messages container (`@click=${this._onCodeActionClick}`):

```ts
  private _onCodeActionClick(e: Event): void {
    const btn = (e.target as HTMLElement).closest<HTMLButtonElement>('button[data-code-action]');
    if (!btn) return;
    const pre = btn.previousElementSibling as HTMLPreElement | null;
    const code = pre?.querySelector('code')?.textContent ?? pre?.textContent ?? '';
    const messageId = btn.closest<HTMLElement>('[data-message-id]')?.dataset.messageId ?? '';
    const action = btn.dataset.codeAction as CodeAction;
    if (action === 'copy') void navigator.clipboard?.writeText(code);
    this._fire('agui-code-action', { action, code, language: pre ? codeLanguage(pre) : '', messageId });
  }
```

5. CSS (inside the existing `static styles`): `.code-actions{display:flex;gap:.25rem;justify-content:flex-end;margin:-.25rem 0 .5rem}` and `.code-actions button{font:inherit;font-size:.75rem;padding:.125rem .5rem;border:1px solid var(--lumo-contrast-20pct,#ccc);border-radius:.25rem;background:transparent;cursor:pointer}`.

- [ ] **Step 5: Verify**

Run (background + log): `cd src/main/resources/META-INF/frontend/agui-vaadin && npm ci && npm run typecheck` then `mvn -B -ntp clean verify`.
Expected: typecheck exit 0; all Java tests pass including the 3 new ones.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/META-INF/frontend/agui-vaadin/agui-markdown.ts src/main/resources/META-INF/frontend/agui-vaadin/agui-chat.ts src/main/java/com/wontlost/agui/AgUiChat.java src/main/java/com/wontlost/agui/AgUiChatI18n.java src/main/java/com/wontlost/agui/AgUiCodeAction.java src/test/java/com/wontlost/agui/AgUiChatCodeActionTest.java
git commit -m "feat(chat): 代码块动作（复制/插入/替换）与 agui-code-action 事件

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 2: Release vaadin-agui 0.2.0 (version bump; publishing is a HUMAN STEP)

**Files:**
- Modify: `pom.xml` (`<version>0.2.0</version>`), `src/main/resources/META-INF/frontend/agui-vaadin/package.json` (`"version": "0.2.0"`), `CHANGELOG.md`

- [ ] **Step 1:** Bump both versions to `0.2.0` and prepend to `CHANGELOG.md`:

```markdown
## 0.2.0 (2026-10-20)

- 新增：代码块动作工具栏（`AgUiChat#setCodeActions(AgUiCodeAction...)`，前端属性 `codeActions`），支持复制 / 插入 / 替换。
- 新增：`agui-code-action` 事件（`AgUiChat.CodeActionEvent`：action、code、language、messageId），宿主可据此把代码写回编辑器。
- 新增：`AgUiChatI18n` 的 `copyCode`、`insertCode`、`replaceCode` 文案。
- 消息元素增加 `data-message-id` 属性。
```

- [ ] **Step 2:** `mvn -B -ntp clean verify` (background + log) → exit 0.
- [ ] **Step 3:** Commit `chore(release): 0.2.0` (trailer as above).
- [ ] **Step 4 — HUMAN STEP:** Controller stops and asks the user to approve: push the branch, open the PR, merge after CI, tag `v0.2.0` on main (triggers `publish.yml` → Maven Central). Parts C/D need `com.wontlost:agui-vaadin:0.2.0` resolvable; until it is published, Part C builds against a locally installed snapshot (`mvn -B -ntp install -DskipTests` in this worktree) — note this in the report.

---

## Part B — aster-cloud (repo `/Users/rpang/IdeaProjects/aster-cloud`)

Worktree: `git -C /Users/rpang/IdeaProjects/aster-cloud worktree add -b feat/ai-workspace-bridge /Users/rpang/IdeaProjects/aster-cloud-aiw origin/main` then `pnpm install --frozen-lockfile` (background). Never touch the user checkout `/Users/rpang/IdeaProjects/aster-cloud`.

### Task 3: Workspace config + handoff token signer

**Files:**
- Create: `src/lib/ai-workspace.ts`, `src/lib/ai-workspace-handoff.ts`
- Modify: `src/lib/env-validation.ts` (optional keys), `wrangler.toml` (comments documenting `wrangler secret put AI_WORKSPACE_HANDOFF_PRIVATE_KEY` and `AI_WORKSPACE_HMAC_KEY`; `[vars]` gets NO value yet — enabling is Task 18), `.env.example`
- Test: `src/__tests__/lib/ai-workspace.test.ts`, `src/__tests__/lib/ai-workspace-handoff.test.ts`

**Interfaces:**
- Produces: `resolveAiWorkspaceBase(configured?: string): string | null` (null when unset/invalid), `aiWorkspaceOrigin(configured?: string): string | null`, `isAiWorkspaceEnabledFor(userId: string, base?: string|null, enabledFor?: string): boolean` (false if base null or `!IS_SAAS`; true for `all`; else membership in comma list), `AI_WORKSPACE_NEXT_PATTERN = /^\/(panel(\?policyId=[A-Za-z0-9_-]{1,64})?)?$/`.
- Produces: `interface HandoffClaims { v: 1; aud: 'ai-workspace'; sub: string; name: string | null; email: string | null; locale: string; role: string; iat: number; exp: number; jti: string }`; `signHandoffToken(claims: Omit<HandoffClaims,'v'|'aud'|'iat'|'exp'|'jti'>, privateKeyB64: string, now?: number): Promise<string>` returning `base64url(json) + '.' + base64url(signature)`.

- [ ] **Step 1: Write failing tests**

```ts
// src/__tests__/lib/ai-workspace.test.ts
import { describe, expect, it } from 'vitest';
import { AI_WORKSPACE_NEXT_PATTERN, aiWorkspaceOrigin, isAiWorkspaceEnabledFor, resolveAiWorkspaceBase } from '@/lib/ai-workspace';

describe('ai-workspace config', () => {
  it('解析合法 https 地址并去掉末尾斜杠', () => {
    expect(resolveAiWorkspaceBase('https://ai.aster-lang.cloud/')).toBe('https://ai.aster-lang.cloud');
    expect(aiWorkspaceOrigin('https://ai.aster-lang.cloud/x')).toBe('https://ai.aster-lang.cloud');
  });
  it('未设置或非法时返回 null（功能关闭）', () => {
    expect(resolveAiWorkspaceBase(undefined)).toBeNull();
    expect(resolveAiWorkspaceBase('not a url')).toBeNull();
    expect(aiWorkspaceOrigin('')).toBeNull();
  });
  it('灰度：all 对所有人开启；名单只对列出的用户开启；地址缺失一律关闭', () => {
    const base = 'https://ai.aster-lang.cloud';
    expect(isAiWorkspaceEnabledFor('u1', base, 'all')).toBe(true);
    expect(isAiWorkspaceEnabledFor('u1', base, 'u2, u1')).toBe(true);
    expect(isAiWorkspaceEnabledFor('u3', base, 'u2,u1')).toBe(false);
    expect(isAiWorkspaceEnabledFor('u1', base, '')).toBe(false);
    expect(isAiWorkspaceEnabledFor('u1', null, 'all')).toBe(false);
  });
  it('next 只允许工作台根路径与带 policyId 的侧栏', () => {
    expect(AI_WORKSPACE_NEXT_PATTERN.test('/')).toBe(true);
    expect(AI_WORKSPACE_NEXT_PATTERN.test('/panel?policyId=abc-123')).toBe(true);
    expect(AI_WORKSPACE_NEXT_PATTERN.test('//evil.com')).toBe(false);
    expect(AI_WORKSPACE_NEXT_PATTERN.test('/panel?policyId=a&x=1')).toBe(false);
  });
});
```

```ts
// src/__tests__/lib/ai-workspace-handoff.test.ts
import { describe, expect, it } from 'vitest';
import { signHandoffToken } from '@/lib/ai-workspace-handoff';

async function keyPair() {
  const kp = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair;
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey('pkcs8', kp.privateKey)).toString('base64');
  return { kp, pkcs8 };
}
const b64urlToBytes = (s: string) => Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');

describe('signHandoffToken', () => {
  it('签出 payload.signature，可用公钥验证，exp = iat + 60', async () => {
    const { kp, pkcs8 } = await keyPair();
    const token = await signHandoffToken({ sub: 'u1', name: 'Ann', email: 'a@x.io', locale: 'zh', role: 'owner' }, pkcs8, 1_800_000_000);
    const [payload, sig] = token.split('.');
    const claims = JSON.parse(b64urlToBytes(payload).toString('utf8'));
    expect(claims).toMatchObject({ v: 1, aud: 'ai-workspace', sub: 'u1', locale: 'zh', role: 'owner', iat: 1_800_000_000, exp: 1_800_000_060 });
    expect(claims.jti).toMatch(/^[0-9a-f-]{36}$/);
    const ok = await crypto.subtle.verify({ name: 'Ed25519' }, kp.publicKey, b64urlToBytes(sig), new TextEncoder().encode(payload));
    expect(ok).toBe(true);
  });
  it('私钥非法时抛错（不返回半成品令牌）', async () => {
    await expect(signHandoffToken({ sub: 'u1', name: null, email: null, locale: 'en', role: 'owner' }, 'bm90LWEta2V5')).rejects.toThrow();
  });
});
```

- [ ] **Step 2:** `pnpm exec vitest run src/__tests__/lib/ai-workspace.test.ts src/__tests__/lib/ai-workspace-handoff.test.ts --project saas` → FAIL (modules missing).

- [ ] **Step 3: Implement**

```ts
// src/lib/ai-workspace.ts
import { IS_SAAS } from '@/lib/deployment-mode';

/**
 * AI 工作台（ADR 0049）的部署配置。全部在调用时读取环境变量（不在模块顶层），
 * 以满足 middleware 导入链不得在加载期触及 process.env 的约束。
 */
export const AI_WORKSPACE_NEXT_PATTERN = /^\/(panel(\?policyId=[A-Za-z0-9_-]{1,64})?)?$/;

export function resolveAiWorkspaceBase(configured?: string): string | null {
  if (!configured) return null;
  try {
    const url = new URL(configured);
    if (url.protocol !== 'https:' && url.hostname !== 'localhost') return null;
    return `${url.origin}${url.pathname.replace(/\/+$/, '')}`;
  } catch {
    return null;
  }
}

export function aiWorkspaceOrigin(configured?: string): string | null {
  const base = resolveAiWorkspaceBase(configured);
  return base ? new URL(base).origin : null;
}

/** 灰度开关：地址缺失或非 SaaS 一律关闭；'all' 全量；否则按用户 id 名单。 */
export function isAiWorkspaceEnabledFor(userId: string, base?: string | null, enabledFor?: string): boolean {
  if (!IS_SAAS || !base) return false;
  const list = (enabledFor ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  return list.includes('all') || list.includes(userId);
}
```

```ts
// src/lib/ai-workspace-handoff.ts
/**
 * 交接令牌（ADR 0049 §2.6）：cloud 用 Ed25519 私钥签名，AI 工作台用公钥验证。
 * 用 WebCrypto 实现（Workers 与 Node 24 均支持 Ed25519），不引入 JWT 依赖。
 */
export interface HandoffClaims {
  v: 1;
  aud: 'ai-workspace';
  sub: string;
  name: string | null;
  email: string | null;
  locale: string;
  role: string;
  iat: number;
  exp: number;
  jti: string;
}

const TTL_SECONDS = 60;

const toB64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function fromB64(b64: string): Uint8Array {
  const bin = atob(b64);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export async function signHandoffToken(
  claims: Omit<HandoffClaims, 'v' | 'aud' | 'iat' | 'exp' | 'jti'>,
  privateKeyB64: string,
  now: number = Math.floor(Date.now() / 1000),
): Promise<string> {
  const key = await crypto.subtle.importKey('pkcs8', fromB64(privateKeyB64), { name: 'Ed25519' }, false, ['sign']);
  const full: HandoffClaims = { v: 1, aud: 'ai-workspace', ...claims, iat: now, exp: now + TTL_SECONDS, jti: crypto.randomUUID() };
  const payload = toB64url(new TextEncoder().encode(JSON.stringify(full)));
  const sig = new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, key, new TextEncoder().encode(payload)));
  return `${payload}.${toB64url(sig)}`;
}
```

Add `AI_WORKSPACE_HANDOFF_PRIVATE_KEY`, `AI_WORKSPACE_HMAC_KEY` as **optional** keys in `env-validation.ts` (follow how other optional keys are declared); document both plus `NEXT_PUBLIC_AI_WORKSPACE_URL` / `AI_WORKSPACE_ENABLED_FOR` in `.env.example` and as comments in `wrangler.toml`.

- [ ] **Step 4:** Re-run the two test files → PASS. Run `pnpm exec tsc --noEmit` → 0.
- [ ] **Step 5:** Commit `feat(ai-workspace): 工作台配置与 Ed25519 交接令牌签发` (trailer).

### Task 4: Handoff route `/api/ai-workspace/handoff`

**Files:**
- Create: `src/app/api/ai-workspace/handoff/route.ts`
- Modify: `config/routes.yaml` (+ run `pnpm run routes:build`, commit `public/.well-known/routes.json`)
- Test: `src/__tests__/app/api/ai-workspace/handoff-route.test.ts`

**Interfaces:**
- Consumes: `auth()` (`@/auth`), `getEffectiveRole(userId)` (`@/lib/effective-role`), Task 3 helpers, `safeEnv`.
- Produces: `GET /api/ai-workspace/handoff?next=<path>&locale=<en|zh|de|hi>` → 302 to `${base}/handoff?token=<t>&next=<urlencoded next>`; unauthenticated → 302 `/${locale}/login?callbackUrl=<encoded original url>`; disabled for user or flag off → 404; `next` failing `AI_WORKSPACE_NEXT_PATTERN` → 400; missing private key → 503.

- [ ] **Step 1: Write the failing test** (mock pattern from `src/__tests__/app/api/llm/sse-proxy-quota-gate.test.ts`: `vi.hoisted` mocks for `@/auth` and `@/lib/effective-role`, `vi.stubEnv`, `vi.resetModules()`, dynamic import of the route):

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const mocks = vi.hoisted(() => ({ auth: vi.fn(), role: vi.fn() }));
vi.mock('@/auth', () => ({ auth: mocks.auth }));
vi.mock('@/lib/effective-role', () => ({ getEffectiveRole: mocks.role }));

async function privateKey() {
  const kp = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair;
  return Buffer.from(await crypto.subtle.exportKey('pkcs8', kp.privateKey)).toString('base64');
}
const req = (qs: string) => new NextRequest(`https://aster-lang.cloud/api/ai-workspace/handoff${qs}`);

describe('GET /api/ai-workspace/handoff', () => {
  beforeEach(async () => {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_AI_WORKSPACE_URL', 'https://ai.aster-lang.cloud');
    vi.stubEnv('AI_WORKSPACE_ENABLED_FOR', 'all');
    vi.stubEnv('AI_WORKSPACE_HANDOFF_PRIVATE_KEY', await privateKey());
    mocks.auth.mockResolvedValue({ user: { id: 'u1', name: 'Ann', email: 'a@x.io' } });
    mocks.role.mockResolvedValue('owner');
  });

  it('已登录：302 到工作台 /handoff，带令牌与 next', async () => {
    const { GET } = await import('@/app/api/ai-workspace/handoff/route');
    const res = await GET(req('?next=%2Fpanel%3FpolicyId%3Dp-1&locale=zh'));
    expect(res.status).toBe(302);
    const loc = new URL(res.headers.get('location')!);
    expect(loc.origin + loc.pathname).toBe('https://ai.aster-lang.cloud/handoff');
    expect(loc.searchParams.get('next')).toBe('/panel?policyId=p-1');
    expect(loc.searchParams.get('token')).toMatch(/^[\w-]+\.[\w-]+$/);
  });
  it('未登录：302 到登录页并带回调', async () => {
    mocks.auth.mockResolvedValue(null);
    const { GET } = await import('@/app/api/ai-workspace/handoff/route');
    const res = await GET(req('?next=%2F&locale=de'));
    expect(res.status).toBe(302);
    expect(res.headers.get('location')).toMatch(/^https:\/\/aster-lang\.cloud\/de\/login\?callbackUrl=/);
  });
  it('非法 next：400', async () => {
    const { GET } = await import('@/app/api/ai-workspace/handoff/route');
    expect((await GET(req('?next=%2F%2Fevil.com'))).status).toBe(400);
  });
  it('不在灰度名单：404', async () => {
    vi.stubEnv('AI_WORKSPACE_ENABLED_FOR', 'someone-else');
    const { GET } = await import('@/app/api/ai-workspace/handoff/route');
    expect((await GET(req('?next=%2F'))).status).toBe(404);
  });
  it('私钥缺失：503', async () => {
    vi.stubEnv('AI_WORKSPACE_HANDOFF_PRIVATE_KEY', '');
    const { GET } = await import('@/app/api/ai-workspace/handoff/route');
    expect((await GET(req('?next=%2F'))).status).toBe(503);
  });
});
```

- [ ] **Step 2:** Run → FAIL (route missing).
- [ ] **Step 3: Implement**

```ts
// src/app/api/ai-workspace/handoff/route.ts
import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@/auth';
import { getEffectiveRole } from '@/lib/effective-role';
import { AI_WORKSPACE_NEXT_PATTERN, isAiWorkspaceEnabledFor, resolveAiWorkspaceBase } from '@/lib/ai-workspace';
import { signHandoffToken } from '@/lib/ai-workspace-handoff';
import { safeEnv } from '@/lib/runtime/safe-env';

const LOCALES = new Set(['en', 'zh', 'de', 'hi']);

/** 把当前登录用户交接给 AI 工作台（ADR 0049 §2.6）：签一次性令牌后 302 过去。 */
export async function GET(req: NextRequest): Promise<NextResponse> {
  const base = resolveAiWorkspaceBase(safeEnv('NEXT_PUBLIC_AI_WORKSPACE_URL'));
  const next = req.nextUrl.searchParams.get('next') ?? '/';
  const localeParam = req.nextUrl.searchParams.get('locale') ?? 'en';
  const locale = LOCALES.has(localeParam) ? localeParam : 'en';
  if (!AI_WORKSPACE_NEXT_PATTERN.test(next)) return NextResponse.json({ error: 'invalid_next' }, { status: 400 });

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    const login = new URL(`/${locale}/login`, req.nextUrl.origin);
    login.searchParams.set('callbackUrl', req.nextUrl.toString());
    return NextResponse.redirect(login, 302);
  }
  if (!isAiWorkspaceEnabledFor(userId, base, safeEnv('AI_WORKSPACE_ENABLED_FOR'))) {
    return NextResponse.json({ error: 'not_found' }, { status: 404 });
  }
  const privateKey = safeEnv('AI_WORKSPACE_HANDOFF_PRIVATE_KEY');
  if (!privateKey) return NextResponse.json({ error: 'ai_workspace_unconfigured' }, { status: 503 });

  const token = await signHandoffToken(
    { sub: userId, name: session.user.name ?? null, email: session.user.email ?? null, locale, role: await getEffectiveRole(userId) },
    privateKey,
  );
  const target = new URL(`${base}/handoff`);
  target.searchParams.set('token', token);
  target.searchParams.set('next', next);
  return NextResponse.redirect(target, 302);
}
```

(Check `getEffectiveRole`'s real signature/return type in `src/lib/effective-role.ts`; adapt the call, keep the test contract.)

Add to `config/routes.yaml` an entry for `/api/ai-workspace/handoff` following the existing API-route entry shape (status, owner `ai`, `source: src/app/api/ai-workspace/handoff/route.ts`, notes "ADR 0049 handoff to AI workspace"), then `pnpm run routes:build`.

- [ ] **Step 4:** Test file PASS; `pnpm routes:check` OK; `git diff --exit-code public/.well-known/routes.json` after build is committed.
- [ ] **Step 5:** Commit `feat(ai-workspace): 交接路由 /api/ai-workspace/handoff` (trailer).

### Task 5: Service-signed identity on `/api/llm/*`

**Files:**
- Create: `src/lib/llm-caller.ts`
- Modify: `src/lib/llm-sse-proxy.ts` (read body once as text; replace `auth()` with `resolveLlmCaller`), `src/app/api/llm/complete/route.ts` (same replacement), `src/lib/security/csrf-gate.ts` (exemption)
- Test: `src/__tests__/lib/llm-caller.test.ts`, extend `src/__tests__/app/api/llm/sse-proxy-quota-gate.test.ts`, extend the csrf-gate test (`src/__tests__/lib/middleware-csrf-gate.test.ts` or the existing csrf-gate test file)

**Interfaces:**
- Produces: `type LlmCaller = { userId: string; via: 'session' | 'ai-workspace' }`; `resolveLlmCaller(req: Request, rawBody: string): Promise<LlmCaller | { error: string; status: 401 | 503 }>`; `AI_WORKSPACE_CALLER = 'ai-workspace'`; `canonicalAiWorkspace(method, path, ts, nonce, bodySha256Hex, userId): string` = `[method.toUpperCase(), path, ts, nonce, bodySha256Hex, userId].join('\n')`.
- Consumes: `hmacVerify(secret, data, hexSig)`, `sha256Hex(data)` (`@/lib/api-signing`), `claimNonce(nonce, expiresAt)` (`@/services/security/nonce-service` — check exact signature), `auth()`.

- [ ] **Step 1: Write the failing unit test**

```ts
// src/__tests__/lib/llm-caller.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ auth: vi.fn(), claimNonce: vi.fn() }));
vi.mock('@/auth', () => ({ auth: mocks.auth }));
vi.mock('@/services/security/nonce-service', () => ({ claimNonce: mocks.claimNonce }));

const KEY = 'k'.repeat(64);
async function hmacHex(data: string) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(KEY), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return Buffer.from(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(data))).toString('hex');
}
async function sha(s: string) {
  return Buffer.from(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s))).toString('hex');
}
async function signed(body: string, over: Partial<Record<string, string>> = {}) {
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = 'a'.repeat(32);
  const canonical = ['POST', '/api/llm/suggest', ts, nonce, await sha(body), 'u1'].join('\n');
  const headers: Record<string, string> = {
    'x-internal-caller': 'ai-workspace', 'x-aster-timestamp': ts, 'x-aster-nonce': nonce,
    'x-aster-user-id': 'u1', 'x-internal-signature': await hmacHex(canonical), ...over,
  };
  return new Request('https://aster-lang.cloud/api/llm/suggest', { method: 'POST', headers, body });
}

describe('resolveLlmCaller', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubEnv('AI_WORKSPACE_HMAC_KEY', KEY);
    mocks.auth.mockReset();
    mocks.claimNonce.mockReset().mockResolvedValue({ ok: true });
  });

  it('无服务头：走会话', async () => {
    mocks.auth.mockResolvedValue({ user: { id: 'u9' } });
    const { resolveLlmCaller } = await import('@/lib/llm-caller');
    const r = await resolveLlmCaller(new Request('https://aster-lang.cloud/api/llm/suggest', { method: 'POST', body: '{}' }), '{}');
    expect(r).toEqual({ userId: 'u9', via: 'session' });
  });
  it('服务签名正确：返回服务身份且不读会话', async () => {
    const { resolveLlmCaller } = await import('@/lib/llm-caller');
    const r = await resolveLlmCaller(await signed('{"source":"x"}'), '{"source":"x"}');
    expect(r).toEqual({ userId: 'u1', via: 'ai-workspace' });
    expect(mocks.auth).not.toHaveBeenCalled();
  });
  it('签名错误：401，且绝不回落到 cookie 会话', async () => {
    mocks.auth.mockResolvedValue({ user: { id: 'u9' } });
    const { resolveLlmCaller } = await import('@/lib/llm-caller');
    const r = await resolveLlmCaller(await signed('{}', { 'x-internal-signature': '00'.repeat(32) }), '{}');
    expect(r).toMatchObject({ status: 401 });
    expect(mocks.auth).not.toHaveBeenCalled();
  });
  it('body 被篡改：401', async () => {
    const { resolveLlmCaller } = await import('@/lib/llm-caller');
    expect(await resolveLlmCaller(await signed('{"a":1}'), '{"a":2}')).toMatchObject({ status: 401 });
  });
  it('时间戳超窗：401', async () => {
    const { resolveLlmCaller } = await import('@/lib/llm-caller');
    expect(await resolveLlmCaller(await signed('{}', { 'x-aster-timestamp': '1000' }), '{}')).toMatchObject({ status: 401 });
  });
  it('nonce 重放：401', async () => {
    mocks.claimNonce.mockResolvedValue({ ok: false, reason: 'replayed_nonce' });
    const { resolveLlmCaller } = await import('@/lib/llm-caller');
    expect(await resolveLlmCaller(await signed('{}'), '{}')).toMatchObject({ status: 401 });
  });
  it('密钥未配置：503', async () => {
    vi.stubEnv('AI_WORKSPACE_HMAC_KEY', '');
    const { resolveLlmCaller } = await import('@/lib/llm-caller');
    expect(await resolveLlmCaller(await signed('{}'), '{}')).toMatchObject({ status: 503 });
  });
});
```

(Adapt the `claimNonce` mock return shape to the real function after reading `nonce-service.ts`; the assertions stay.)

- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement `src/lib/llm-caller.ts`**

```ts
import { auth } from '@/auth';
import { hmacVerify, sha256Hex } from '@/lib/api-signing';
import { claimNonce } from '@/services/security/nonce-service';
import { safeEnv } from '@/lib/runtime/safe-env';

/**
 * /api/llm/* 的调用方解析（ADR 0049 §2.2）：浏览器会话，或 AI 工作台以服务签名代表用户调用。
 * 一旦带了服务头就必须验签成功——失败一律 401，绝不回落到 cookie 会话。
 */
export const AI_WORKSPACE_CALLER = 'ai-workspace';
const WINDOW_SEC = 300;

export type LlmCaller = { userId: string; via: 'session' | 'ai-workspace' };
type CallerError = { error: string; status: 401 | 503 };

export function canonicalAiWorkspace(method: string, path: string, ts: string, nonce: string, bodySha: string, userId: string): string {
  return [method.toUpperCase(), path, ts, nonce, bodySha, userId].join('\n');
}

export async function resolveLlmCaller(req: Request, rawBody: string): Promise<LlmCaller | CallerError> {
  if (req.headers.get('x-internal-caller') !== AI_WORKSPACE_CALLER) {
    const session = await auth();
    return session?.user?.id ? { userId: session.user.id, via: 'session' } : { error: 'unauthorized', status: 401 };
  }
  return verifyWorkspaceCaller(req, rawBody);
}

async function verifyWorkspaceCaller(req: Request, rawBody: string): Promise<LlmCaller | CallerError> {
  const key = safeEnv('AI_WORKSPACE_HMAC_KEY');
  if (!key) return { error: 'ai_workspace_unconfigured', status: 503 };
  const h = (n: string) => req.headers.get(n) ?? '';
  const ts = h('x-aster-timestamp');
  const nonce = h('x-aster-nonce');
  const userId = h('x-aster-user-id');
  const tsNum = Number(ts);
  if (!userId || !/^[0-9a-f]{32}$/.test(nonce) || !Number.isFinite(tsNum)) return { error: 'bad_signature', status: 401 };
  if (Math.abs(Date.now() / 1000 - tsNum) > WINDOW_SEC) return { error: 'stale_signature', status: 401 };
  const path = new URL(req.url).pathname;
  const canonical = canonicalAiWorkspace(req.method, path, ts, nonce, await sha256Hex(rawBody), userId);
  if (!(await hmacVerify(key, canonical, h('x-internal-signature')))) return { error: 'bad_signature', status: 401 };
  const claimed = await claimNonce(nonce, new Date((tsNum + WINDOW_SEC + 60) * 1000));
  if (!claimed.ok) return { error: 'replayed_nonce', status: 401 };
  return { userId, via: 'ai-workspace' };
}
```

(Adjust to the real `sha256Hex` sync/async signature and `claimNonce` return shape.)

- [ ] **Step 4: Wire it in**
  - `llm-sse-proxy.ts`: read `const rawBody = await req.text()` once at the top; `const caller = await resolveLlmCaller(req, rawBody)`; if it has `status`, return `NextResponse.json({ error: caller.error }, { status: caller.status })`; use `caller.userId` everywhere `session.user.id` was used; parse JSON from `rawBody` where the code used `req.json()`.
  - `complete/route.ts`: same replacement.
  - `csrf-gate.ts`: before the CSRF check, return "allowed" when `pathname.startsWith('/api/llm/')` and the request carries `x-internal-caller: ai-workspace` (comment: browsers cannot set this custom header cross-site without a CORS preflight, which cloud never grants; the route verifies the signature itself). Read nothing from env here (middleware import chain).
  - Add tests: csrf-gate allows the header on `/api/llm/suggest` and still blocks a cookie POST without Origin; sse-proxy test: a service-signed request reaches upstream with `X-Tenant-Id: u1` and records usage for `u1`.

- [ ] **Step 5:** Run (background + log) the new/changed tests, then `src/__tests__/lib/middleware-no-process.test.ts`, then the full `pnpm exec vitest run` (~9 min) → all pass; `pnpm exec tsc --noEmit`, `pnpm lint` → 0 errors.
- [ ] **Step 6:** Commit `feat(llm): /api/llm/* 接受 AI 工作台服务签名身份` (trailer).

### Task 6: CSP, navigation entry, i18n

**Files:**
- Modify: `src/lib/security/csp.ts` (`frame-src` adds `aiWorkspaceOrigin(safeEnv('NEXT_PUBLIC_AI_WORKSPACE_URL'))` when non-null, evaluated inside `buildCspHeader` like the guardian embed), `src/components/dashboard-sidebar.tsx` (+ labels interface), `src/app/[locale]/(dashboard)/layout.tsx` (pass label + `aiWorkspaceHref` only when `isAiWorkspaceEnabledFor(session.user.id, …)`), `src/lib/command-palette-commands.ts`, `src/i18n/demo-supplement.ts` (`dashboardNav.aiWorkspace`, `dashboardNav.commandPalette.aiWorkspace` in en/zh/de/hi)
- Test: extend `src/__tests__/lib/csp.test.ts`; `src/__tests__/components/dashboard-sidebar-ai-workspace.test.tsx`

**Interfaces:**
- Consumes: Task 3 helpers.
- Produces: sidebar item `{ href: '/api/ai-workspace/handoff?next=%2F&locale=<locale>', label: labels.aiWorkspace, icon: Bot }` rendered only when the layout passes `aiWorkspaceHref`; i18n texts en "AI workspace", zh "AI 工作台", de "KI-Arbeitsbereich", hi "एआई कार्यक्षेत्र".

- [ ] **Step 1: Failing tests** — csp: with `NEXT_PUBLIC_AI_WORKSPACE_URL=https://ai.aster-lang.cloud`, `frame-src` contains `https://ai.aster-lang.cloud` exactly once and `frame-ancestors 'none'` is unchanged; unset → origin absent; invalid → no throw (copy the guardian test shape with `vi.stubEnv` + `vi.resetModules()` + dynamic import). Sidebar: rendered with `aiWorkspaceHref` → link with that href and label; without → no link.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement as listed; keep every env read inside functions; add the four locale strings (hi Devanagari) to the supplement.
- [ ] **Step 4:** Tests PASS; `pnpm check:message-keys` 0 missing; `pnpm exec tsc --noEmit`; middleware-no-process test PASS.
- [ ] **Step 5:** Commit `feat(ai-workspace): CSP frame-src、导航入口与四语文案` (trailer).

### Task 7: Editor iframe panel + postMessage bridge

**Files:**
- Create: `src/lib/ai-workspace-bridge.ts`, `src/components/policy/ai-workspace-panel.tsx`
- Modify: `src/components/policy/policy-form/side-panel.tsx` (the `tab === 'ai'` branch), `src/components/policy/policy-form/index.tsx` (pass `policyId` and `aiWorkspace` props to `SidePanel`), the edit/new page wrappers or layout to compute `aiWorkspace = { base, enabled }` server-side
- Test: `src/__tests__/lib/ai-workspace-bridge.test.ts`, `src/__tests__/components/ai-workspace-panel.test.tsx`

**Interfaces:**
- Produces (bridge, pure):
  - `type BridgeIn = { type: 'aster.ai.ready'; v: 1 } | { type: 'aster.ai.apply'; v: 1; action: 'insert' | 'replace'; code: string; policyId: string; requestId: string } | { type: 'aster.ai.reauth'; v: 1 }`
  - `parseBridgeMessage(ev: { origin: string; data: unknown }, expectedOrigin: string): BridgeIn | null` — null for wrong origin, wrong `v`, unknown type, missing fields, code > 65536 chars.
  - `applyToEditor(editor: EditorLike, msg: Extract<BridgeIn, {type:'aster.ai.apply'}>, currentPolicyId: string): { ok: true } | { ok: false; reason: 'policy_mismatch' | 'no_editor' }` — insert at selection (or 1,1) via `executeEdits('ai-workspace-insert', …)`, replace via full model range (`executeEdits('ai-workspace-replace', …)`), then `focus()`.
  - `contextMessage(policyId: string, source: string, cnlLocale: string) → { type: 'aster.ai.context'; v: 1; policyId; source; cnlLocale }`.
  - `type EditorLike = { getModel(): { getFullModelRange(): unknown } | null; getSelection(): unknown | null; executeEdits(src: string, edits: { range: unknown; text: string; forceMoveMarkers?: boolean }[]): boolean; focus(): void; getValue(): string }`.
- Produces (component): `<AiWorkspacePanel base={string} policyId={string} editor={EditorLike|null} cnlLocale={string} uiLocale={string} onApplyContent={(s: string) => void} />` — iframe `src=/api/ai-workspace/handoff?next=${encodeURIComponent('/panel?policyId=' + policyId)}&locale=${uiLocale}`; on `ready` and on editor content change (debounce 500 ms) posts `contextMessage`; on `apply` calls `applyToEditor`, then `onApplyContent(editor.getValue())`, then posts `{type:'aster.ai.applied', v:1, requestId, ok, reason?}`; on `reauth` reloads the iframe `src`. `postMessage` target origin is always `aiWorkspaceOrigin(base)`.

- [ ] **Step 1: Failing bridge tests** (cover Review Focus #4):

```ts
import { describe, expect, it, vi } from 'vitest';
import { applyToEditor, contextMessage, parseBridgeMessage } from '@/lib/ai-workspace-bridge';

const ORIGIN = 'https://ai.aster-lang.cloud';
const apply = (over: object = {}) => ({ type: 'aster.ai.apply', v: 1, action: 'insert', code: 'Rule a.', policyId: 'p1', requestId: 'r1', ...over });
function editor() {
  return {
    getModel: () => ({ getFullModelRange: () => 'FULL' }),
    getSelection: () => 'SEL',
    executeEdits: vi.fn(() => true),
    focus: vi.fn(),
    getValue: () => 'Rule a.',
  };
}

describe('parseBridgeMessage', () => {
  it('只接受工作台 origin', () => {
    expect(parseBridgeMessage({ origin: 'https://evil.example', data: apply() }, ORIGIN)).toBeNull();
    expect(parseBridgeMessage({ origin: ORIGIN, data: apply() }, ORIGIN)).toMatchObject({ type: 'aster.ai.apply' });
  });
  it('版本、类型、字段不合法一律忽略', () => {
    expect(parseBridgeMessage({ origin: ORIGIN, data: apply({ v: 2 }) }, ORIGIN)).toBeNull();
    expect(parseBridgeMessage({ origin: ORIGIN, data: { type: 'aster.ai.unknown', v: 1 } }, ORIGIN)).toBeNull();
    expect(parseBridgeMessage({ origin: ORIGIN, data: apply({ action: 'delete' }) }, ORIGIN)).toBeNull();
    expect(parseBridgeMessage({ origin: ORIGIN, data: apply({ code: 'x'.repeat(65_537) }) }, ORIGIN)).toBeNull();
    expect(parseBridgeMessage({ origin: ORIGIN, data: 'string' }, ORIGIN)).toBeNull();
  });
});

describe('applyToEditor', () => {
  it('insert 写在选区', () => {
    const ed = editor();
    expect(applyToEditor(ed, apply() as never, 'p1')).toEqual({ ok: true });
    expect(ed.executeEdits).toHaveBeenCalledWith('ai-workspace-insert', [{ range: 'SEL', text: 'Rule a.', forceMoveMarkers: true }]);
  });
  it('replace 覆盖全文', () => {
    const ed = editor();
    applyToEditor(ed, apply({ action: 'replace' }) as never, 'p1');
    expect(ed.executeEdits).toHaveBeenCalledWith('ai-workspace-replace', [{ range: 'FULL', text: 'Rule a.' }]);
  });
  it('policyId 不一致：不改编辑器', () => {
    const ed = editor();
    expect(applyToEditor(ed, apply({ policyId: 'other' }) as never, 'p1')).toEqual({ ok: false, reason: 'policy_mismatch' });
    expect(ed.executeEdits).not.toHaveBeenCalled();
  });
});

describe('contextMessage', () => {
  it('形状固定', () => {
    expect(contextMessage('p1', 'src', 'zh')).toEqual({ type: 'aster.ai.context', v: 1, policyId: 'p1', source: 'src', cnlLocale: 'zh' });
  });
});
```

- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement `src/lib/ai-workspace-bridge.ts`**

```ts
/**
 * 策略编辑器与 AI 工作台 iframe 之间的 postMessage 协议（ADR 0049 §2.5）。
 * 纯函数，便于测试；只认工作台 origin 与当前策略 id，其余消息一律丢弃。
 */
export type ApplyMessage = { type: 'aster.ai.apply'; v: 1; action: 'insert' | 'replace'; code: string; policyId: string; requestId: string };
export type BridgeIn = { type: 'aster.ai.ready'; v: 1 } | ApplyMessage | { type: 'aster.ai.reauth'; v: 1 };

export type EditorLike = {
  getModel(): { getFullModelRange(): unknown } | null;
  getSelection(): unknown | null;
  executeEdits(src: string, edits: { range: unknown; text: string; forceMoveMarkers?: boolean }[]): boolean;
  focus(): void;
  getValue(): string;
};

const MAX_CODE = 65_536;
const isStr = (v: unknown, max = 256): v is string => typeof v === 'string' && v.length > 0 && v.length <= max;

function parseApply(d: Record<string, unknown>): ApplyMessage | null {
  const okAction = d.action === 'insert' || d.action === 'replace';
  const ok = okAction && typeof d.code === 'string' && d.code.length <= MAX_CODE && isStr(d.policyId, 64) && isStr(d.requestId, 64);
  return ok ? (d as unknown as ApplyMessage) : null;
}

export function parseBridgeMessage(ev: { origin: string; data: unknown }, expectedOrigin: string): BridgeIn | null {
  if (ev.origin !== expectedOrigin || typeof ev.data !== 'object' || ev.data === null) return null;
  const d = ev.data as Record<string, unknown>;
  if (d.v !== 1) return null;
  if (d.type === 'aster.ai.ready' || d.type === 'aster.ai.reauth') return { type: d.type, v: 1 };
  return d.type === 'aster.ai.apply' ? parseApply(d) : null;
}

export function applyToEditor(editor: EditorLike | null, msg: ApplyMessage, currentPolicyId: string): { ok: true } | { ok: false; reason: 'policy_mismatch' | 'no_editor' } {
  if (msg.policyId !== currentPolicyId) return { ok: false, reason: 'policy_mismatch' };
  const model = editor?.getModel();
  if (!editor || !model) return { ok: false, reason: 'no_editor' };
  if (msg.action === 'replace') {
    editor.executeEdits('ai-workspace-replace', [{ range: model.getFullModelRange(), text: msg.code }]);
  } else {
    const range = editor.getSelection() ?? { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1 };
    editor.executeEdits('ai-workspace-insert', [{ range, text: msg.code, forceMoveMarkers: true }]);
  }
  editor.focus();
  return { ok: true };
}

export function contextMessage(policyId: string, source: string, cnlLocale: string) {
  return { type: 'aster.ai.context' as const, v: 1 as const, policyId, source, cnlLocale };
}
```

- [ ] **Step 4: Implement the component** — `ai-workspace-panel.tsx` (`'use client'`): an `<iframe title=… className="h-full w-full border-0" src=…>`; a `useEffect` registering `window.addEventListener('message', …)` → `parseBridgeMessage(ev, origin)`; on `ready` post context; on `apply` → `applyToEditor` → `onApplyContent(editor.getValue())` when ok → post `aster.ai.applied`; on `reauth` → reset `src` (key bump). A second `useEffect` subscribes `editor.onDidChangeModelContent` (debounce 500 ms) to post context. New policies without an id use `policyId = 'new'`. In `side-panel.tsx`, the `ai` branch renders `<AiWorkspacePanel …>` when `aiWorkspace?.enabled`, else the existing `<AIAssistantPanel …>` unchanged. Component test: renders iframe with the handoff src; dispatching a `MessageEvent` from the right origin with an apply calls `executeEdits` and posts `aster.ai.applied` to `contentWindow` (mock `postMessage`); a foreign-origin message does nothing.
- [ ] **Step 5:** Tests PASS; full vitest (background) PASS; tsc/lint clean.
- [ ] **Step 6:** Commit `feat(ai-workspace): 编辑器 iframe 侧栏与 postMessage 写回桥` (trailer).

---

## Part C — `aster-ai-workspace` service (new repo, local `/Users/rpang/IdeaProjects/aster-ai-workspace`)

Creating the GitHub repo `wontlost-ltd/aster-ai-workspace` is a HUMAN STEP (Task 16). Until then, work in a local git repo on branch `main` → feature branch `feat/initial`.

### Task 8: Scaffold Spring Boot 4 + Vaadin 25 app

**Files:**
- Create: `pom.xml`, `src/main/java/com/wontlost/aster/aiw/AiWorkspaceApplication.java`, `src/main/java/com/wontlost/aster/aiw/config/AiwProperties.java`, `src/main/java/com/wontlost/aster/aiw/web/FrameAncestorsFilter.java`, `src/main/resources/application.yml`, `src/test/resources/application-test.yml`, `.gitignore`, `README.md` (Chinese, how to run locally)
- Test: `src/test/java/com/wontlost/aster/aiw/ApplicationContextTest.java`, `src/test/java/com/wontlost/aster/aiw/web/FrameAncestorsFilterTest.java`

**Interfaces:**
- Produces: `@ConfigurationProperties("aiw") record AiwProperties(String cloudUrl, String cloudOrigin, String hmacKey, String handoffPublicKey, String ticketKey, int retentionDays)`; env mapping `AIW_CLOUD_URL`, `AIW_CLOUD_ORIGIN`, `AIW_HMAC_KEY`, `AIW_HANDOFF_PUBLIC_KEY`, `AIW_TICKET_KEY`, `AIW_RETENTION_DAYS` (default 180).
- Produces: every HTTP response carries `Content-Security-Policy: frame-ancestors <cloudOrigin>` (overriding Vaadin's defaults; no `X-Frame-Options: DENY`).

- [ ] **Step 1: `pom.xml`** — parent `org.springframework.boot:spring-boot-starter-parent:4.1.1`; `java.version` 21; BOM `com.vaadin:vaadin-bom:25.3.0`; dependencies: `com.vaadin:vaadin-spring-boot-starter`, `com.wontlost:agui-vaadin:0.2.0`, `com.wontlost:agui-pro-conversations:0.1.2`, `com.wontlost:agui-pro-audit:0.1.2`, `com.wontlost:agui-ee-governance:0.1.2`, `com.wontlost:agui-ee-observability:0.1.2`, `org.springframework.boot:spring-boot-starter-actuator`, `org.springframework.boot:spring-boot-starter-jdbc`, `org.postgresql:postgresql`, `io.micrometer:micrometer-registry-prometheus`; test: `spring-boot-starter-test`, `com.h2database:h2`. Plugins: `spring-boot-maven-plugin`; profile `production` with `vaadin-maven-plugin` goals `prepare-frontend`, `build-frontend` and `vaadin.productionMode=true`.
- [ ] **Step 2: Failing tests**

```java
// ApplicationContextTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationContextTest {
    @Autowired TestRestTemplate http;

    @Test
    void healthIsUp() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health/readiness", String.class);
        assertEquals(200, r.getStatusCode().value());
    }
}
```

```java
// FrameAncestorsFilterTest.java
class FrameAncestorsFilterTest {
    @Test
    void setsFrameAncestorsToCloudOriginAndDropsDeny() throws Exception {
        var filter = new FrameAncestorsFilter("https://aster-lang.cloud");
        var req = new MockHttpServletRequest("GET", "/panel");
        var res = new MockHttpServletResponse();
        res.setHeader("X-Frame-Options", "DENY");
        filter.doFilter(req, res, (rq, rs) -> {});
        assertEquals("frame-ancestors https://aster-lang.cloud", res.getHeader("Content-Security-Policy"));
        assertNull(res.getHeader("X-Frame-Options"));
    }
}
```

`application-test.yml`: H2 `jdbc:h2:mem:aiw;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`, `aiw.*` dummy values, `vaadin.productionMode=false`, `vaadin.frontend.hotdeploy=false`, `agui.pro.license` unset (dev trial).

- [ ] **Step 3: Implement** — `AiWorkspaceApplication` (`@SpringBootApplication @EnableConfigurationProperties(AiwProperties.class) @EnableScheduling`); `FrameAncestorsFilter extends OncePerRequestFilter` that wraps the response so `X-Frame-Options` is never written and sets the CSP header before and after `chain.doFilter` (Vaadin may set headers late — use a `HttpServletResponseWrapper` that drops `X-Frame-Options` in `setHeader`/`addHeader`); registered as a `@Bean FilterRegistrationBean` at highest precedence. `application.yml`:

```yaml
server:
  port: 8080
  forward-headers-strategy: framework
spring:
  datasource:
    url: ${AIW_DB_URL:jdbc:postgresql://localhost:5432/aster_cloud?currentSchema=ai_workspace}
    username: ${AIW_DB_USERNAME:aster_ai_user}
    password: ${AIW_DB_PASSWORD:}
    hikari:
      maximum-pool-size: 5
agui:
  path: /agui
  timeout: 10m
  pro:
    license: ${AGUI_PRO_LICENSE:}
  ee:
    governance:
      retention:
        conversations-days: ${AIW_RETENTION_DAYS:180}
        audit-days: ${AIW_RETENTION_DAYS:180}
        cron: "0 17 3 * * *"
aiw:
  cloud-url: ${AIW_CLOUD_URL:https://aster-lang.cloud}
  cloud-origin: ${AIW_CLOUD_ORIGIN:https://aster-lang.cloud}
  hmac-key: ${AIW_HMAC_KEY:}
  handoff-public-key: ${AIW_HANDOFF_PUBLIC_KEY:}
  ticket-key: ${AIW_TICKET_KEY:}
  retention-days: ${AIW_RETENTION_DAYS:180}
management:
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.probes.enabled: true
vaadin:
  launch-browser: false
```

- [ ] **Step 4:** `mvn -B -ntp verify` (background) → both tests PASS.
- [ ] **Step 5:** Commit `feat: 初始化 aster-ai-workspace（Spring Boot 4 + Vaadin 25 + agui）` (trailer).

### Task 9: `AsterFrameTranslator` (cloud SSE → AG-UI events)

**Files:**
- Create: `src/main/java/com/wontlost/aster/aiw/agent/AsterFrameTranslator.java`, `src/main/java/com/wontlost/aster/aiw/agent/SseFrameReader.java`
- Test: `src/test/java/com/wontlost/aster/aiw/agent/AsterFrameTranslatorTest.java`, `SseFrameReaderTest.java`

**Interfaces:**
- Produces: `final class SseFrameReader { static List<JsonNode> parseFrames(String chunk, StringBuilder carry) }` — splits on blank lines (`\r?\n\r?\n`), joins `data:` lines, JSON-parses; non-JSON payloads become `{"type":"delta","data":<text>}`; incomplete tail stays in `carry`.
- Produces: `final class AsterFrameTranslator { AsterFrameTranslator(List<Message> history, Supplier<String> idGen); List<AgUiEvent> onFrame(JsonNode frame); List<AgUiEvent> finish(); }` implementing ADR 0049 §2.3:
  - `meta` → `Custom("aster.model", TextNode(data))`; `upstream_reached` → nothing.
  - `delta` → `TextMessageStart(id)` once per message, then `TextMessageContent(id, data)`; buffer appended to `current`.
  - `repair_start` → `TextMessageEnd(current)` if open, reset, then `StepStarted("repair " + data)`.
  - `validation_error` → `StateDelta([{op:add,path:/validationError,value:<error|data>}])`.
  - `final` → `TextMessageEnd(current)` if open; `MessagesSnapshot(history + assistant(id, data))`; `StateDelta([{op:add,path:/validated,value:<validated>}])`; remembers `finalized=true`.
  - `error` → `TextMessageEnd` if open, then `RunError(message ?? error, error)` (code = `error` field; message = `message` field when present else the code).
  - `finish()` → `TextMessageEnd(current)` if a message is still open; nothing else (RUN_FINISHED is emitted by the agent).

- [ ] **Step 1: Failing tests** — one test per row of the mapping table plus: two deltas share one start; repair produces end + step + new start with a new id; `final` snapshot equals `history ++ [assistant(final text)]`; `error` with only `error` field uses it as message; `finish()` closes an open message; `SseFrameReader` handles a frame split across chunks, CRLF, `event:` + `data:` lines, and a non-JSON data payload. Example:

```java
@Test
void deltasShareOneMessageAndFinalSnapshots() {
    var ids = new java.util.ArrayDeque<>(List.of("m1", "m2"));
    var t = new AsterFrameTranslator(List.of(Message.user("hi")), ids::pop);
    var ev = new ArrayList<AgUiEvent>();
    ev.addAll(t.onFrame(json("{\"type\":\"delta\",\"data\":\"Rule \"}")));
    ev.addAll(t.onFrame(json("{\"type\":\"delta\",\"data\":\"a.\"}")));
    ev.addAll(t.onFrame(json("{\"type\":\"final\",\"data\":\"Rule a.\",\"validated\":true}")));
    assertInstanceOf(AgUiEvent.TextMessageStart.class, ev.get(0));
    assertEquals("m1", ((AgUiEvent.TextMessageContent) ev.get(2)).messageId());
    assertInstanceOf(AgUiEvent.TextMessageEnd.class, ev.get(3));
    var snap = (AgUiEvent.MessagesSnapshot) ev.get(4);
    assertEquals(2, snap.messages().size());
    assertEquals("Rule a.", snap.messages().get(1).content());
    var delta = (AgUiEvent.StateDelta) ev.get(5);
    assertEquals("/validated", delta.delta().get(0).get("path").asString());
}
```

(Record accessor names follow vaadin-agui `AgUiEvent` records — verify against `src/main/java/com/wontlost/agui/event/AgUiEvent.java` in the jar sources; Jackson 3 `JsonNode#asString()`.)

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (one small private method per frame type to stay ≤ 3 indentation levels; Chinese intent comments). **Step 4:** PASS. **Step 5:** Commit `feat(agent): cloud SSE 帧到 AG-UI 事件的翻译器` (trailer).

### Task 10: `CloudLlmClient`, request signer, `AsterAgent`

**Files:**
- Create: `src/main/java/com/wontlost/aster/aiw/agent/CloudRequestSigner.java`, `CloudLlmClient.java`, `AsterAgent.java`, `RunRequestBuilder.java`, `src/main/java/com/wontlost/aster/aiw/i18n/ErrorTexts.java`, `src/main/resources/i18n/errors_{en,zh,de,hi}.properties`
- Test: `CloudRequestSignerTest.java`, `RunRequestBuilderTest.java`, `AsterAgentTest.java`

**Interfaces:**
- Consumes: Task 9 translator; Task 11 `UserTicket.verify(String) → Optional<TicketUser>` (define `record TicketUser(String userId, String locale)` in Task 10 under `com.wontlost.aster.aiw.auth` with a stub verifier interface `TicketVerifier { Optional<TicketUser> verify(String ticket); }`, implemented in Task 11).
- Produces:
  - `CloudRequestSigner(String hmacKey, Clock clock, Supplier<String> nonceGen)`; `Map<String,String> headers(String method, String path, String body, String userId)` → the 5 headers of Global Constraints; canonical identical to cloud `canonicalAiWorkspace`. Shared fixture test: fixed key `"k".repeat(64)`, ts `1800000000`, nonce `"a".repeat(32)`, body `{"source":"x"}`, user `u1` → assert the exact hex signature, and copy the same vector into cloud's `llm-caller.test.ts` (Task 5) as an extra test.
  - `RunRequestBuilder.build(RunAgentInput in, TicketUser user) → Either<CloudCall, RunError>` where `record CloudCall(String path, String jsonBody)`; modes from `forwardedProps.mode`: `generate` → `/api/llm/generate` `{goal: lastUserText, locale, existingSource: ctx("policy.source")}`; `suggest` → `/api/llm/suggest` `{source: ctx("policy.source"), locale, focus: lastUserText (≤512, may be blank)}`; `ask` (default) → `/api/llm/assistant` `{query: lastUserText (≤2048), groundingHits: [], locale}`. Locale is the CNL locale from context item `policy.cnlLocale` when present, else the user's UI locale mapped (`zh`→`zh-CN`, `de`→`de-DE`, `hi`→`hi-IN`, `en`→`en-US`). Oversized source (> 65536 chars) or goal/query beyond limits → `RunError(ErrorTexts.get(locale,"source_too_large"), "source_too_large")` without calling cloud.
  - `CloudLlmClient(HttpClient http, String baseUrl, CloudRequestSigner signer)`; `Stream<String> stream(CloudCall call, String userId, BooleanSupplier cancelled) throws CloudDenied` — POST with `Accept: text/event-stream`, `Content-Type: application/json`; non-2xx → `CloudDenied(int status, String code, String message)` parsed from JSON `{error, message}` (fallback code `http_<status>`).
  - `@Component("aster") AsterAgent implements AgUiAgent` — `run(input, emitter)`: verify `forwardedProps.ticket` (absent/invalid → `RunError(…,"session_expired")`); emit `RunStarted(threadId, runId)`; build call; stream through translator; on `CloudDenied` → `RunError(ErrorTexts.get(locale, code) ?? message, code)`; on IO failure → `RunError(ErrorTexts.get(locale,"network"),"network")`; always end with `RunFinished(threadId, runId)` unless an error was emitted (then `emitter.complete()`); honour `emitter.isCancelled()` / `onCancel` by closing the response stream.
  - Metrics (ADR §2.7 observability): `AsterAgent` takes a `MeterRegistry` and increments `aiw.runs{mode=<generate|suggest|ask>, outcome=<ok|error>, code=<error code or none>}` once per run; `AsterAgentTest` asserts the counter for the happy path (`outcome=ok`) and the quota denial (`outcome=error, code=ai_quota_exhausted`) using a `SimpleMeterRegistry`.
  - `ErrorTexts.get(String uiLocale, String code) → String` with keys: `network`, `session_expired`, `source_too_large`, `ai_quota_exhausted`, `ai_email_unverified`, `ai_rate_limited`, `ai_banned`, `byok_unavailable`, `unauthorized`, `provider_auth_failed`, `provider_rejected_model`, `provider_bad_request`, `provider_rate_limited`, `provider_unavailable`, `out_of_scope`; unknown code → `null` (caller falls back to cloud's message). Four bundles (hi in Devanagari).

- [ ] **Step 1: Failing tests** — `AsterAgentTest` runs the agent against an in-process `com.sun.net.httpserver.HttpServer` stub and a recording `AgUiEmitter`:
  - happy path: stub returns `data:{"type":"meta","data":"claude-opus-5-5"}\n\ndata:{"type":"upstream_reached"}\n\ndata:{"type":"delta","data":"Hi"}\n\ndata:{"type":"final","data":"Hi","validated":false}\n\n` → events start with `RunStarted`, contain `Custom aster.model`, text events, snapshot, and end with `RunFinished`; the stub saw `X-Internal-Caller: ai-workspace`, `X-Aster-User-Id: u1`, a valid signature, and body `{"query":"hello","groundingHits":[],"locale":"en-US"}` for mode ask.
  - `denialBecomesLocalizedRunError` (Review Focus #2): stub 429 `{"error":"ai_quota_exhausted","message":"Quota exceeded"}` with ticket locale `zh` → single `RunError` whose `code` is `ai_quota_exhausted` and message is the zh text from `errors_zh.properties`; no `RunFinished` text events.
  - `oversizedSourceIsRejectedBeforeCallingCloud` (Review Focus #3): mode suggest with a 70 000-char `policy.source` → `RunError code=source_too_large`, stub received 0 requests.
  - invalid ticket → `RunError session_expired`, 0 requests.
  - provider error frame `{"type":"error","error":"provider_bad_request","message":"`temperature` is deprecated"}` → `RunError(code provider_bad_request, message = ErrorTexts(en) + "\n" + provider message)` (concatenate when both exist).
  - cancellation: emitter cancelled after first delta → client stream closed, no exception.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (Java `HttpClient.newHttpClient()` bean; `BodyHandlers.ofLines()`; feed lines through `SseFrameReader`; small methods). **Step 4:** PASS (`mvn -B -ntp verify`, background). **Step 5:** Commit `feat(agent): AsterAgent 经 cloud /api/llm 代用户调用并翻译为 AG-UI` (trailer).

### Task 11: Handoff verification, session user, user ticket

**Files:**
- Create: `src/main/java/com/wontlost/aster/aiw/auth/HandoffTokenVerifier.java`, `HandoffJtiStore.java`, `HandoffController.java`, `SessionUser.java`, `UserTicket.java` (implements `TicketVerifier`), `src/main/resources/schema-aiw.sql`
- Test: `HandoffTokenVerifierTest.java`, `HandoffControllerTest.java`, `UserTicketTest.java`, `HandoffJtiStoreTest.java`

**Interfaces:**
- Produces: `record AiwUser(String userId, String name, String email, String locale, String role) implements Serializable`; `SessionUser { static Optional<AiwUser> current(); static void set(HttpSession s, AiwUser u); }` (session attribute `aiw.user`).
- Produces: `HandoffTokenVerifier(String publicKeyB64, Clock clock)`; `Optional<HandoffClaims> verify(String token)` — checks Ed25519 signature over the ASCII payload part, `v==1`, `aud=="ai-workspace"`, `now <= exp` (allow 5 s skew), returns claims; `record HandoffClaims(String sub, String name, String email, String locale, String role, long exp, String jti)`.
- Produces: `HandoffJtiStore(JdbcTemplate)`; `boolean claim(String jti, Instant expiresAt)` — `INSERT` into `aiw_handoff_jti(jti varchar(64) primary key, expires_at timestamp not null)`; duplicate key → false; `@Scheduled(fixedDelay=600000) purgeExpired()`. Table created by `schema-aiw.sql` run at startup (`spring.sql.init.mode=always`, `spring.sql.init.schema-locations=classpath:schema-aiw.sql`, `CREATE TABLE IF NOT EXISTS`).
- Produces: `GET /handoff?token=&next=` → valid & fresh & first use: `SessionUser.set(session, …)`, `session.changeSessionId()` (fixation), 302 `next` (must match `^/(panel(\?policyId=[A-Za-z0-9_-]{1,64})?)?$`, else `/`); expired / replayed / bad signature: 302 to `${cloudUrl}/api/ai-workspace/handoff?next=<next>&locale=<locale from claims or en>` (re-handoff, Review Focus #1).
- Produces: `UserTicket(String ticketKey, Clock clock)`: `String issue(AiwUser u)` → `base64url("userId|locale|exp") + "." + hexHmac` (TTL 12 h); `Optional<TicketUser> verify(String ticket)`.
- Produces: views call `SessionUser.current()`; absent → `UI.getCurrent().getPage().setLocation(cloudUrl + "/api/ai-workspace/handoff?next=" + encode(currentPath) + "&locale=en")` (done in Task 12's `BeforeEnterObserver`).

- [ ] **Step 1: Failing tests** — verifier: valid token (signed in-test with a generated Ed25519 key pair via `KeyPairGenerator.getInstance("Ed25519")`, payload built exactly like cloud's) → claims; tampered payload → empty; wrong audience → empty; expired → empty. Cross-check fixture: a token signed by cloud's `signHandoffToken` in Node with a fixed key (generate once, paste `PKCS#8` private key and `X.509` public key base64 plus one token with `exp` far in the future into both test suites) verifies in Java. Controller (MockMvc): valid → 302 to `/panel?policyId=p-1` with session attribute set; `expiredTokenRedirectsToCloudHandoff` and `replayedTokenRedirectsToCloudHandoff` → 302 to `https://aster-lang.cloud/api/ai-workspace/handoff?next=%2Fpanel%3FpolicyId%3Dp-1&locale=zh`; bad `next` → `/`. Ticket: issue→verify round trip; tampered → empty; expired → empty. JtiStore on H2: first claim true, second false, purge removes expired.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS (`mvn verify`, background). **Step 5:** Commit `feat(auth): 交接令牌验证、一次性 jti、会话用户与 run ticket` (trailer).

### Task 12: Views, Pro wiring with license gate, i18n, panel bridge

**Files:**
- Create: `src/main/java/com/wontlost/aster/aiw/pro/ProFeatures.java`, `src/main/java/com/wontlost/aster/aiw/ui/ChatFactory.java`, `HistoryBinding.java`, `SessionHistory.java`, `WorkspaceView.java` (`@Route("")`), `PanelView.java` (`@Route("panel")`), `ModeSelector.java`, `src/main/java/com/wontlost/aster/aiw/i18n/AiwI18nProvider.java`, `src/main/resources/i18n/ui_{en,zh,de,hi}.properties`, `src/main/frontend/aster-bridge.ts`
- Test: `ProFeaturesTest.java`, `HistoryBindingTest.java`, `ChatFactoryTest.java`, `PanelBridgeContractTest.java`

**Interfaces:**
- Consumes: `AiwUser`, `SessionUser`, `UserTicket`, `AiwProperties`, `ConversationStore`, `AuditTrail`, `AgUiChat`, `AgUiCodeAction`, `ConversationBinder`, `AuditBinder`, `ConversationSidebar`.
- Produces: `ProFeatures` bean: `boolean enabled(String feature)` = `License.status().valid() && License.status().key().map(k -> k.covers(feature)).orElse(false)`; constructor-injectable `Supplier<License.Status>` for tests.
- Produces: `HistoryBinding.bind(AgUiChat chat, AiwUser user, String threadId, ProFeatures pro, ConversationStore store, AuditTrail trail)` — if `pro.enabled("conversations")`: `ConversationBinder.bind(chat, store, new ConversationScope(user.userId(), user.userId()), () -> "aster")` and `.restore(threadId)` when it exists; if `pro.enabled("audit")`: `AuditBinder.bind(chat, trail, user.userId(), user::userId, () -> "aster")`; else `SessionHistory` (VaadinSession attribute `aiw.history` = `Map<String, List<Message>>`): restore on bind via `chat.setMessages`, save on `RunFinishedEvent`.
- Produces (ADR §2.7 "数据库不可用"): `ResilientConversationStore implements ConversationStore` decorating the real store — every write method catches `RuntimeException` (incl. `DataAccessException`), logs WARN, and invokes `Runnable onSaveFailed`; reads that fail return empty results. `HistoryBinding` passes a callback that shows a Vaadin `Notification` (`ui.saveFailed`: en "This conversation could not be saved", zh "本次对话未能保存", de "Diese Unterhaltung konnte nicht gespeichert werden", hi "यह बातचीत सहेजी नहीं जा सकी") via `ui.access`; the chat keeps working.
- Produces: `ChatFactory.create(AiwUser user, Mode mode, String policyId, boolean compact)` → `AgUiChat("/agui/aster")` with `setI18n` (from `ui_<locale>.properties`), `setCodeActions(COPY, INSERT, REPLACE)` in panel mode (`COPY` only in workspace), `setForwardedProps({mode, policyId, ticket})`, `addThemeVariants(COMPACT)` when compact, `setSuggestions` per mode.
- Produces: `PanelView` — reads `policyId` query param (`[A-Za-z0-9_-]{1,64}`, else `new`), thread `policy:{policyId}`, `ModeSelector` (Generate / Suggest / Ask, default Suggest), `@JsModule("./aster-bridge.ts")` + `getElement().executeJs("window.asterBridge.init($0, $1, $2)", chat, cloudOrigin, policyId)`; mode change updates forwardedProps.
- Produces: `aster-bridge.ts` (`window.asterBridge.init(chatEl, cloudOrigin, policyId)`): posts `{type:'aster.ai.ready', v:1}` to `window.parent` with target `cloudOrigin`; on `message` from `cloudOrigin` with `aster.ai.context` and matching `policyId` → sets `chatEl.context = [{description:'policy.source', value: source}, {description:'policy.cnlLocale', value: cnlLocale}, {description:'policy.id', value: policyId}]`; on chat `agui-code-action` with `insert`/`replace` → posts `{type:'aster.ai.apply', v:1, action, code, policyId, requestId: crypto.randomUUID()}`; on `agui-run-error` with `code === 'session_expired'` → posts `{type:'aster.ai.reauth', v:1}`.
- Produces: `WorkspaceView` — `ConversationSidebar` (when Pro conversations enabled; otherwise a session-history list) + chat (`ModeSelector` default Ask; generate/suggest available with a "paste policy" text area that sets `policy.source` context), header shows user name and a "Back to Aster Cloud" link to `cloudUrl`.
- Both views implement `BeforeEnterObserver`: no `SessionUser` → redirect to cloud handoff (Task 11 contract).
- `AiwI18nProvider implements I18NProvider` over `ui_*.properties`; `UI` locale set from `AiwUser.locale()`.

- [ ] **Step 1: Failing tests** (Review Focus #5):

```java
class ProFeaturesTest {
    @Test
    void enabledOnlyWithValidCoveringKey() {
        var key = new LicenseKey("Aster", "agui-vaadin-pro", Set.of("conversations"), 5, Instant.now(), LocalDate.now().plusYears(1));
        assertTrue(new ProFeatures(() -> new License.Status(Optional.of(key), Optional.empty())).enabled("conversations"));
        assertFalse(new ProFeatures(() -> new License.Status(Optional.of(key), Optional.empty())).enabled("audit"));
        assertFalse(new ProFeatures(() -> new License.Status(Optional.empty(), Optional.of("no key"))).enabled("conversations"));
    }
}
```

(Construct `License.Status` / `LicenseKey` per their real public constructors in agui-pro-license; if `Status` has no public constructor, inject a `Predicate<String>` instead and test `ProFeatures.fromLicense()` separately.)

`HistoryBindingTest` (no Spring, like Pro's `ConversationBinderTest`): with Pro disabled, firing `new AgUiChat.RunFinishedEvent(chat, true, "t1", "r1", false, messagesArray)` stores into a fresh `SessionHistory`, and a second `bind` on a new chat restores those messages (`chat.getMessages()`); with Pro enabled (fake `ProFeatures` true, `InMemoryConversationStore`) the conversation lands in the store. `ResilientConversationStoreTest`: a delegate whose `save` throws `DataAccessResourceFailureException` → no exception escapes, `onSaveFailed` ran once; a delegate whose `list` throws → empty list. `ChatFactoryTest`: panel mode → `codeActions` contains insert/replace, forwardedProps has `mode`, `policyId`, non-empty `ticket`, theme `compact`; workspace → copy only. `PanelBridgeContractTest`: reads `src/main/frontend/aster-bridge.ts` as text and asserts it contains the five literal message types and `v: 1`, and never calls `postMessage(` with `'*'` (guards the origin rule).

- [ ] **Step 2:** FAIL → **Step 3:** implement (small classes, Chinese intent comments, ≤ 3 indentation levels) and the four `ui_*.properties` (all `AgUiChatI18n` fields incl. copyCode/insertCode/replaceCode, sidebar labels, mode labels, workspace header, "Back to Aster Cloud", paste-policy label). → **Step 4:** `mvn -B -ntp verify` PASS; `mvn -Pproduction -DskipTests package` builds the frontend bundle (background, log). **Step 5:** Commit `feat(ui): 工作台与编辑器侧栏视图、Pro 授权闸门、四语界面与 iframe 桥` (trailer).

### Task 13: Governance redaction pattern, retention, observability, audit explorer route

**Files:**
- Create: `src/main/java/com/wontlost/aster/aiw/pro/GovernanceConfig.java`, `src/main/java/com/wontlost/aster/aiw/ui/AuditView.java` (`@Route("audit")`, visible only to role `owner|admin` and when `pro.enabled("audit")`)
- Test: `GovernanceConfigTest.java` (ApplicationContextRunner), `AuditViewAccessTest.java`

**Interfaces:**
- Produces: `@Bean Redactor redactor()` = `PatternRedactor.builder().email().phone().card().iban().ipv4().pattern("apikey", "\\b(?:sk|ak|gsk|xai)[-_][A-Za-z0-9_-]{16,}\\b", "[key]").build()` (overrides the auto-configured default; governance auto-config wraps stores with it).
- Retention from `agui.ee.governance.retention.*` (Task 8 yml) runs on the cron; observability is on by default (metrics `agui.run`, `agui.events`, … exposed via `/actuator/prometheus`).

- [ ] **Step 1: Failing tests** — context runner with `GovernanceAutoConfiguration` + `GovernanceConfig`: `Redactor` redacts `sk-ant-abcdefghijklmnopqrstu` → `[key]` and an email → `[email]`; `RetentionPolicy` reports 180 days for conversations and audit; `AuditView` for role `member` redirects to `/`.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS → **Step 5:** Commit `feat(pro): 治理脱敏（含类 Key 模式）、180 天保留、可观测与审计视图` (trailer).

### Task 14: Container image and CI/deploy workflows

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `.github/workflows/ci.yml`, `.github/workflows/deploy.yml`, `scripts/ci/open-image-pin-pr.sh` (copied from aster-api `scripts/ci/open-image-pin-pr.sh`, defaults `LOCK_PATH=apps/aster-lang/ai/image-lock.yaml`, `KUSTOMIZATION_PATH=apps/aster-lang/ai/kustomization.yaml`), `scripts/ci/pin-closure-guard.sh` (copied from aster-api)
- Test: `docker buildx build --platform linux/arm64 --load -t aster-ai-workspace:local .` locally (background) and a container smoke run.

**Interfaces:**
- Produces: image `docker.io/wontlost/aster-ai-workspace`, tags `<sha7>` and `latest`; runs as uid 1001 with read-only root FS (writes only `/tmp`); `JAVA_OPTS` default `-Xms128m -Xmx448m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Djava.io.tmpdir=/tmp`; port 8080.

- [ ] **Step 1: `Dockerfile`**

```dockerfile
# 构建阶段：Maven + Node（Vaadin 生产前端打包）
FROM maven:3.9-eclipse-temurin-21 AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src src
RUN mvn -B -ntp -Pproduction -DskipTests package

# 运行阶段：只读根文件系统 + 非 root（uid 1001 由 Pod securityContext 指定）
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/aster-ai-workspace-*.jar /app/app.jar
ENV JAVA_OPTS="-Xms128m -Xmx448m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Djava.io.tmpdir=/tmp" MALLOC_ARENA_MAX=2
EXPOSE 8080
USER 1001
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

- [ ] **Step 2:** `ci.yml`: on PR/push → `mvn -B -ntp verify` (Java 21 temurin). `deploy.yml`: on push to main → same build-and-test job; `deploy` job (environment production, `id-token: write`) mirroring aster-api's `deploy.yml`: stale-commit guard, buildx `platforms: linux/arm64`, push `wontlost/aster-ai-workspace:<sha7>` + `:latest`, arm64 assertion, `cosign sign` keyless + `cosign verify` with identity `…/aster-ai-workspace/.github/workflows/deploy.yml@refs/heads/main`; `image-pin-pr` job running `bash scripts/ci/open-image-pin-pr.sh docker.io/wontlost/aster-ai-workspace image-pin/aster-ai-workspace`; `pin-closure-guard` job. Secrets referenced: `DOCKER_USERNAME`, `DOCKER_PASSWORD`, `CROSS_REPO_TOKEN`, `IMAGE_PIN_APP_PRIVATE_KEY`, var `IMAGE_PIN_APP_CLIENT_ID` (provisioned in Task 16).
- [ ] **Step 3:** Local build (background) → image exists; smoke: `podman run --rm -d --read-only --tmpfs /tmp -p 18080:8080 -e SPRING_PROFILES_ACTIVE=test aster-ai-workspace:local`, poll `curl -fs localhost:18080/actuator/health/readiness` → 200; stop by container id.
- [ ] **Step 4:** Commit `build: arm64 镜像与 CI / 部署 / image-pin 工作流` (trailer).

---

## Part D — Deployment (repo `/Users/rpang/IdeaProjects/k3s`)

Worktree: `git -C /Users/rpang/IdeaProjects/k3s worktree add -b feat/aster-ai /Users/rpang/IdeaProjects/k3s-aster-ai origin/main`.

### Task 15: k3s manifests and registrations

**Files:**
- Create: `apps/aster-lang/ai/{namespace,deployment,service,ingress,pdb,external-secrets,image-lock,kustomization}.yaml`, `apps/infrastructure/policy-controller/policies/cluster-image-policy-aster-ai-workspace.yaml`, `…-aster-ai-workspace-reject-tag.yaml`
- Modify: `argocd/projects/aster-lang.yaml` (destinations + `aster-ai`), `.github/image-pin/apps.yaml` (lane `ai`), `apps/infrastructure/policy-controller/policies/kustomization.yaml`, `apps/infrastructure/cert-manager/wildcard-certificates.yaml` (add `aster-ai` to `aster-lang-cloud-tls` reflection-allowed namespaces), `apps/infrastructure/postgres-cluster/manifests/cluster.yaml` (`managed.roles` + `aster_ai_user`, conn limit 10), `apps/infrastructure/postgres-cluster/manifests/external-secrets.yaml` (role password secret: type `kubernetes.io/basic-auth`, label `cnpg.io/reload: "true"`, `mergePolicy: Merge`, `username: aster_ai_user`, Vault `data-services/aster-ai-db`), `docs/CLOUDFLARE_TUNNEL_ROUTES.md`, `docs/DOMAIN_STRATEGY.md`
- Prepared but NOT committed by the agent (ruleset-protected): the `.github/image-pin/allowed-images.yaml` entry — write it into the task report for the human (Task 16).

**Interfaces:**
- Produces: namespace `aster-ai` (labels `app.kubernetes.io/part-of: aster-lang`, `policy.sigstore.dev/include: "true"`); Deployment `aster-ai-workspace` (replicas 1, strategy `Recreate`, image `docker.io/wontlost/aster-ai-workspace` by digest, port 8080, securityContext as aster-api with uid 1001, read-only root FS, `/tmp` emptyDir 256Mi, resources requests 384Mi/100m limits 768Mi/500m, probes `/actuator/health/liveness|readiness` with startupProbe `failureThreshold: 30, periodSeconds: 5`); env from secrets: `AIW_DB_URL=jdbc:postgresql://shared-postgres-rw.data-services.svc.cluster.local:5432/aster_cloud?currentSchema=ai_workspace`, `AIW_DB_USERNAME`/`AIW_DB_PASSWORD` (secret `aster-ai-db-credentials`), `AIW_HMAC_KEY`, `AIW_HANDOFF_PUBLIC_KEY`, `AIW_TICKET_KEY`, `AGUI_PRO_LICENSE` (secret `aster-ai-app-secrets` from Vault `apps/aster-ai-workspace`), plain `AIW_CLOUD_URL=https://aster-lang.cloud`, `AIW_CLOUD_ORIGIN=https://aster-lang.cloud`, `SPRING_PROFILES_ACTIVE=prod`, OTEL env as aster-api, annotation `reloader.stakater.com/auto: "true"`; Service ClusterIP 80→8080; Ingress traefik websecure host `ai.aster-lang.cloud` TLS `aster-lang-cloud-tls`; PDB `maxUnavailable: 1`; image-lock seed entry with the first real digest from Task 14's local build is NOT acceptable (must be cosign-signed from CI) — seed with a placeholder only if `verify-no-floating-tags` allows it; otherwise this task leaves `image-lock.yaml`/`kustomization.yaml images:` empty and documents that the first CI image-pin PR fills them (confirm by reading the aster-lsp seed convention comments; record the decision in the report).

- [ ] **Step 1:** Write the manifests (copy structure and field style from `apps/aster-lang/cloud/` and `apps/wontlost/agui-pro/`).
- [ ] **Step 2:** Validate: `kustomize build apps/aster-lang/ai` (or `kubectl kustomize`) renders; run `scripts/image-pin/verify-cip-sync.sh` and `scripts/image-pin/verify-registry-sync.sh` locally if they run without cluster access; record outputs. (`verify-cip-sync` will only pass once the human adds the allowed-images entry — note it.)
- [ ] **Step 3:** Commit `feat(aster-ai): AI 工作台 k3s 清单、CNPG 角色、证书反射与镜像策略` (trailer). Do not push.

### Task 16: HUMAN STEP — provisioning checklist (controller stops and asks the user)

The controller presents this checklist and waits; it executes only the items the user explicitly approves, one at a time:
1. Create GitHub repo `wontlost-ltd/aster-ai-workspace` (private), push `main` + `feat/initial`; set repo secrets `DOCKER_USERNAME`, `DOCKER_PASSWORD`, `CROSS_REPO_TOKEN`, `IMAGE_PIN_APP_PRIVATE_KEY` and var `IMAGE_PIN_APP_CLIENT_ID`; install the image-pin GitHub App on the repo.
2. Generate keys locally (never committed): Ed25519 key pair (`openssl genpkey -algorithm ed25519 -out hk.pem`; export PKCS#8 DER base64 for cloud and X.509 DER base64 for the service), `AI_WORKSPACE_HMAC_KEY` (`openssl rand -hex 32`), `AIW_TICKET_KEY` (`openssl rand -hex 32`), Pro license via `mvn -f tools/keygen/pom.xml exec:java -Dexec.args="sign --licensee 'Aster Cloud' --features conversations,audit,ee.governance,ee.observability --seats 5 --expires 2027-10-20"` in agui-vaadin-pro.
3. Vault: `vault kv put secret/apps/aster-ai-workspace hmac-key=… handoff-public-key=… ticket-key=… pro-license=…`; `vault kv put secret/data-services/aster-ai-db username=aster_ai_user password=…`.
4. Postgres (as superuser on `shared-postgres`): `CREATE SCHEMA IF NOT EXISTS ai_workspace AUTHORIZATION aster_ai_user; GRANT CONNECT ON DATABASE aster_cloud TO aster_ai_user;` (after the CNPG role exists).
5. Cloudflare Zero Trust → tunnel MEL_OCI_K3S → Public Hostname `ai.aster-lang.cloud` → `http://aster-ai-workspace.aster-ai.svc.cluster.local:80`.
6. Trust root: add the `allowed-images.yaml` entry from Task 15's report (ruleset-protected, human commit).
7. Cloud secrets: `wrangler secret put AI_WORKSPACE_HANDOFF_PRIVATE_KEY` and `wrangler secret put AI_WORKSPACE_HMAC_KEY` (same HMAC value as Vault). Do NOT set `NEXT_PUBLIC_AI_WORKSPACE_URL` yet (Task 18).
8. Merge order once approved: vaadin-agui 0.2.0 published → aster-cloud PR (flag off, inert) → aster-ai-workspace `main` (CI builds/signs image, opens image-pin PR) → k3s PR.

---

## Part E — Verification and rollout

### Task 17: Local end-to-end on the podman stack

**Files:** none committed (evidence in `/Users/rpang/.aster-stack/adr0049/e2e/`, report `e2e-report.md`).

- [ ] Build images: aster-ai-workspace (Task 14), aster-cloud production build from `feat/ai-workspace-bridge` with `NEXT_PUBLIC_AI_WORKSPACE_URL=http://localhost:18080`, `AI_WORKSPACE_ENABLED_FOR=all`, local keys; reuse the existing aster-api container and the Anthropic-emulating HTTPS stub recipe from `/Users/rpang/.aster-stack/temperature/e2e-report.md` (stub `good` key, `claude-opus-5-5`, temperature-deprecated behaviour).
- [ ] Service DB: `aster_cloud` on `aster-pg :55432` with `CREATE SCHEMA ai_workspace` (local stack only; never :55433 here).
- [ ] Scenarios (Playwright via the cloud worktree's `@playwright/test`, screenshots en + zh):
  1. Nav "AI workspace" → handoff → workspace loads, user name shown; ask a question → streamed answer.
  2. Policy editor → AI tab shows iframe panel; Suggest → suggestion with Insert/Replace; Insert and Replace change Monaco; `generate` shows "Apply" and never auto-applies.
  3. Navigate away and back, then hard refresh → the suggestion is still shown in the panel (thread `policy:{id}`); workspace sidebar lists it.
  4. Quota exhausted (set the user's quota to 0 in the local DB) → zh error text with code; provider workspace-scope 400 (stub `noworkspace-list503`) → provider text.
  5. Expired handoff (reuse a captured token after 61 s) → transparent re-handoff.
  6. Foreign-origin postMessage injected in the parent page → no editor change.
  7. Restart the service container mid-session → reload re-handoffs, conversation intact.
- [ ] Performance: time-to-first-delta through the service vs direct cloud (20 runs each, p95 delta ≤ 150 ms); service RSS with 10 open sessions.
- [ ] Restore the stack exactly as found (record `podman inspect` first).

### Task 18: Dark launch, internal enablement, general enablement (HUMAN STEP gates)

- [ ] After Task 16 merges: verify production `https://ai.aster-lang.cloud/actuator/health/readiness` → 200 and that cloud still shows the old panel (flag off).
- [ ] HUMAN STEP: set `NEXT_PUBLIC_AI_WORKSPACE_URL=https://ai.aster-lang.cloud` and `AI_WORKSPACE_ENABLED_FOR=<owner user id>` in `wrangler.toml [vars]` via a cloud PR; deploy; run scenarios 1–4 of Task 17 against production with that user.
- [ ] HUMAN STEP: switch `AI_WORKSPACE_ENABLED_FOR=all` (cloud PR) after the user signs off.
- [ ] Follow-up plans (not in this plan): delete the old React assistant panel after SaaS acceptance; on-prem packaging with the Task 12 license gate (ADR 0049 §2.8).
