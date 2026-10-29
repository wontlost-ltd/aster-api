package orchestrator

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

// ErrUnavailable 表示编排层系统性失败（超时/日志不可解析/Job 消失）——handler 归 F 契约的 unavailable。
var ErrUnavailable = errors.New("runner job unavailable")

// runJob 编排一次 runner 执行：create Job（拿 UID）→ 回填 ConfigMap owner-ref UID → create
// ConfigMap → watch 终态（受 ctx 30s SLA 约束）→ 读 Pod log 末行 envelope，用 exit code 作权威。
// 任何系统性失败返回 (zero, err)——handler 绝不裸 500，会转结构化 unavailable。
//
// ★create 顺序是 Codex 强制契约：ConfigMap 的 owner-ref UID 只有在 API server create Job 后
//
//	才存在（buildRunnerJob 只能填 Name/Kind/APIVersion，UID 留空）。绝不能以空 UID 的
//	owner-ref 创建 ConfigMap——否则 Job 删除后 ConfigMap 变孤儿，级联 GC 失效、泄漏
//	per-invocation ConfigMap。故顺序恒为：create Job → 回填 UID → create ConfigMap。
func runJob(ctx context.Context, clientset kubernetes.Interface, req RunnerRequest, digest string) (env RunnerEnvelope, retErr error) {
	job, cm, err := buildRunnerJob(req, digest)
	if err != nil {
		if errors.Is(err, ErrRequestTooLarge) {
			return RunnerEnvelope{}, err // 请求问题而非集群故障，原样上抛供 handler 回 413
		}
		return RunnerEnvelope{}, fmt.Errorf("%w: build job: %v", ErrUnavailable, err)
	}

	// 1) 先 create Job 拿到 API server 分配的真实 UID。
	created, err := clientset.BatchV1().Jobs(runnerNamespace).Create(ctx, job, metav1.CreateOptions{})
	if err != nil {
		return RunnerEnvelope{}, fmt.Errorf("%w: create job: %v", ErrUnavailable, err)
	}

	// ★中途失败/超时清理（Codex 抓的泄漏）：ttlSecondsAfterFinished 只回收**已终态** Job；
	//   若本函数超时/失败时 Job 仍 Active/Pending，TTL 永不触发 → Job + ConfigMap 泄漏。
	//   故**仅在返回 error 时**（放弃编排）best-effort 删 Job（Background 传播使 ConfigMap
	//   经 owner-ref 级联删）。成功路径**不删**——Job 已 Complete，交 ttlSecondsAfterFinished
	//   原生 GC（保留短暂日志便于审计）。用独立 context（父 ctx 可能已超时取消）+ 短超时。
	defer func() {
		if retErr == nil {
			return // 成功：留给 TTL GC，不主动删
		}
		delCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		bg := metav1.DeletePropagationBackground
		_ = clientset.BatchV1().Jobs(runnerNamespace).Delete(
			delCtx, created.Name, metav1.DeleteOptions{PropagationPolicy: &bg})
	}()

	// 2) 回填 ConfigMap owner-ref 的 UID（buildRunnerJob 留空，此处补齐）。
	cm.OwnerReferences[0].UID = created.UID

	// 3) 回填后才 create ConfigMap——绝不以空 UID owner-ref 创建（级联 GC 契约）。
	if _, err := clientset.CoreV1().ConfigMaps(runnerNamespace).Create(ctx, cm, metav1.CreateOptions{}); err != nil {
		return RunnerEnvelope{}, fmt.Errorf("%w: create configmap: %v", ErrUnavailable, err)
	}

	// watch 到 Job **真终态**（Complete/Failed condition，非 Status.Failed 计数），受 ctx timeout 约束。
	jobFailed, err := waitForJobTerminal(ctx, clientset, created.Name)
	if err != nil {
		return RunnerEnvelope{}, err // 已含 ErrUnavailable 包装（defer 会清理 Job）
	}

	// 读 Pod log 末行 envelope。★不 2>/dev/null——读全 log 保诊断，从末尾找首条合法 envelope。
	logs, exitCode, err := readPodLogAndExitCode(ctx, clientset, created.Name)
	if err != nil {
		return RunnerEnvelope{}, fmt.Errorf("%w: read pod log: %v", ErrUnavailable, err)
	}
	parsed, parseErr := lastEnvelopeLine(logs)
	if parseErr != nil {
		// 日志截断/不可解析 → fail-closed 到 unavailable（Fork C）。
		return RunnerEnvelope{}, fmt.Errorf("%w: unparseable envelope (exit=%d, jobFailed=%v): %v",
			ErrUnavailable, exitCode, jobFailed, parseErr)
	}

	// ★exit code × outcome 一致性矩阵（Codex 抓：原只特判 exit3，放行矛盾组合）。
	//   RunnerMain 契约：exit0=SUCCESS，exit1=ERROR，exit3=序列化失败。矛盾=runner 损坏，fail-closed。
	if err := reconcileExitAndOutcome(exitCode, parsed.Outcome); err != nil {
		return RunnerEnvelope{}, err
	}
	return parsed, nil
}

