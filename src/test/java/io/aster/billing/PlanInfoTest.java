package io.aster.billing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanInfo / PlanLimitException 单元测试
 */
class PlanInfoTest {

    @Test
    void failOpen_returnsMostPermissive() {
        PlanInfo info = PlanInfo.failOpen();
        assertEquals("pro", info.plan());
        assertTrue(info.allowsApproval(), "fail-open 必须放行审批，避免业务被 plan 系统拖死");
        assertEquals(-1, info.maxTeamMembers(), "fail-open 不限制成员数");
        assertFalse(info.isFreePlan());
    }

    @Test
    void isFreePlan_onlyFreeReturnsTrue() {
        assertTrue(new PlanInfo("free", null, false, 1, 1000, 0, 0).isFreePlan());
        assertFalse(new PlanInfo("pro", null, true, -1, 50000, 5000, 1).isFreePlan());
        assertFalse(new PlanInfo("enterprise", null, true, -1, -1, -1, -1).isFreePlan());
    }

    @Test
    void grandfatherCustomer_preservesLegacyTier() {
        PlanInfo info = new PlanInfo("pro", "team", true, -1, 50000, 5000, 1);
        assertEquals("team", info.legacyTier());
        assertEquals("pro", info.plan());
        assertTrue(info.allowsApproval());
    }

    @Test
    void apiAccessAllowed_freePlanIsBlocked() {
        assertFalse(new PlanInfo("free", null, false, 1, 100, 0, 0).apiAccessAllowed());
        assertTrue(new PlanInfo("pro", null, true, 5, 5000, 5000, 1).apiAccessAllowed());
        assertTrue(new PlanInfo("enterprise", null, true, -1, -1, -1, -1).apiAccessAllowed());
    }

    @Test
    void unlimitedApi_onlyEnterprise() {
        assertFalse(new PlanInfo("free", null, false, 1, 100, 0, 0).unlimitedApi());
        assertFalse(new PlanInfo("pro", null, true, 5, 5000, 5000, 1).unlimitedApi());
        assertTrue(new PlanInfo("enterprise", null, true, -1, -1, -1, -1).unlimitedApi());
    }

    @Test
    void planLimitException_messageFollowsContract() {
        PlanLimitException ex = new PlanLimitException("reviewer_required");
        assertEquals("reviewer_required", ex.reason());
        assertEquals("upgrade_required:reviewer_required", ex.getMessage(),
            "message 格式必须与 ExceptionMapper 期望一致");
    }

    // ==================================================================
    // What-If 并发批次权益（ADR 0034 §7.2）
    // ==================================================================

    @Test
    void free档没有WhatIf能力而非限流为0() {
        // ★0 表示「没买这个功能」，不是「限流上限是 0」——
        //   调用方据此返回 403（无权益）而不是 409（并发超限）。
        PlanInfo free = new PlanInfo("free", null, false, 1, 1000, 0, 0);
        assertFalse(free.allowsReplayBatch());
        assertFalse(free.hasUnlimitedReplayBatches());
    }

    @Test
    void pro档有能力且上限为1() {
        PlanInfo pro = new PlanInfo("pro", null, true, -1, 50000, 5000, 1);
        assertTrue(pro.allowsReplayBatch());
        assertFalse(pro.hasUnlimitedReplayBatches());
        assertEquals(1, pro.concurrentReplayBatches());
    }

    @Test
    void enterprise负数表示不限() {
        PlanInfo ent = new PlanInfo("enterprise", null, true, -1, -1, -1, -1);
        assertTrue(ent.allowsReplayBatch());
        assertTrue(ent.hasUnlimitedReplayBatches());
    }

    @Test
    void failOpen必须对WhatIf能力fail_closed() {
        // ★这是本记录里**唯一**与 failOpen 宽松取向刻意不一致的字段。
        //   failOpen 意图是「plan-gate 抖动时不阻塞既有业务」，对读类操作合理；
        //   但 What-If 批次是新发起的、消耗计算资源的付费能力——
        //   fail-open 等于「plan-gate 一抖动，free 租户就能免费跑批」。
        PlanInfo fo = PlanInfo.failOpen();

        assertEquals("pro", fo.plan(), "其余字段仍按 pro 档宽松处理");
        assertTrue(fo.apiAccessAllowed(), "其余字段仍 fail-open");

        assertEquals(0, fo.concurrentReplayBatches(),
            "★What-If 权益必须 fail-closed —— 取不到权益时不得放行");
        assertFalse(fo.allowsReplayBatch(),
            "★plan-gate 不可达时不得让任何租户跑 What-If");
    }
}
