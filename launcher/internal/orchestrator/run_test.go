package orchestrator

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"
)

// 用 fake clientset 驱动：手动把 Job 标记为 Succeeded 后，runJob 应尝试读回 envelope。
// ★Pod log 在 fake clientset 里恒返回固定串（不可注入任意 log）——故本单测覆盖
//
//	「create + watch 终态 + fail-closed」的编排逻辑；真 envelope 解析走 Task 2.4 集成测试。
func TestRunJob_SucceedsAndReadsEnvelope(t *testing.T) {
	const digest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	cs := fake.NewSimpleClientset()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// 后台 goroutine 模拟 Job controller：等 Job 出现后标 Succeeded=1。
	go func() {
		for i := 0; i < 50; i++ {
			jobs, _ := cs.BatchV1().Jobs(runnerNamespace).List(ctx, metav1.ListOptions{})
			if len(jobs.Items) == 1 {
				j := jobs.Items[0].DeepCopy()
				j.Status.Succeeded = 1
				j.Status.Conditions = []batchv1.JobCondition{{Type: batchv1.JobComplete, Status: "True"}}
				_, _ = cs.BatchV1().Jobs(runnerNamespace).UpdateStatus(ctx, j, metav1.UpdateOptions{})
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()

	env, err := runJob(ctx, cs, testReq(), digest)
	// fake clientset 的 GetLogs 恒返回固定 "fake logs"，且无 Pod（fake 不跑真 Job controller
	// 产 Pod），无法注入合法 envelope。故编排应在读 log/parse 阶段 fail-closed 到
	// ErrUnavailable——验证「Job Succeeded 但日志不可解析」这条 fail-closed 路径；真 envelope
	// 成功解析路径由 Task 2.4 集成测试（真 runner 镜像）覆盖。
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("期望 ErrUnavailable（fake 无可解析 envelope），得 env=%+v err=%v", env, err)
	}
}

func TestRunJob_TimeoutIsError(t *testing.T) {
	const digest = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	cs := fake.NewSimpleClientset()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	// 不模拟 controller → Job 永不终态 → ctx 超时 → error（handler 归 unavailable）。
	if _, err := runJob(ctx, cs, testReq(), digest); err == nil {
		t.Fatal("期望超时 error，得 nil")
	} else if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("期望 ErrUnavailable，得 %v", err)
	}
}

// TestRunJob_ConfigMapOwnerRefUIDBackfilled 是 Codex 强制契约：ConfigMap 的 owner-ref 必须
// 携带 Job 的真实 UID（只有 API server create Job 后才存在），绝不能以空 UID 创建 ConfigMap
// （否则级联 GC 失效——Job 删除后 ConfigMap 变孤儿）。
//
// ★fake clientset 的 ObjectTracker 不像真实 API server 那样自动分配 UID（create 只是原样存对象）。
// 用 PrependReactor 拦截 "create jobs"：在对象落盘前把 UID 写进 action 携带的 Job 指针
// （Fake 的默认 ObjectReaction 之后会持久化这个被我们改过的对象），模拟 API server 赋 UID 的行为；
// 返回 handled=false 让默认 tracker 继续完成真正的 create。随后断言 create 出的 ConfigMap
// owner-ref UID 与这个注入 UID 一致。
func TestRunJob_ConfigMapOwnerRefUIDBackfilled(t *testing.T) {
	const digest = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
	const injectedUID = types.UID("job-uid-fixed-for-test")

	cs := fake.NewSimpleClientset()
	cs.PrependReactor("create", "jobs", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if createAction, ok := action.(k8stesting.CreateAction); ok {
			if job, ok := createAction.GetObject().(*batchv1.Job); ok {
				job.UID = injectedUID
			}
		}
		return false, nil, nil // 不 short-circuit：交给默认 tracker 完成真正的 create/持久化。
	})

	// ★在 ConfigMap **create 时**捕获它的 owner-ref——不依赖 runJob 返回后的状态
	//   （runJob 失败路径会 best-effort 删 Job 并级联删 ConfigMap，故 post-return list 不可靠；
	//   本契约要验的是「create ConfigMap 那一刻 owner-ref 已带真实 UID」）。
	var capturedOwnerUID types.UID
	var capturedRefCount int
	cs.PrependReactor("create", "configmaps", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if createAction, ok := action.(k8stesting.CreateAction); ok {
			if cm, ok := createAction.GetObject().(*corev1.ConfigMap); ok {
				capturedRefCount = len(cm.OwnerReferences)
				if capturedRefCount == 1 {
					capturedOwnerUID = cm.OwnerReferences[0].UID
				}
			}
		}
		return false, nil, nil
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// 驱动 Job 到 Complete 终态（让 runJob 走过 create ConfigMap 后的路径）。
	go func() {
		for i := 0; i < 50; i++ {
			jobs, _ := cs.BatchV1().Jobs(runnerNamespace).List(ctx, metav1.ListOptions{})
			if len(jobs.Items) == 1 {
				j := jobs.Items[0].DeepCopy()
				j.Status.Succeeded = 1
				j.Status.Conditions = []batchv1.JobCondition{{Type: batchv1.JobComplete, Status: "True"}}
				_, _ = cs.BatchV1().Jobs(runnerNamespace).UpdateStatus(ctx, j, metav1.UpdateOptions{})
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()

	// runJob 会因 fake log 不可解析而返回 error（预期）——但 create ConfigMap 已在此前发生并被捕获。
	_, _ = runJob(ctx, cs, testReq(), digest)

	// 契约断言：create ConfigMap 那一刻 owner-ref 恰 1 条且 UID == 注入的 Job UID（非空）。
	if capturedRefCount != 1 {
		t.Fatalf("create ConfigMap 时 owner-ref 数=%d（期望 1）——runJob 未创建 ConfigMap 或 owner-ref 缺失", capturedRefCount)
	}
	if capturedOwnerUID == "" {
		t.Fatal("create ConfigMap 时 owner-ref UID 为空——违反 Codex 强制契约（级联 GC 失效）")
	}
	if capturedOwnerUID != injectedUID {
		t.Fatalf("create ConfigMap 时 owner-ref UID=%q 与 job UID=%q 不一致（回填未生效）", capturedOwnerUID, injectedUID)
	}
}

// TestLastEnvelopeLine 纯函数单测：从含 stderr 噪声混入的 log 中取末条合法 SUCCESS envelope；
// 截断/不含合法 JSON 的 log 必须 error（fail-closed，Fork C）。
func TestLastEnvelopeLine(t *testing.T) {
	logs := "starting runner\nWARN something on stderr merged\n" +
		`{"outcome":"SUCCESS","replayMetadata":{"canonicalInputHash":"h","canonicalOutputHash":"o","canonicalizationVersion":"v1","replayabilityStatus":"REPLAYABLE","traceHash":"t"}}` + "\n"
	env, err := lastEnvelopeLine(logs)
	if err != nil || env.Outcome != "SUCCESS" || env.ReplayMetadata == nil {
		t.Fatalf("env=%+v err=%v", env, err)
	}
	if _, err := lastEnvelopeLine("no json here\ntruncated {\"outcome\""); err == nil {
		t.Fatal("截断 log 应 error")
	}
}

// TestReconcileExitAndOutcome 守 Codex 抓的矛盾组合放行：只有 (0,SUCCESS)/(1,ERROR) 合法，
// 其余（含 exit3、0+ERROR、1+SUCCESS、未知 exit）一律 ErrUnavailable（runner 输出损坏 fail-closed）。
func TestReconcileExitAndOutcome(t *testing.T) {
	cases := []struct {
		exit    int32
		outcome string
		wantErr bool
	}{
		{0, "SUCCESS", false},
		{1, "ERROR", false},
		{3, "SUCCESS", true},  // 序列化失败
		{0, "ERROR", true},    // 矛盾：exit0 却 ERROR
		{1, "SUCCESS", true},  // 矛盾：exit1 却 SUCCESS
		{2, "SUCCESS", true},  // 未知 exit code
		{-1, "SUCCESS", true}, // exit code 读取失败（-1 哨兵）
		{0, "", true},         // 空 outcome
	}
	for _, tc := range cases {
		err := reconcileExitAndOutcome(tc.exit, tc.outcome)
		if tc.wantErr && err == nil {
			t.Fatalf("exit=%d outcome=%q 期望 error 却 nil", tc.exit, tc.outcome)
		}
		if !tc.wantErr && err != nil {
			t.Fatalf("exit=%d outcome=%q 期望 nil 却 %v", tc.exit, tc.outcome, err)
		}
		if tc.wantErr && err != nil && !errors.Is(err, ErrUnavailable) {
			t.Fatalf("exit=%d outcome=%q 错误应 wrap ErrUnavailable，得 %v", tc.exit, tc.outcome, err)
		}
	}
}

// 超限请求在建 Job 之前被拒：返回 ErrRequestTooLarge（非 ErrUnavailable），集群里不留 Job。
func TestRunJob_RequestTooLargeRejectedBeforeCreate(t *testing.T) {
	const digest = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
	cs := fake.NewSimpleClientset()
	req := testReq()
	req.Source = strings.Repeat("a", configMapMaxBytes)

	_, err := runJob(context.Background(), cs, req, digest)
	if !errors.Is(err, ErrRequestTooLarge) || errors.Is(err, ErrUnavailable) {
		t.Fatalf("期望 ErrRequestTooLarge 且非 ErrUnavailable，得 %v", err)
	}
	jobs, _ := cs.BatchV1().Jobs(runnerNamespace).List(context.Background(), metav1.ListOptions{})
	if len(jobs.Items) != 0 {
		t.Fatalf("超限不应创建 Job，得 %d 个", len(jobs.Items))
	}
}
