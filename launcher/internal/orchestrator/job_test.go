package orchestrator

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
)

func testReq() RunnerRequest {
	return RunnerRequest{
		TenantID: "t1", Source: "Module M ...", Input: map[string]any{"x": 1},
		Locale: "en-US", FunctionName: "f", AliasSet: nil,
	}
}

func TestBuildRunnerJob_ForkC_And_Hardening(t *testing.T) {
	const digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	job, cm, err := buildRunnerJob(testReq(), digest)
	if err != nil {
		t.Fatalf("buildRunnerJob 意外失败: %v", err)
	}

	// ConfigMap 持 request.json，且 owner-ref 到 Job（级联 GC）。
	if _, ok := cm.Data["request.json"]; !ok {
		t.Fatal("configmap 缺 request.json")
	}
	if len(cm.OwnerReferences) != 1 || cm.OwnerReferences[0].Kind != "Job" {
		t.Fatalf("configmap owner-ref 未指向 Job: %+v", cm.OwnerReferences)
	}

	spec := job.Spec.Template.Spec
	// Fork A：image = @digest（不可是 tag）。
	img := spec.Containers[0].Image
	if !strings.HasSuffix(img, "@"+digest) || !strings.Contains(img, "aster-replay-runner") {
		t.Fatalf("image 未 digest-pin: %q", img)
	}
	// Fork C：主容器 command 覆写 stdin 重定向。
	wantCmd := []string{"/bin/sh", "-c", "exec /app/bin/runner < /work/request.json"}
	if strings.Join(spec.Containers[0].Command, "\x00") != strings.Join(wantCmd, "\x00") {
		t.Fatalf("主容器 command 未覆写: %v", spec.Containers[0].Command)
	}
	// Fork C：initContainer copy configmap → /work/request.json。
	if len(spec.InitContainers) != 1 {
		t.Fatalf("缺 initContainer: %v", spec.InitContainers)
	}

	// 硬化断言（镜像 migrate-job.yaml）。
	if job.Spec.BackoffLimit == nil || *job.Spec.BackoffLimit != 2 {
		t.Fatal("backoffLimit != 2")
	}
	if job.Spec.TTLSecondsAfterFinished == nil {
		t.Fatal("缺 ttlSecondsAfterFinished")
	}
	if spec.RestartPolicy != corev1.RestartPolicyNever {
		t.Fatalf("restartPolicy=%v want Never", spec.RestartPolicy)
	}
	if spec.AutomountServiceAccountToken == nil || *spec.AutomountServiceAccountToken {
		t.Fatal("automountServiceAccountToken 应为 false")
	}
	if spec.SecurityContext == nil || spec.SecurityContext.RunAsNonRoot == nil || !*spec.SecurityContext.RunAsNonRoot {
		t.Fatal("runAsNonRoot 应为 true")
	}
	c := spec.Containers[0].SecurityContext
	if c == nil || c.ReadOnlyRootFilesystem == nil || !*c.ReadOnlyRootFilesystem {
		t.Fatal("readOnlyRootFilesystem 应为 true")
	}
	if c.AllowPrivilegeEscalation == nil || *c.AllowPrivilegeEscalation {
		t.Fatal("allowPrivilegeEscalation 应为 false")
	}
	if c.Capabilities == nil || len(c.Capabilities.Drop) == 0 || c.Capabilities.Drop[0] != "ALL" {
		t.Fatal("capabilities 应 drop ALL")
	}
	// emptyDir /work + /tmp 均可写（readOnlyRootFilesystem 下靠 emptyDir 提供可写路径）。
	var work, tmp bool
	for _, v := range spec.Volumes {
		if v.EmptyDir == nil {
			continue
		}
		switch v.Name {
		case "work":
			work = true
		case "tmp":
			tmp = true
		}
	}
	if !work || !tmp {
		t.Fatalf("缺 emptyDir work/tmp: work=%v tmp=%v", work, tmp)
	}
}

// TestBuildRunnerJob_NonSerializableInput 守 Codex 抓的静默空请求：req.Input 是 any
// （cloud 请求体任意 JSON），含不可序列化值（chan/func）时 Marshal 失败——必须返回 error，
// 绝不产半成品 Job/空 request.json 送给 runner。
func TestBuildRunnerJob_NonSerializableInput(t *testing.T) {
	const digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	req := testReq()
	req.Input = make(chan int) // json.Marshal 对 chan 报 UnsupportedTypeError
	job, cm, err := buildRunnerJob(req, digest)
	if err == nil {
		t.Fatal("不可序列化 Input 应返回 error，却成功")
	}
	if job != nil || cm != nil {
		t.Fatalf("失败时应返回 nil job/cm，却得 job=%v cm=%v", job, cm)
	}
}

