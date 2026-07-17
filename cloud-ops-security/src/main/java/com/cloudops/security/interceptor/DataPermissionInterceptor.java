package com.cloudops.security.interceptor;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.cloudops.security.context.RequestContextStore;
import com.cloudops.security.context.SecurityContext;
import com.cloudops.security.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.Map;
import java.util.Set;

import java.sql.SQLException;
import java.util.List;

/**
 * 数据权限拦截器 — 自动追加 tenant_id / dept_id WHERE 条件
 *
 * 原理:
 *   1. 拦截 MyBatis 所有 SELECT 操作
 *   2. 从 SecurityContext (ThreadLocal) 获取当前用户的 tenantId/deptId
 *   3. 用 JSqlParser 解析原始 SQL，在 WHERE 条件后追加过滤条件
 *   4. 替换 BoundSql 中的 SQL 文本
 *
 * 过滤规则:
 *   - SUPER_ADMIN: 不追加任何条件（全局可见）
 *   - TENANT_ADMIN: 追加 WHERE tenant_id = '当前用户tenantId'
 *   - 其他角色: 追加 WHERE tenant_id = ? AND dept_id = ?
 *
 * 只拦截 SELECT，不拦截 INSERT/UPDATE/DELETE（这些操作在业务层处理）
 */
@Slf4j
public class DataPermissionInterceptor implements InnerInterceptor {

    /**
     * 需要数据隔离的表名列表
     * 只对这些表追加 WHERE 条件，其他表（如 sys_user 等系统表）不拦截
     */
    private static final List<String> PROTECTED_TABLES = List.of(
            "mock_alarm",
            "mock_resource_relation",
            "mock_resource_load",
            "mock_billing_stream",
            "chat_memory"
    );

    /**
     * 表级部门过滤旁路规则：哪些角色在查哪些表时可以跳过部门过滤。
     * 设计意图：财务需要跨部门看账单（一个租户下所有部门的费用），
     * 但不应跨部门看告警/资源。此规则使财务角色查询账单表时只按租户过滤。
     */
    private static final Map<String, Set<String>> DEPT_BYPASS =
            Map.of("mock_billing_stream", Set.of("FINANCE_USER"));

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        // 1. 获取当前用户上下文（优先 ThreadLocal，回退到请求级存储）
        UserContext user = SecurityContext.get();
        if (user == null) {
            // 尝试从参数中提取 userId（MyBatis Mapper 方法可能传入）
            String userId = extractUserId(parameter);
            user = RequestContextStore.get(userId);
        }
        if (user == null) {
            // ★ 跨线程兜底：遍历 RequestContextStore（LangChain4j 工作线程上 ThreadLocal 不可用）
            for (UserContext ctx : RequestContextStore.getAll()) {
                user = ctx;
                break; // 取第一个活跃上下文（通常只有一个）
            }
        }
        if (user == null) {
            // 未登录或上下文未传播（如登录接口本身），不拦截
            return;
        }

        // 2. 超级管理员不拦截
        if (user.isSuperAdmin()) {
            return;
        }

        // 3. 获取原始 SQL
        String originalSql = boundSql.getSql();
        log.debug("[数据权限] 原始SQL: {}", originalSql);

