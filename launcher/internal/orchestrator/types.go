// Package orchestrator 负责把一次 launch 请求编排成一个 digest-pinned runner Job，
// watch 到终态并读回 runner envelope。launcher 只编排不产证据——透传 runner 产的字段不改。
package orchestrator

import "encoding/json"

// RunnerRequest 逐字对齐 aster-api runner 的 RunnerRequest.java（JSON 字段名一致）。
// role 不在此结构（role 只在 HMAC header，不进 runner request body——见 F 契约）。
type RunnerRequest struct {
	TenantID     string              `json:"tenantId"`
	Source       string              `json:"source"`
	Input        any                 `json:"input"`
	Locale       string              `json:"locale"`
	FunctionName string              `json:"functionName"`
	AliasSet     map[string][]string `json:"aliasSet"` // 可为 null（omitempty 不加——须显式发 null 对齐 Java 侧）
	// Vocabulary 是 cloud 下发的原始词汇 JSON（ADR 0051），原样透传给 runner，不解析。
	// 无词汇时省略该键；runner 侧 ignoreUnknown 与缺省均退化为无词汇。
	Vocabulary map[string]any `json:"vocabulary,omitempty"`
}

// RunnerEnvelope 逐字对齐 aster-api runner 的 RunnerEnvelope.java（NON_NULL 序列化：
// 成功承 replayMetadata，错误承 errorCode/message/phase，二者不共存）。
//
// ★replayMetadata 用 json.RawMessage 原样透传（Codex 抓）——不解析成有类型 struct 再重序列化：
//
//	runner 的 ReplayMetadata.java 有 11 个字段（5 hash + reasonCodes + replayabilityStatus +
//	replayabilityReasons + M2 的 canonicalInput/Output/Trace）；若 launcher 用只含 6 字段的
//	struct round-trip，会静默丢掉 reasonCodes/replayabilityReasons/M2 payload 等——违反
//	spec「launcher 只编排不产证据，透传 runner envelope 不改字段」。launcher 只需 outcome 分类，
//	metadata 字节原样转给 cloud（cloud 的 LaunchReplayMetadata 读它需要的 6 个字段，其余字段
//	对 cloud 透明但不丢失，未来消费者/审计仍可取）。
type RunnerEnvelope struct {
	Outcome        string          `json:"outcome"`                  // "SUCCESS" | "ERROR"
	ReplayMetadata json.RawMessage `json:"replayMetadata,omitempty"` // 原样透传，不解析
	ErrorCode      string          `json:"errorCode,omitempty"`
	Message        string          `json:"message,omitempty"`
	Phase          string          `json:"phase,omitempty"`
}
