// Package httpapi 实现 launcher 的 HTTP 端点：POST /api/v1/runner/launch + /healthz。
// ★reject-proof（Global Constraints）：整个 handler 裹 panic-recovery，任何 panic/内部 error
// 归结构化 503 JSON——launcher 绝不裸 500 stacktrace（镜像 cloud client 的 safeErrorMessage 哲学）。
package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/aster-cloud/aster-api/launcher/internal/auth"
	"github.com/aster-cloud/aster-api/launcher/internal/orchestrator"
)

// maxBodyBytes 限制请求体大小（防超大 body OOM）。runner 源码通常几 KB，1MiB 足够宽裕。
const maxBodyBytes = 1 << 20

// LaunchHandler 处理 POST /api/v1/runner/launch。Orch 是编排 seam（生产=K8sOrchestrator）。
type LaunchHandler struct {
	Orch orchestrator.Orchestrator
}

// errorBody 是所有非 200 响应的结构化 body（诊断用；client 只看 HTTP status 不解析此 body）。
type errorBody struct {
	Error string `json:"error"`
}

// ServeHTTP：验签 → 解析 → 编排 → F 契约映射，全程裹 recover。★绝不裸 500。
func (h *LaunchHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// ★panic-recovery：任何 panic（含 orchestrator 内部）→ 结构化 503，不吐 stacktrace。
	defer func() {
		if rec := recover(); rec != nil {
			writeJSON(w, http.StatusServiceUnavailable, errorBody{Error: "internal orchestrator failure"})
		}
	}()

	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, errorBody{Error: "method not allowed"})
		return
	}

	// 预读 body 一次（body 不可重读）：VerifyHMAC 与 JSON 解析共用同一份 raw bytes。
	// ★超限必须**显式拒绝**而非静默截断（Codex 抓的认证边界洞）：io.LimitReader 会把超限 body
	//   截到 maxBodyBytes——攻击者可对恰好 maxBodyBytes 的合法 JSON 前缀签名、再追加未签名尾部，
	//   handler 验签+解析的都是被截前缀 → HMAC 未覆盖完整 HTTP entity 却被接受。故读 max+1，
	//   超过 max 即 fail-closed 413，验签前拒绝，绝不让 orchestrator 见到部分请求。
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, errorBody{Error: "read body failed"})
		return
	}
	if int64(len(body)) > maxBodyBytes {
		writeJSON(w, http.StatusRequestEntityTooLarge, errorBody{Error: "request body too large"})
		return
	}

	// (1) 验 HMAC（Unit 1）。status!=0 → 直接返回该拒绝码（401/403/500 均转结构化）。
	//     ★验签失败 fail-fast，绝不进 orchestrator（key 隔离 + 拒绝态不触发建 Job）。
	tenant, role, status := auth.VerifyHMAC(r, body)
	if status != 0 {
		// 500（key 未配）也转 503——launcher 对外只暴露「可用/不可用」，不吐内部 500。
		httpStatus := status
		if httpStatus == http.StatusInternalServerError {
			httpStatus = http.StatusServiceUnavailable
		}
		writeJSON(w, httpStatus, errorBody{Error: "unauthorized"})
		return
	}

	// (2) 解析 RunnerRequest（body 已读）。tenant/role 来自已验证 header——tenant 权威取 header
	//     覆盖 body（防 body 内 tenantId 与签名 tenant 不一致的越权；role 不进 body）。
	var req orchestrator.RunnerRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, errorBody{Error: "unparseable request body"})
		return
	}
	req.TenantID = tenant // 以已验证 header tenant 为权威（body 内值仅参考）
	_ = role              // role 只用于 HMAC canonical，不进 runner request body（F 契约）

	// (3) 编排（Unit 2/3.1）。系统性失败（ErrUnavailable/任意 err）→ 503；env 正常 → 按 outcome 映射。
	env, runErr := h.Orch.Run(r.Context(), req)
	if runErr != nil {
		if errors.Is(runErr, orchestrator.ErrRequestTooLarge) {
			writeJSON(w, http.StatusRequestEntityTooLarge, errorBody{Error: "runner request too large for configmap (limit 1MiB), reduce vocabulary or source"})
			return
		}
		// 任意编排错（含 ErrUnavailable）→ 503（client 归 unavailable）。诊断放 body，不吐 stacktrace。
		reason := "runner unavailable"
		if errors.Is(runErr, orchestrator.ErrUnavailable) {
			reason = "runner orchestration unavailable"
		}
		writeJSON(w, http.StatusServiceUnavailable, errorBody{Error: reason})
		return
	}

	// (4) F 契约映射：SUCCESS 与 ERROR 皆 HTTP 200（client 按 outcome 分类，非 HTTP status）。
	//     launcher 原样透传 runner envelope（不改字段——只编排不产证据；replayMetadata 是
	//     json.RawMessage，序列化时原始字节不经解析重排，逐字节透传给 cloud）。
	writeJSON(w, http.StatusOK, env)
}

// Healthz 是 k8s liveness/readiness 探针端点：恒 200 + {"status":"ok"}。
func Healthz(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// writeJSON 序列化 v 为 JSON 并写 status。★序列化失败也不裸 panic——退化为最简结构化 503。
// （env 全为可序列化标量/指针，理论上不失败；此为 reject-proof 的最后一环。）
func writeJSON(w http.ResponseWriter, status int, v any) {
	buf, err := json.Marshal(v)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(`{"error":"response serialization failed"}`))
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(buf)
}
