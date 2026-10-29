package orchestrator

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// 镜像仓库前缀——digest 由 Fork A 从 launcher Deployment env RUNNER_IMAGE_DIGEST 传入。
const runnerImageRepo = "docker.io/wontlost/aster-replay-runner"

// runnerNamespace 是 launcher 建 Job 的目标 ns（与 launcher 同 ns，2b-seed 已建）。
const runnerNamespace = "aster-runner"

// requestConfigKey 是 ConfigMap 中承载 RunnerRequest JSON 的键。
const requestConfigKey = "request.json"

// configMapMaxBytes 是 k8s 对单个 ConfigMap 的 Data+BinaryData 总字节上限（键名与值长度之和，
// 见 apimachinery 校验 MaxSecretSize = 1<<20）。超限时 API server 返回 422 Invalid，无法与集群故障区分，
// 故在建 Job 之前自行预检。
const configMapMaxBytes = 1 << 20

// ErrRequestTooLarge 表示 RunnerRequest 序列化后超过 ConfigMap 容量；是调用方可修正的请求问题
// （缩减源码/词汇），不属于编排层不可用，handler 映射为 413。
var ErrRequestTooLarge = errors.New("runner request too large for configmap")

// marshalRunnerRequest 序列化 RunnerRequest。关闭 HTML 转义：默认的 `<`、`>`、`&` 会膨胀成 6 字节
// 的 \uXXXX 转义，白白占用 ConfigMap 容量；JSON 语义不变，runner 侧解析结果一致。
func marshalRunnerRequest(req RunnerRequest) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(req); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

