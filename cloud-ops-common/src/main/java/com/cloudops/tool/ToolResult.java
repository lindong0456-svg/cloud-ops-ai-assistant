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

    public static <T> ToolResult<T> fail(String error, long costMs) {
        return ToolResult.<T>builder()
                .success(false)
                .error(error)
                .costMs(costMs)
                .build();
    }
}
