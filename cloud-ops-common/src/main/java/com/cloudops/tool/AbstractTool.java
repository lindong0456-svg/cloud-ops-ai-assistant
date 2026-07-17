package com.cloudops.tool;

import com.cloudops.tool.annotation.RequiredPermission;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.Supplier;

/**
 * Tool 抽象基类 — 模板方法模式
 *
 * 设计思路（借鉴联通 AbstractProduct 文档构建器）：
 *   把"权限校验 → 记录日志 → 执行业务 → 捕获异常 → 计时"的公共逻辑放在父类，
 *   子类只写业务逻辑（传入 Supplier 即可），避免 4 个 Tool 重复写异常处理。
 *
 * ★ 权限控制机制：
 *   通过 @RequiredPermission 注解声明每个 Tool 所需权限。
 *   execute() 模板方法在执行业务前自动：
 *     1. 用 StackWalker 获取调用方法的 @RequiredPermission 注解
 *     2. 读取注解的 value() 得到所需权限编码
 *     3. 调用 ToolPermissionChecker.hasPermission() 校验当前用户（Spring 注入）
 *     4. 无权限 → 返回 ToolResult.fail()，不执行业务逻辑，LLM 收到"无权限"提示后直接回答
 *
 * ★ 依赖解耦：
 *   - @RequiredPermission 注解和 ToolPermissionChecker 接口都在 common 模块
 *   - ToolPermissionChecker 的实现由 security 模块提供（读 SecurityContext）
 *   - 避免 common ↔ security 循环依赖
 */
@Slf4j
public abstract class AbstractTool {

    public static MeterRegistry meterRegistry;

    /** 权限检查器（由 security 模块通过 Spring 注入） */
    private static ToolPermissionChecker permissionChecker;

    /** StackWalker 实例 — 用于获取调用方法上的注解（Java 9+ API） */
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(
            StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * Spring 注入权限检查器（各 Tool 都是 Spring Bean，@Autowired 在初始化时执行）
     */
    @Autowired
    public void setPermissionChecker(ToolPermissionChecker checker) {
        AbstractTool.permissionChecker = checker;
    }

    /**
     * 模板方法 — final 防止子类覆盖执行流程
     *
     * 执行顺序：权限校验 → 记录开始 → 执行业务(Supplier) → 记录完成 → 异常兜底
     *
     * @param toolName 工具名称，用于日志标识
     * @param action   业务逻辑，用 Supplier 传入（Lambda）
     * @return ToolResult 统一返回
     */
    protected <T> ToolResult<T> execute(String toolName, Supplier<T> action) {
        // ===== 权限校验 =====
        RequiredPermission rp = findRequiredPermission();
        if (rp != null && permissionChecker != null) {
            String requiredPerm = rp.value();
            if (!permissionChecker.hasPermission(requiredPerm)) {
                log.warn("[Tool] {} 权限不足: 需要={}", toolName, requiredPerm);
                return ToolResult.fail("权限不足：需要「" + requiredPerm + "」权限才能使用此功能", 0);
            }
        }

        long startTime = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            log.info("[Tool] {} 开始执行", toolName);
            T result = action.get();
            long costMs = System.currentTimeMillis() - startTime;
            log.info("[Tool] {} 执行完成, 耗时 {}ms", toolName, costMs);
            if (meterRegistry != null) {
                sample.stop(Timer.builder("tool.call.duration")
                        .tag("tool", toolName)
                        .tag("status", "success")
                        .register(meterRegistry));
                meterRegistry.counter("tool.call.total",
                        "tool", toolName,
                        "status", "success").increment();
            }
            return ToolResult.success(result, costMs);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("[Tool] {} 执行失败, 耗时 {}ms, 错误: {}", toolName, costMs, e.getMessage(), e);
            if (meterRegistry != null) {
                sample.stop(Timer.builder("tool.call.duration")
                        .tag("tool", toolName)
                        .tag("status", "error")
                        .register(meterRegistry));
                meterRegistry.counter("tool.call.total",
                        "tool", toolName,
                        "status", "error").increment();
            }
            return ToolResult.fail(e.getMessage(), costMs);
        }
    }

    /**
     * 通过 StackWalker 获取调用 execute() 的方法上的 @RequiredPermission 注解
     *
     * 调用链：Tool方法() → AbstractTool.execute() → findRequiredPermission()
     * 所以调用 execute() 的那个方法就是带 @Tool + @RequiredPermission 注解的业务方法
     */
    private RequiredPermission findRequiredPermission() {
        try {
            return STACK_WALKER.walk(frames -> frames
                    .skip(3)                          // 跳过 findRequiredPermission / execute / executeOrThrow 自身
                    .findFirst()                       // 第一个就是 Tool 方法
                    .map(f -> {
                        try {
                            return f.getDeclaringClass().getDeclaredMethod(
                                    f.getMethodName(), f.getMethodType().parameterArray());
                        } catch (NoSuchMethodException e) {
                            return null;
                        }
                    })
                    .map(m -> m != null ? m.getAnnotation(RequiredPermission.class) : null)
                    .orElse(null));
        } catch (Exception e) {
            log.debug("[Tool] 获取 @RequiredPermission 注解失败（可能未标注，忽略即可）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 安全执行并返回数据 — 失败时抛出 RuntimeException
     *
     * 替代直接调用 execute(...).getData() 的模式。
     * 失败时抛出异常而非返回错误字符串，避免泛型擦除导致 ClassCastException
     * （例如 ToolResult.fail() 将 String 塞入 List<MockAlarm> 的泛型 data）。
     * LangChain4j DefaultToolExecutor 会捕获 RuntimeException 并将异常信息传给 LLM。
     */
    protected <T> T executeOrThrow(String toolName, Supplier<T> action) {
        ToolResult<T> result = execute(toolName, action);
        if (!result.isSuccess()) {
            throw new RuntimeException("[" + toolName + "] " + result.getError());
        }
        return result.getData();
    }
}
