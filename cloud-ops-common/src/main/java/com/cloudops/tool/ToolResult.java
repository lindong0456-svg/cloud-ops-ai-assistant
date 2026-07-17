package com.cloudops.tool;

import lombok.Builder;
import lombok.Data;

/**
 * Tool 统一返回结构
 *
 * 所有 Tool 的执行结果都包装成 ToolResult，统一 success/data/error/costMs 四个字段。
 * 联通计费中心的统一响应格式（code/info/data/success），
 * 这里简化为 success/data/error/costMs，加了执行耗时用于性能监控。
 */
@Data
@Builder
public class ToolResult<T> {
    private boolean success;
    private T data;
    private String error;
    private long costMs;

    public static <T> ToolResult<T> success(T data, long costMs) {
        return ToolResult.<T>builder()
                .success(true)
                .data(data)
                .costMs(costMs)
                .build();
    }

    /**
     * 失败结果 — 将 error 信息同步写入 data 字段
     *
     * 设计意图：
     *   所有 Tool 子类通过 .getData() 取返回值传给 LLM。
     *   若 data=null，LLM 拿到 null observation 可能导致 LangChain4j 内部异常被吞，
     *   进而导致 onComplete/onError 都不触发 → latch 不 countDown → SSE 流永久挂起。
     *   因此 fail() 必须保证 data 非空，让 LLM 能读到"权限不足"等错误信息并优雅降级。
     *
     * 注意：此处利用了 Java 泛型擦除，T 在运行时就是 Object，
     *       String 赋值给 T data 在编译期有 unchecked warning 但运行时完全安全。
     */
    @SuppressWarnings("unchecked")
    public static <T> ToolResult<T> fail(String error, long costMs) {
        return ToolResult.<T>builder()
                .success(false)
                .data((T) error)          // ★ 关键：error 同步写入 data，保证 .getData() 不返回 null
                .error(error)
                .costMs(costMs)
                .build();
    }
}