// buildRunnerJob 造一个 digest-pinned runner Job + per-invocation ConfigMap。
// ★Fork C：ConfigMap 持 request.json → initContainer copy 到 emptyDir /work → 主容器
//
//	command 覆写为 stdin 重定向（runner 镜像字节不变）。ConfigMap owner-ref 到 Job（级联 GC）。
//
// ★硬化镜像 migrate-job.yaml：backoffLimit/ttl/restartPolicy/token/securityContext/emptyDir。
// digest 形如 "sha256:...."；image = <repo>@<digest>。jobName 用调用方生成的唯一名（见 runJob）。
// ★返回 error：req.Input 是 any（来自 cloud 请求体的任意 JSON），可能含不可序列化值
//
//	（如含循环引用或 chan/func 的 map）——若吞掉 Marshal 错会静默产空 request 送给 runner
//	（Codex 抓）。故 Marshal 失败即返回 error，由 runJob 归 unavailable，绝不产半成品 Job。
func buildRunnerJob(req RunnerRequest, digest string) (*batchv1.Job, *corev1.ConfigMap, error) {
	jobName := newJobName()

	// request.json：runner 逐字读 stdin 的 RunnerRequest JSON（aliasSet 显式 null 对齐 Java）。
	reqJSON, err := marshalRunnerRequest(req)
	if err != nil {
		return nil, nil, fmt.Errorf("marshal runner request: %w", err)
	}
	if size := len(requestConfigKey) + len(reqJSON); size > configMapMaxBytes {
		return nil, nil, fmt.Errorf("%w: %d bytes exceeds limit %d", ErrRequestTooLarge, size, configMapMaxBytes)
	}

	falsePtr := boolPtr(false)
	truePtr := boolPtr(true)
	uid := int64Ptr(1000)
	backoff := int32Ptr(2)
	ttl := int32Ptr(300) // 5min 后 GC；比 30s SLA 宽裕，便于失败时短暂保留日志

	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      jobName, // 与 Job 同名，便于关联
			Namespace: runnerNamespace,
		},
		Data: map[string]string{requestConfigKey: string(reqJSON)},
	}

	job := &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{
			Name:      jobName,
			Namespace: runnerNamespace,
			Labels:    map[string]string{"app": "aster-runner-job"},
		},
		Spec: batchv1.JobSpec{
			TTLSecondsAfterFinished: ttl,
			BackoffLimit:            backoff,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": "aster-runner-job", "job-name": jobName}},
				Spec: corev1.PodSpec{
					RestartPolicy:                corev1.RestartPolicyNever,
					AutomountServiceAccountToken: falsePtr,
					SecurityContext: &corev1.PodSecurityContext{
						RunAsNonRoot:   truePtr,
						RunAsUser:      uid,
						RunAsGroup:     uid,
						FSGroup:        uid,
						SeccompProfile: &corev1.SeccompProfile{Type: corev1.SeccompProfileTypeRuntimeDefault},
					},
					InitContainers: []corev1.Container{{
						Name:  "write-request",
						Image: fmt.Sprintf("%s@%s", runnerImageRepo, digest), // 复用同一 runner 镜像（含 sh）
						// 从 ConfigMap 挂载点 copy 到 emptyDir /work（emptyDir 在 readOnlyRootFilesystem 下可写）。
						Command:         []string{"/bin/sh", "-c", "cp /config/request.json /work/request.json"},
						SecurityContext: hardenedContainerSecurityContext(),
						VolumeMounts: []corev1.VolumeMount{
							{Name: "config", MountPath: "/config", ReadOnly: true},
							{Name: "work", MountPath: "/work"},
						},
					}},
					Containers: []corev1.Container{{
						Name:  "runner",
						Image: fmt.Sprintf("%s@%s", runnerImageRepo, digest),
						// ★Fork C：覆写 command，把 emptyDir 里的 request.json 重定向进 runner stdin。
						Command:         []string{"/bin/sh", "-c", "exec /app/bin/runner < /work/request.json"},
						SecurityContext: hardenedContainerSecurityContext(),
						Resources:       runnerResources(), // TODO-capacity：见 Task 2.3 实测驱动
						VolumeMounts: []corev1.VolumeMount{
							{Name: "work", MountPath: "/work"},
							{Name: "tmp", MountPath: "/tmp"},
						},
					}},
					Volumes: []corev1.Volume{
						{Name: "config", VolumeSource: corev1.VolumeSource{
							ConfigMap: &corev1.ConfigMapVolumeSource{LocalObjectReference: corev1.LocalObjectReference{Name: jobName}}}},
						{Name: "work", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
						{Name: "tmp", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
					},
				},
			},
		},
	}

	// owner-ref：ConfigMap 属于 Job，Job 删则 ConfigMap 级联 GC（避免 per-invocation ConfigMap 泄漏）。
	// ★owner-ref 的 UID 在 Job 由 API server 创建后才有——runJob 会在 create Job 后回填 UID 再 create ConfigMap。
	cm.OwnerReferences = []metav1.OwnerReference{{
		APIVersion: "batch/v1", Kind: "Job", Name: jobName,
		Controller: truePtr, BlockOwnerDeletion: truePtr,
	}}
	return job, cm, nil
}

// hardenedContainerSecurityContext 是容器级硬化（镜像 migrate-job 的 container securityContext）。
func hardenedContainerSecurityContext() *corev1.SecurityContext {
	return &corev1.SecurityContext{
		AllowPrivilegeEscalation: boolPtr(false),
		ReadOnlyRootFilesystem:   boolPtr(true),
		Capabilities:             &corev1.Capabilities{Drop: []corev1.Capability{"ALL"}},
	}
}

// runnerResources 是 runner 容器的 request/limit。
// ★TODO-capacity（唯一延后项）：当前值是保守占位，正式值由 Task 2.3 实测（峰值 RSS + p95 冷启动）驱动，
//
//	并发 default=1。见 Task 2.3 的测量流程——不是拍脑袋数字。
func runnerResources() corev1.ResourceRequirements {
	// 占位：宽松上限便于先跑通编排 + 供 Task 2.3 测峰值。正式值替换后 commit。
	return corev1.ResourceRequirements{}
}
