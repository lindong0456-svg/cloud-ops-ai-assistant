package com.cloudops.security.interceptor;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
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

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        // 1. 获取当前用户上下文
        UserContext user = SecurityContext.get();
        if (user == null) {
            // 未登录（如登录接口本身），不拦截
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

        if (user.roles().contains("TENANT_ADMIN")) {
            // TENANT_ADMIN: 只按租户过滤
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
}
