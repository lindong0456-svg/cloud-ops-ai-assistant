package com.cloudops.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Tool 抽象基类 — 模板方法模式
 *
 * 设计思路（借鉴联通 AbstractProduct 文档构建器）：
 *   把"记日志 → 执行业务 → 捕获异常 → 计时"的公共逻辑放在父类，
 *   子类只写业务逻辑（传入 Supplier 即可），避免 4 个 Tool 重复写异常处理。
 *
 * 面试讲法：
 *   "我在联通做过 AbstractProduct 模板方法设计，这里复用了同样的思路——
 *    公共逻辑（日志/计时/异常）上提，业务逻辑下放，新增 Tool 只实现 doProcess。"
 *
 * 用法：
 *   public class AlarmQueryTool extends AbstractTool {
 *       @Tool("查告警")
 *       public List<MockAlarm> queryAlarms(@P("数量") int limit) {
 *           return execute("queryAlarms", () -> {
 *               // 业务逻辑写这里
 *               return alarmMapper.selectList(...);
 *           }).getData();
 *       }
 *   }
 */
@Slf4j
public abstract class AbstractTool {

    /**
     * 模板方法 — final 防止子类覆盖执行流程
     *
     * 执行顺序：记录开始 → 执行业务(Supplier) → 记录完成 → 异常兜底
     *
     * @param toolName 工具名称，用于日志标识
     * @param action   业务逻辑，用 Supplier 传入（Lambda）
     * @return ToolResult 统一返回
     */
    protected <T> ToolResult<T> execute(String toolName, Supplier<T> action) {
        long startTime = System.currentTimeMillis();
        try {
            log.info("[Tool] {} 开始执行", toolName);
            T result = action.get();
            long costMs = System.currentTimeMillis() - startTime;
            log.info("[Tool] {} 执行完成, 耗时 {}ms", toolName, costMs);
            return ToolResult.success(result, costMs);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("[Tool] {} 执行失败, 耗时 {}ms, 错误: {}", toolName, costMs, e.getMessage(), e);
            return ToolResult.fail(e.getMessage(), costMs);
        }
    }
}
