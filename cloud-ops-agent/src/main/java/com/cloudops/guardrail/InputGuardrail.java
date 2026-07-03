package com.cloudops.guardrail;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 输入护轨服务 — 在用户输入到达 Agent 之前拦截危险指令
 *
 * 检查逻辑（三层过滤）：
 *   1. 护轨未启用 → 直接放行
 *   2. 命中敏感词 → 检查白名单
 *      2a. 命中白名单 → 放行（用户在"问知识"而非"要执行"）
 *      2b. 未命中白名单 → 拦截
 *   3. 未命中敏感词 → 放行
 *
 * 为什么用关键词匹配而不是 LLM 判断：
 *   - 关键词匹配：O(n) 毫秒级，零成本
 *   - LLM 判断：多一次 API 调用，延迟+成本
 *   - 运维场景的敏感操作有限且明确，关键词足够覆盖
 *   - 面试讲法："简单场景用规则，复杂场景才上模型，避免过度设计"
 *
 * 对标联通的 WAF 防护思路：
 *   联通 WAF 先用规则引擎拦截已知攻击，漏过的才送入深度检测；
 *   这里先用关键词拦截已知危险操作，护轨是第一道防线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputGuardrail {

    private final GuardrailConfig guardrailConfig;

    /**
     * 检查用户输入是否安全
     *
     * @param input 用户原始输入
     * @return 检查结果：allowed=true 放行，allowed=false 拦截（message 是拒绝提示）
     */
    public GuardrailResult check(String input) {
        // 1. 护轨未启用，直接放行
        if (!guardrailConfig.isEnabled()) {
            return GuardrailResult.allowed();
        }

        // 空输入放行（让 Agent 处理）
        if (input == null || input.isBlank()) {
            return GuardrailResult.allowed();
        }

        String lowerInput = input.toLowerCase(Locale.ROOT);

        // 2. 检查是否命中敏感词
        String hitKeyword = findBlockedKeyword(lowerInput);
        if (hitKeyword == null) {
            // 未命中敏感词，放行
            return GuardrailResult.allowed();
        }

        // 3. 命中敏感词，检查白名单
        if (containsWhitelistKeyword(lowerInput)) {
            // 命中白名单（如"如何删除""删除的SOP"），放行
            log.info("[Guardrail] 命中敏感词'{}'但含白名单词，放行: {}", hitKeyword, truncate(input));
            return GuardrailResult.allowed();
        }

        // 4. 拦截
        log.warn("[Guardrail] 输入被拦截! 命中敏感词 '{}', 输入: {}", hitKeyword, truncate(input));
        return GuardrailResult.blocked(guardrailConfig.getBlockedMessage(), hitKeyword);
    }

    /**
     * 查找命中的敏感词（返回第一个命中的）
     */
    private String findBlockedKeyword(String lowerInput) {
        for (String keyword : guardrailConfig.getBlockedKeywords()) {
            if (keyword != null && lowerInput.contains(keyword.toLowerCase(Locale.ROOT))) {
                return keyword;
            }
        }
        return null;
    }

    /**
     * 检查是否包含白名单词
     */
    private boolean containsWhitelistKeyword(String lowerInput) {
        for (String keyword : guardrailConfig.getWhitelistKeywords()) {
            if (keyword != null && lowerInput.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 截断日志显示（避免日志过长）
     */
    private String truncate(String input) {
        return input.length() > 50 ? input.substring(0, 50) + "..." : input;
    }

    /**
     * 护轨检查结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GuardrailResult {
        /** 是否放行 */
        private boolean allowed;
        /** 拦截时的提示信息 */
        private String message;
        /** 命中的敏感词（拦截时有值） */
        private String hitKeyword;

        public static GuardrailResult allowed() {
            return new GuardrailResult(true, null, null);
        }

        public static GuardrailResult blocked(String message, String hitKeyword) {
            return new GuardrailResult(false, message, hitKeyword);
        }
    }
}
