package com.cloudops.tool;

import com.cloudops.entity.MockBillingStream;
import com.cloudops.tool.annotation.RequiredPermission;
import com.cloudops.skill.BillingQuerySkill;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 账单查询 Tool — AI 适配层，包装 BillingQuerySkill
 *
 * 成本优化场景时 Agent 调这个 Tool：
 *   gpu-002 月费3599但利用率<10% → 建议释放省钱
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingQueryTool extends AbstractTool {

    private final BillingQuerySkill billingQuerySkill;

    /**
     * 按租户+账期查账单
     */
    @RequiredPermission("billing:read")
    @Tool("按租户ID和账期查询账单流水，返回资源名称、计费类型、费用金额")
    public List<MockBillingStream> queryBill(
            @P("租户ID，如tenant-001") String tenantId,
            @P("账期，格式yyyyMM，如202607") int billingPeriod
    ) {
        return executeOrThrow("queryBill", () -> billingQuerySkill.searchByTenant(tenantId, billingPeriod));
    }

    /**
     * 按资源ID查该资源的所有账单（看这台机器花了多少钱）
     */
    @RequiredPermission("billing:read")
    @Tool("按资源ID查询该资源的所有账单流水，返回费用明细")
    public List<MockBillingStream> queryBillByResource(@P("资源ID，如ecs-001") String resourceId) {
        return executeOrThrow("queryBillByResource", () -> billingQuerySkill.searchByResource(resourceId));
    }
}