        try {
            // 4. 用 JSqlParser 解析 SQL
            Statement statement = CCJSqlParserUtil.parse(originalSql);

            if (statement instanceof Select select) {
                if (select.getSelectBody() instanceof PlainSelect plainSelect) {
                    // 5. 检查是否查的是受保护的表
                    if (!isProtectedTable(plainSelect)) {
                        return;
                    }

                    // 6. 追加 WHERE 条件
                    String filteredSql = appendDataPermission(plainSelect, user);

                    // 7. 替换 BoundSql 中的 SQL
                    PluginUtils.mpBoundSql(boundSql).sql(filteredSql);
                    log.debug("[数据权限] 过滤后SQL: {}", filteredSql);
                }
            }
        } catch (JSQLParserException e) {
            log.error("[数据权限] SQL解析失败，跳过数据权限拦截: {}", originalSql, e);
        }
    }

    /**
     * 检查 SQL 查询的表是否在受保护列表中
     */
    private boolean isProtectedTable(PlainSelect plainSelect) {
        if (plainSelect.getFromItem() instanceof Table table) {
            String tableName = table.getName().toLowerCase();
            return PROTECTED_TABLES.contains(tableName);
        }
        return false;
    }

    /**
     * 追加数据权限 WHERE 条件
     *
     * TENANT_ADMIN: WHERE tenant_id = 'tenant-001'
     * 其他角色: WHERE tenant_id = 'tenant-001' AND dept_id = 'dept-prod-ops'
     */
    private String appendDataPermission(PlainSelect plainSelect, UserContext user) {
        // 构建 tenant_id = 'xxx' 条件
        EqualsTo tenantCondition = new EqualsTo(
                new Column("tenant_id"),
                new StringValue(user.tenantId())
        );

        if (user.roles().contains("TENANT_ADMIN") || canBypassDept(plainSelect, user)) {
            // TENANT_ADMIN / 表级旁路规则：只按租户过滤（跨部门可见）
            plainSelect.setWhere(
                    wrapWithAnd(plainSelect.getWhere(), tenantCondition)
            );
        } else {
            // 其他角色: 按租户 + 部门过滤
            EqualsTo deptCondition = new EqualsTo(
                    new Column("dept_id"),
                    new StringValue(user.deptId())
            );
            // tenant_id = ? AND dept_id = ?
            AndExpression tenantAndDept = new AndExpression(tenantCondition, deptCondition);
            plainSelect.setWhere(
                    wrapWithAnd(plainSelect.getWhere(), tenantAndDept)
            );
        }

        return plainSelect.toString();
    }

    /**
     * 将新条件与现有 WHERE 条件用 AND 连接
     * 如果原来没有 WHERE，直接用新条件
     */
    private net.sf.jsqlparser.expression.Expression wrapWithAnd(
            net.sf.jsqlparser.expression.Expression existing,
            net.sf.jsqlparser.expression.Expression addition) {
        if (existing == null) {
            return addition;
        }
        return new AndExpression(existing, addition);
    }

    /**
     * 检查当前用户角色是否允许在查询该表时跳过部门过滤。
     */
    private boolean canBypassDept(PlainSelect plainSelect, UserContext user) {
        if (plainSelect.getFromItem() instanceof Table table) {
            String tableName = table.getName().toLowerCase();
            System.out.println("===DEPT_BYPASS CHECK: table=" + tableName + " roles=" + user.roles() + " DEPT_BYPASS=" + DEPT_BYPASS);
            Set<String> bypassRoles = DEPT_BYPASS.get(tableName);
            if (bypassRoles != null) {
                System.out.println("===DEPT_BYPASS HIT: bypassRoles=" + bypassRoles);
                return user.roles().stream().anyMatch(bypassRoles::contains);
            }
        } else {
            System.out.println("===DEPT_BYPASS getFromItem NOT Table: " + plainSelect.getFromItem().getClass().getSimpleName());
        }
        return false;
    }

    /**
     * 从 MyBatis 参数中提取 userId
     * 支持直接传 String 或 Map<String, Object> 含 userId key 的情况
     */
    private String extractUserId(Object parameter) {
        if (parameter instanceof String) {
            return (String) parameter;
        }
        if (parameter instanceof Map) {
            try {
                // MyBatis ParamMap.get() 对不存在的 key 抛 BindingException 而非返回 null
                Object val = ((Map<?, ?>) parameter).get("userId");
                return val != null ? val.toString() : null;
            } catch (Exception e) {
                return null;
            }
        }
        // ChatMessageMapper 等可能用 wrapper 对象
        try {
            // 反射取 memoryId / sessionId 字段（MysqlChatMemoryStore 用这些）
            java.lang.reflect.Field f = null;
            Class<?> clazz = parameter.getClass();
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                if (name.contains("session") || name.contains("memory") || name.contains("user")) {
                    f = field;
                    break;
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Object val = f.get(parameter);
                return val != null ? val.toString() : null;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
