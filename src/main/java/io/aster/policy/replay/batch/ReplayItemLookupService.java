package io.aster.policy.replay.batch;

import io.aster.policy.replay.batch.dto.ReplayItemLookupResponse;
import io.aster.policy.replay.batch.dto.ReplayItemLookupResponse.Item;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 executionId 取该租户最新一次回放的结论码。
 *
 * <p>replay_batch_item 无租户列，经 replay_batch.tenant_id 过滤；跨租户与无回放的 id 不出现在结果里。
 * 只认已完成批次中成功的条目（在途批次的 null 结论与失败项的 ERROR 不是比对结论）；
 * 按 created_at、id 倒序读出后按 executionId 取首条，上限由资源层把住，内存量有界。
 */
@ApplicationScoped
public class ReplayItemLookupService {

    @Inject EntityManager em;

    @Transactional(Transactional.TxType.SUPPORTS)
    public ReplayItemLookupResponse latestByExecutionIds(String tenantId, List<String> executionIds) {
        List<Object[]> rows = em.createQuery(
                "select i.executionId, b.id, i.baseOutcome, i.targetOutcome, i.baseLegacy "
                + "from ReplayBatchItemEntity i, ReplayBatchEntity b "
                + "where i.batchId = b.id and b.tenantId = :tenant and i.executionId in :ids "
                + "and b.status = :completed and i.success = true "
                + "order by b.createdAt desc, b.id desc", Object[].class)
            .setParameter("tenant", tenantId)
            .setParameter("ids", executionIds)
            .setParameter("completed", ReplayBatchStatus.COMPLETED)
            .getResultList();
        Map<String, Item> latest = new LinkedHashMap<>();
        for (Object[] r : rows) {
            latest.putIfAbsent((String) r[0], new Item((String) r[0], String.valueOf(r[1]),
                (String) r[2], (String) r[3], Boolean.TRUE.equals(r[4])));
        }
        return new ReplayItemLookupResponse(List.copyOf(latest.values()));
    }
}
