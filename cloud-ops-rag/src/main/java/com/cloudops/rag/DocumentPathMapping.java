package com.cloudops.rag;

/**
 * 文档路径 → 权限标签映射
 *
 * 三级权限模型:
 *   public — 全局共享，所有租户可见（如通用SOP文档）
 *   tenant — 租户级，仅本租户可见（如生产环境专属文档）
 *   dept   — 部门级，仅本部门可见（如运维组专属文档）
 *
 * 路径规则:
 *   docs/runbooks/alert-cpu-high.md              → public, 全局可见
 *   docs/runbooks/tenant-001/prod-deploy.md      → tenant, tenant-001可见
 *   docs/runbooks/tenant-001/ops/internal.md     → dept, dept-prod-ops可见
 */
public record DocumentPathMapping(
        String tenantId,      // "public" 或 "tenant-001"
        String deptId,        // "public" 或 "dept-prod-ops"
        String accessLevel,   // "public" / "tenant" / "dept"
        String category       // 文档分类: alarm/docker/k8s/sop
) {}
