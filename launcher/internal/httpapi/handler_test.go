package httpapi

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/aster-cloud/aster-api/launcher/internal/orchestrator"
)

const testKey = "test-runner-launcher-hmac-key"

// mockOrch 是 Orchestrator 的测试替身：按预设返回 env/err 或 panic。
type mockOrch struct {
	env      orchestrator.RunnerEnvelope
	err      error
	doPanic  bool
	gotReq   orchestrator.RunnerRequest
	gotCalls int
}

func (m *mockOrch) Run(ctx context.Context, req orchestrator.RunnerRequest) (orchestrator.RunnerEnvelope, error) {
	m.gotCalls++
	m.gotReq = req
	if m.doPanic {
		panic("simulated orchestrator panic")
	}
	return m.env, m.err
}

// signRequest 造带全套签名头的 *http.Request（复刻 client signRunnerLauncherHeaders）。
func signRequest(t *testing.T, tenant, role string, body []byte) *http.Request {
	t.Helper()
	const method, path, nonce = "POST", "/api/v1/runner/launch", "0123456789abcdef0123456789abcdef"
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	sum := sha256.Sum256(body)
	bodyHash := hex.EncodeToString(sum[:])
	canonical := strings.Join([]string{method, path, ts, nonce, bodyHash, tenant, role}, "\n")
	mac := hmac.New(sha256.New, []byte(testKey))
	mac.Write([]byte(canonical))
	sig := hex.EncodeToString(mac.Sum(nil))

	r := httptest.NewRequest(method, path, strings.NewReader(string(body)))
	r.Header.Set("X-Internal-Caller", "cloud-runner-launcher")
	r.Header.Set("X-Aster-Timestamp", ts)
	r.Header.Set("X-Aster-Nonce", nonce)
	r.Header.Set("X-Aster-Tenant", tenant)
	r.Header.Set("X-Aster-Role", role)
	r.Header.Set("X-Internal-Signature", sig)
	return r
}