// TestRunnerEnvelope_MetadataPassthrough 守 Codex 抓的丢字段：runner 的 ReplayMetadata.java
// 有 11 字段（launcher 的旧 6 字段 struct round-trip 会丢 reasonCodes/replayabilityReasons/
// M2 canonicalInput/Output/Trace）。用 json.RawMessage 透传后，unmarshal→marshal 不丢任何字段。
func TestRunnerEnvelope_MetadataPassthrough(t *testing.T) {
	// 模拟 runner stdout 的 envelope，metadata 含 launcher struct 不认识的额外字段。
	raw := `{"outcome":"SUCCESS","replayMetadata":{"canonicalInputHash":"a","canonicalOutputHash":"b","canonicalizationVersion":"v1","replayabilityStatus":"REPLAYABLE","traceHash":"t","runtimeToolchainId":"tc","reasonCodes":[],"replayabilityReasons":["x"],"canonicalInput":"CI","canonicalOutput":"CO","canonicalTrace":"CT"}}`
	var env RunnerEnvelope
	if err := json.Unmarshal([]byte(raw), &env); err != nil {
		t.Fatalf("unmarshal 失败: %v", err)
	}
	if env.Outcome != "SUCCESS" {
		t.Fatalf("outcome=%q", env.Outcome)
	}
	// ★真正的 round-trip：handler 会把 env 重序列化转给 cloud。故断言 json.Marshal(env)
	//   后的输出（非只 RawMessage 本身）仍含全部额外字段——证透传经完整 unmarshal→marshal 不丢。
	out, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("重序列化 envelope 失败: %v", err)
	}
	for _, field := range []string{"reasonCodes", "replayabilityReasons", "canonicalInput", "canonicalOutput", "canonicalTrace"} {
		if !strings.Contains(string(out), field) {
			t.Fatalf("round-trip 丢字段 %q（RawMessage 应原样保留）: %s", field, out)
		}
	}
}

// vocabulary 须随 request.json 完整进入 runner（ADR 0051）；无词汇时不出现该键。
func TestRunnerRequest_VocabularyRoundTrip(t *testing.T) {
	body := `{"tenantId":"t1","source":"s","input":{},"locale":"de-DE","functionName":"f","aliasSet":null,` +
		`"vocabulary":{"id":"v","locale":"de-DE","structs":[{"canonical":"Driver","localized":"Fahrer","kind":"struct"}]}}`
	var req RunnerRequest
	if err := json.Unmarshal([]byte(body), &req); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if req.Vocabulary["id"] != "v" {
		t.Fatalf("vocabulary 被丢弃: %+v", req.Vocabulary)
	}
	out, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	var back map[string]any
	if err := json.Unmarshal(out, &back); err != nil {
		t.Fatal(err)
	}
	structs := back["vocabulary"].(map[string]any)["structs"].([]any)
	if structs[0].(map[string]any)["localized"] != "Fahrer" {
		t.Fatalf("vocabulary 内容回写不一致: %s", out)
	}

	noVocab, _ := json.Marshal(testReq())
	if strings.Contains(string(noVocab), "vocabulary") {
		t.Fatalf("无词汇请求不应出现 vocabulary 键: %s", noVocab)
	}
}

// 构造序列化后恰好 target 字节的请求（source 填充 'a'，不含需转义字符）。
func reqSerializedTo(t *testing.T, target int) RunnerRequest {
	t.Helper()
	req := testReq()
	req.Source = ""
	base, err := marshalRunnerRequest(req)
	if err != nil {
		t.Fatal(err)
	}
	req.Source = strings.Repeat("a", target-len(base))
	got, _ := marshalRunnerRequest(req)
	if len(got) != target {
		t.Fatalf("构造体积失败: got=%d want=%d", len(got), target)
	}
	return req
}

// 键名也计入 ConfigMap 容量：键+值恰好等于上限放行，多 1 字节即拒绝。
func TestBuildRunnerJob_ConfigMapSizeBoundary(t *testing.T) {
	const digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	limit := configMapMaxBytes - len(requestConfigKey)

	if _, cm, err := buildRunnerJob(reqSerializedTo(t, limit), digest); err != nil {
		t.Fatalf("恰好等于上限应放行: %v", err)
	} else if len(cm.Data[requestConfigKey])+len(requestConfigKey) != configMapMaxBytes {
		t.Fatalf("ConfigMap 体积与预期不符")
	}

	job, cm, err := buildRunnerJob(reqSerializedTo(t, limit+1), digest)
	if !errors.Is(err, ErrRequestTooLarge) {
		t.Fatalf("超限 1 字节应返回 ErrRequestTooLarge，得 %v", err)
	}
	if errors.Is(err, ErrUnavailable) {
		t.Fatal("超限不得被归为 ErrUnavailable")
	}
	if job != nil || cm != nil {
		t.Fatal("超限时不应产出 Job/ConfigMap")
	}
}

// 关闭 HTML 转义后 < > & 保持 1 字节，且 JSON 语义不变。
func TestMarshalRunnerRequest_NoHTMLEscape(t *testing.T) {
	req := testReq()
	req.Source = "if a < b && b > c"
	b, err := marshalRunnerRequest(req)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), `\u00`) {
		t.Fatalf("不应出现 HTML 转义: %s", b)
	}
	if strings.HasSuffix(string(b), "\n") {
		t.Fatal("不应带 Encoder 追加的换行")
	}
	var back RunnerRequest
	if err := json.Unmarshal(b, &back); err != nil || back.Source != req.Source {
		t.Fatalf("往返不一致: %v %q", err, back.Source)
	}
}