// reconcileExitAndOutcome 交叉校验 runner 容器 exit code 与 envelope.outcome。
// RunnerMain 契约（run() 尾）：SUCCESS→0，ERROR→1，序列化失败→3。任何不匹配组合都表示
// runner 输出损坏（如日志被截断取到错行、或 runner 异常），fail-closed 到 unavailable，绝不透传矛盾结果。
func reconcileExitAndOutcome(exitCode int32, outcome string) error {
	switch {
	case exitCode == 0 && outcome == "SUCCESS":
		return nil
	case exitCode == 1 && outcome == "ERROR":
		return nil
	default:
		// 含 exit3（序列化失败）、exit0+ERROR、exit1+SUCCESS、未知 exit code 等一切矛盾。
		return fmt.Errorf("%w: exit code %d 与 outcome %q 不一致（runner 输出损坏）",
			ErrUnavailable, exitCode, outcome)
	}
}

// waitForJobTerminal poll Job 到**真终态**：Complete condition（成功）或 Failed condition
// （backoff 耗尽，非首次 Pod 失败）。★不用 Status.Failed>0——那是失败 Pod 计数，backoffLimit=2
//
//	下首次失败即触发会**提前**读 log（漏重试），Codex 抓。返回 jobFailed=true 表示 Job 终态失败
//	（仍读 log 拿 runner ERROR envelope）；ctx 超时返回 ErrUnavailable。
func waitForJobTerminal(ctx context.Context, clientset kubernetes.Interface, name string) (jobFailed bool, err error) {
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return false, fmt.Errorf("%w: watch timeout: %v", ErrUnavailable, ctx.Err())
		case <-ticker.C:
			j, gerr := clientset.BatchV1().Jobs(runnerNamespace).Get(ctx, name, metav1.GetOptions{})
			if gerr != nil {
				return false, fmt.Errorf("%w: get job: %v", ErrUnavailable, gerr)
			}
			// 只认 Job condition 作终态：Complete=成功终态，Failed=backoff 耗尽的失败终态。
			for _, c := range j.Status.Conditions {
				if c.Status != corev1.ConditionTrue {
					continue
				}
				switch c.Type {
				case batchv1.JobComplete:
					return false, nil // 成功终态
				case batchv1.JobFailed:
					return true, nil // 失败终态（backoff 耗尽）——仍去读 log 拿 runner ERROR envelope
				}
			}
		}
	}
}

// readPodLogAndExitCode 找 Job 的 Pod，读全 log（不 2>/dev/null）+ 容器终止 exit code。
func readPodLogAndExitCode(ctx context.Context, clientset kubernetes.Interface, jobName string) (string, int32, error) {
	pods, err := clientset.CoreV1().Pods(runnerNamespace).List(ctx, metav1.ListOptions{
		LabelSelector: fmt.Sprintf("job-name=%s", jobName),
	})
	if err != nil {
		return "", -1, err
	}
	if len(pods.Items) == 0 {
		return "", -1, errors.New("no pod for job")
	}
	pod := pods.Items[0]

	// exit code：从 runner 容器的 terminated state 读（0/1/3）。
	var exitCode int32 = -1
	for _, cs := range pod.Status.ContainerStatuses {
		if cs.Name == "runner" && cs.State.Terminated != nil {
			exitCode = cs.State.Terminated.ExitCode
		}
	}

	// 读 log：显式指定 runner 容器。★不加 2>/dev/null（Fork C 保诊断，读全量输出）。
	req := clientset.CoreV1().Pods(runnerNamespace).GetLogs(pod.Name, &corev1.PodLogOptions{Container: "runner"})
	rc, err := req.Stream(ctx)
	if err != nil {
		return "", exitCode, err
	}
	defer rc.Close()
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(rc); err != nil {
		return "", exitCode, err
	}
	return buf.String(), exitCode, nil
}

// lastEnvelopeLine 从 log 末尾向前找第一条能解析为合法 RunnerEnvelope（outcome∈{SUCCESS,ERROR}）的行。
// ★runner 把前置日志走 stderr、envelope 走 stdout 最后一行——但保守起见扫全 log 找末条合法 JSON。
func lastEnvelopeLine(logs string) (RunnerEnvelope, error) {
	lines := strings.Split(strings.TrimRight(logs, "\n"), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		line := strings.TrimSpace(lines[i])
		if line == "" || !strings.HasPrefix(line, "{") {
			continue
		}
		var env RunnerEnvelope
		if err := json.Unmarshal([]byte(line), &env); err != nil {
			continue
		}
		if env.Outcome == "SUCCESS" || env.Outcome == "ERROR" {
			return env, nil
		}
	}
	return RunnerEnvelope{}, errors.New("no valid envelope line in log")
}