func TestLaunchHandler(t *testing.T) {
	t.Setenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY", testKey)
	validBody := []byte(`{"tenantId":"t1","source":"Module M","input":{"x":1},"locale":"en-US","functionName":"f","aliasSet":null}`)

	// ★ReplayMetadata 是 orchestrator.RunnerEnvelope 的 json.RawMessage 字段（Task 2.1 Codex
	// 加固：完整 runner ReplayMetadata.java 有 11 个字段，launcher 不解析成有类型 struct 再
	// 重序列化，逐字节透传）。这里用一段刻意包含「未在任何 launcher 类型中建模」字段
	// （reasonCodes/replayabilityReasons/canonicalInput 等）的原始 JSON，验证 handler 原样
	// 转发、不丢字段、不重排。
	rawReplayMetadata := []byte(`{"canonicalInputHash":"h","canonicalOutputHash":"o","canonicalizationVersion":"v1","replayabilityStatus":"REPLAYABLE","traceHash":"t","reasonCodes":["R1"],"replayabilityReasons":["x"],"canonicalInput":"CI"}`)
	successEnv := orchestrator.RunnerEnvelope{
		Outcome:        "SUCCESS",
		ReplayMetadata: rawReplayMetadata,
	}
	errorEnv := orchestrator.RunnerEnvelope{
		Outcome: "ERROR", ErrorCode: "EXECUTION", Message: "boom", Phase: "execute",
	}

	cases := []struct {
		name       string
		req        func() *http.Request
		orch       *mockOrch
		wantStatus int
		wantBody   func(t *testing.T, body []byte)
	}{
		{
			name:       "valid-success-200-passthrough",
			req:        func() *http.Request { return signRequest(t, "t1", "user", validBody) },
			orch:       &mockOrch{env: successEnv},
			wantStatus: 200,
			wantBody: func(t *testing.T, body []byte) {
				var got orchestrator.RunnerEnvelope
				if err := json.Unmarshal(body, &got); err != nil || got.Outcome != "SUCCESS" || got.ReplayMetadata == nil {
					t.Fatalf("body 非 SUCCESS envelope: %s (err=%v)", body, err)
				}
				// ★关键断言：replayMetadata 原始字节逐字节透传，不因 launcher 类型窄而丢字段。
				if string(got.ReplayMetadata) != string(rawReplayMetadata) {
					t.Fatalf("replayMetadata 未原样透传:\n got=%s\nwant=%s", got.ReplayMetadata, rawReplayMetadata)
				}
			},
		},
		{
			name:       "runner-error-200-not-http-error",
			req:        func() *http.Request { return signRequest(t, "t1", "user", validBody) },
			orch:       &mockOrch{env: errorEnv},
			wantStatus: 200, // ★关键：runner 业务错也是 200，client 按 outcome 分类
			wantBody: func(t *testing.T, body []byte) {
				var got orchestrator.RunnerEnvelope
				_ = json.Unmarshal(body, &got)
				if got.Outcome != "ERROR" || got.ErrorCode != "EXECUTION" || got.Phase != "execute" {
					t.Fatalf("ERROR envelope 字段丢失: %s", body)
				}
			},
		},
		{
			name:       "unavailable-503",
			req:        func() *http.Request { return signRequest(t, "t1", "user", validBody) },
			orch:       &mockOrch{err: orchestrator.ErrUnavailable},
			wantStatus: 503,
		},
		{
			name:       "panic-in-orchestrator-503-not-500",
			req:        func() *http.Request { return signRequest(t, "t1", "user", validBody) },
			orch:       &mockOrch{doPanic: true},
			wantStatus: 503, // ★reject-proof：panic 也被 recover 成结构化 503，绝不裸 500
		},
		{
			name: "bad-hmac-403",
			req: func() *http.Request {
				r := signRequest(t, "t1", "user", validBody)
				r.Header.Set("X-Internal-Signature", "deadbeef"+r.Header.Get("X-Internal-Signature")[8:])
				return r
			},
			orch:       &mockOrch{env: successEnv},
			wantStatus: 403,
		},
		{
			name: "wrong-caller-401",
			req: func() *http.Request {
				r := signRequest(t, "t1", "user", validBody)
				r.Header.Set("X-Internal-Caller", "cloud-bff")
				return r
			},
			orch:       &mockOrch{env: successEnv},
			wantStatus: 401,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h := &LaunchHandler{Orch: tc.orch}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, tc.req())
			if rec.Code != tc.wantStatus {
				t.Fatalf("status=%d want=%d body=%s", rec.Code, tc.wantStatus, rec.Body.String())
			}
			// ★reject-proof 断言：任何响应都是合法 JSON，绝不是裸 stacktrace 文本。
			var probe map[string]any
			if err := json.Unmarshal(rec.Body.Bytes(), &probe); err != nil {
				t.Fatalf("响应非结构化 JSON（疑似裸 500 stacktrace）: %s", rec.Body.String())
			}
			// 拒绝态（401/403/503）不得调用 orchestrator（bad-HMAC 在验签阶段 fail-fast）。
			if tc.wantStatus == 401 || tc.wantStatus == 403 {
				if tc.orch.gotCalls != 0 {
					t.Fatalf("验签失败不应调用 orchestrator，实际调用 %d 次", tc.orch.gotCalls)
				}
			}
			if tc.wantBody != nil {
				tc.wantBody(t, rec.Body.Bytes())
			}
		})
	}
}

// key-not-configured-500 单独一测（须清空 env，与主表驱动共用 env 会互相干扰）。
// ★VerifyHMAC 对未配置 key 返回 500；handler 必须转成结构化 503（launcher 对外只暴露
// 「可用/不可用」，不吐内部 500），且不得调用 orchestrator。
func TestLaunchHandler_KeyNotConfigured(t *testing.T) {
	t.Setenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY", "")
	validBody := []byte(`{"tenantId":"t1","source":"Module M","input":{"x":1},"locale":"en-US","functionName":"f","aliasSet":null}`)
	orch := &mockOrch{env: orchestrator.RunnerEnvelope{Outcome: "SUCCESS"}}
	h := &LaunchHandler{Orch: orch}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signRequest(t, "t1", "user", validBody))

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want=503 body=%s", rec.Code, rec.Body.String())
	}
	var probe map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &probe); err != nil {
		t.Fatalf("响应非结构化 JSON: %s", rec.Body.String())
	}
	if orch.gotCalls != 0 {
		t.Fatalf("key 未配置不应调用 orchestrator，实际调用 %d 次", orch.gotCalls)
	}
}

// Healthz 恒 200 + {"status":"ok"}（k8s liveness/readiness）。
func TestHealthz(t *testing.T) {
	rec := httptest.NewRecorder()
	Healthz(rec, httptest.NewRequest("GET", "/healthz", nil))
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "ok") {
		t.Fatalf("healthz code=%d body=%s", rec.Code, rec.Body.String())
	}
}

