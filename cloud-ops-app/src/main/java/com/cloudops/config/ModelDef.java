package com.cloudops.config;

import lombok.Data;

/**
 * 模型定义 — 对应 application.yml 中 app.models[].* 的配置项
 *
 * 每个模型声明一组独立的连接参数，便于后续接入多模态大模型（如通义千问 VL）
 * 或换用其他 OpenAI 兼容供应商，无需改动代码，只改配置。
 */
@Data
public class ModelDef {
    /** 模型唯一 key，如 deepseek / qwen-vl */
    private String key;
    /** 展示名称，用于前端徽标与切换下拉 */
    private String label;
    /** 供应商标识，openai / dashscope 等（仅用于展示与扩展判断） */
    private String provider;
    /** API Key（支持 ${ENV} 占位） */
    private String apiKey;
    /** 兼容 OpenAI 的 base-url */
    private String baseUrl;
    /** 模型名，如 deepseek-v4-pro / qwen-vl-max */
    private String modelName;
    /** 采样温度 */
    private Double temperature = 0.7;
    /** 最大输出 token */
    private Integer maxTokens = 4096;
}