// TestLaunchHandler_OversizedBodyRejected 守 Codex 抓的认证边界洞：io.LimitReader 静默截断
// 会让「对合法前缀签名 + 追加未签名尾部」的 body 被验签+接受（HMAC 未覆盖完整 entity）。
// 修复后：body 超 maxBodyBytes 必 413 fail-closed，验签前拒绝，orchestrator 零调用。
func TestLaunchHandler_OversizedBodyRejected(t *testing.T) {
	t.Setenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY", testKey)

	// 构造超限 body：恰好 maxBodyBytes+1 字节（> 上限）。签名对**这个完整 body** 也算，
	// 但 handler 应在读取阶段就因超限拒绝，根本不进 VerifyHMAC/orchestrator。
	oversized := make([]byte, (1<<20)+1)
	for i := range oversized {
		oversized[i] = 'a'
	}
	orch := &mockOrch{env: orchestrator.RunnerEnvelope{Outcome: "SUCCESS"}}
	h := &LaunchHandler{Orch: orch}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signRequest(t, "t1", "user", oversized))

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("超限 body 应 413，得 %d", rec.Code)
	}
	if orch.gotCalls != 0 {
		t.Fatalf("超限 body 绝不该调 orchestrator，却调了 %d 次", orch.gotCalls)
	}
	// 响应仍是结构化 JSON（reject-proof）。
	var eb errorBody
	if err := json.Unmarshal(rec.Body.Bytes(), &eb); err != nil || eb.Error == "" {
		t.Fatalf("413 响应应是结构化 JSON errorBody，得 %s", rec.Body.String())
	}
}

// TestLaunchHandler_SignedPrefixUnsignedTailRejected 精确复现 Codex 描述的攻击：对**恰好 max
// 的合法 JSON 前缀**签名（bodyHash 只覆盖前缀），再追加未签名尾部使总长 > max。修复前 LimitReader
// 会截回前缀 → 验签通过被接受（HMAC 未覆盖完整 entity）；修复后总长超限直接 413，orchestrator 零调用。
func TestLaunchHandler_SignedPrefixUnsignedTailRejected(t *testing.T) {
	t.Setenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY", testKey)

	// 合法前缀：恰好 maxBodyBytes 的可解析 JSON（padding 塞进一个字符串字段撑到上限）。
	const maxBody = 1 << 20
	head := `{"tenantId":"t1","source":"m","input":{},"locale":"en-US","functionName":"f","aliasSet":null,"pad":"`
	tail := `"}`
	padLen := maxBody - len(head) - len(tail)
	prefix := head + strings.Repeat("x", padLen) + tail
	if len(prefix) != maxBody {
		t.Fatalf("前缀长度=%d 期望=%d（构造错）", len(prefix), maxBody)
	}

	// 对**前缀**签名（模拟攻击者只对合法前缀签名）。
	r := signRequest(t, "t1", "user", []byte(prefix))
	// 再追加未签名尾部，使 HTTP body 总长 > max（签名仍是前缀的）。
	full := prefix + strings.Repeat("Z", 100)
	r.Body = io.NopCloser(strings.NewReader(full))
	r.ContentLength = int64(len(full))

	orch := &mockOrch{env: orchestrator.RunnerEnvelope{Outcome: "SUCCESS"}}
	h := &LaunchHandler{Orch: orch}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, r)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("签名前缀+未签名尾部应 413（总长超限），得 %d", rec.Code)
	}
	if orch.gotCalls != 0 {
		t.Fatalf("超限 body 绝不该调 orchestrator，却调了 %d 次", orch.gotCalls)
	}
}

// 编排层报 ErrRequestTooLarge → 413 + 明确信息（而非 503 runner orchestration unavailable）。
func TestLaunchHandler_RequestTooLargeMapsTo413(t *testing.T) {
	t.Setenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY", testKey)
	body := []byte(`{"tenantId":"t1","source":"Module M","input":{},"locale":"en-US","functionName":"f","aliasSet":null}`)
	orch := &mockOrch{err: fmt.Errorf("%w: 1048600 bytes exceeds limit 1048576", orchestrator.ErrRequestTooLarge)}
	h := &LaunchHandler{Orch: orch}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, signRequest(t, "t1", "user", body))

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("应 413，得 %d", rec.Code)
	}
	var eb errorBody
	if err := json.Unmarshal(rec.Body.Bytes(), &eb); err != nil || !strings.Contains(eb.Error, "too large") {
		t.Fatalf("应有明确的体积错误信息，得 %s", rec.Body.String())
	}
}
