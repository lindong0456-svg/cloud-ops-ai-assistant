package com.cloudops.controller;

import com.cloudops.agent.OpsAssistant;
import com.cloudops.guardrail.InputGuardrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 对话接口
 *   1. response.setBufferSize(0) — 必须在 getOutputStream() 前调用，禁用 Tomcat 缓冲
 *   2. 用 ServletOutputStream 直接写 UTF-8 字节 — 绕过 OutputStreamWriter 编码缓冲
 *   3. out.flush() — 直接刷到 socket
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private final OpsAssistant opsAssistant;
    private final InputGuardrail inputGuardrail;
    private final ObjectMapper objectMapper;

    @GetMapping("/chat")
    @PreAuthorize("hasAuthority('agent:chat')")
    public Map<String, Object> chat(
            @RequestParam(defaultValue = "default-user") String userId,
            @RequestParam String message
    ) {
        long startTime = System.currentTimeMillis();
        log.info("[Chat] 收到请求 userId={}, message={}", userId, truncate(message));

        InputGuardrail.GuardrailResult guardrailResult = inputGuardrail.check(message);
        if (!guardrailResult.isAllowed()) {
            long costMs = System.currentTimeMillis() - startTime;
            log.info("[Chat] 护轨拦截, userId={}, 命中词={}", userId, guardrailResult.getHitKeyword());
            return Map.of(
                    "status", "blocked",
                    "userId", userId,
                    "hitKeyword", guardrailResult.getHitKeyword(),
                    "reply", guardrailResult.getMessage(),
                    "costMs", costMs
            );
        }

        try {
            String reply = opsAssistant.chat(userId, message);
            long costMs = System.currentTimeMillis() - startTime;
            log.info("[Chat] Agent 响应完成, userId={}, 耗时={}ms", userId, costMs);
            return Map.of("status", "success", "userId", userId, "reply", reply, "costMs", costMs);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("[Chat] Agent 调用失败, userId={}", userId, e);
            return Map.of("status", "error", "userId", userId, "error", e.getMessage(), "costMs", costMs);
        }
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('agent:chat')")
    public Map<String, Object> chatPost(
            @RequestParam(defaultValue = "default-user") String userId,
            @RequestParam String message
    ) {
        return chat(userId, message);
    }

    @GetMapping(value = "/chat/text", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasAuthority('agent:chat')")
    public String chatText(
            @RequestParam(defaultValue = "default-user") String userId,
            @RequestParam String message
    ) {
        InputGuardrail.GuardrailResult guardrailResult = inputGuardrail.check(message);
        if (!guardrailResult.isAllowed()) {
            return "[护轨拦截] 命中敏感词: " + guardrailResult.getHitKeyword() + "\n\n" + guardrailResult.getMessage();
        }
        try {
            return opsAssistant.chat(userId, message);
        } catch (Exception e) {
            return "[Agent 调用失败] " + e.getMessage();
        }
    }

    /**
     * ★ 诊断端点：每 500ms 发一个 token，用于验证 SSE 传输链路
     *
     * 如果这个端点也不流式 → 传输层有问题（Tomcat/浏览器缓冲）
     * 如果这个端点流式正常 → LangChain4j 的流式有问题
     *
     * 测试方法（推荐用 curl，浏览器地址栏会缓冲 text/event-stream）：
     *   curl -N http://localhost:8080/api/agent/stream-test
     *   （-N 禁用 curl 缓冲，能看到逐条实时输出）
     *
     * ⚠️ 不要直接在浏览器地址栏打开 — Chrome 对地址栏导航的 text/event-stream
     *    会做预渲染缓冲，看不到逐字效果。只能用 curl -N 或 EventSource 测试。
     */
    @GetMapping(value = "/stream-test", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('agent:chat')")
    public void streamTest(HttpServletResponse response) throws Exception {
        // ★ setBufferSize(0) 必须在 getOutputStream() 前调用
        response.setBufferSize(0);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        // ★ 直接用 ServletOutputStream 写字节，绕过 OutputStreamWriter 的编码缓冲
        ServletOutputStream out = response.getOutputStream();
        long requestStartTime = System.currentTimeMillis();
        log.info("[Stream-Test] 开始发送测试 token");

        for (int i = 0; i < 10; i++) {
            String data = "token-" + i;
            String json = objectMapper.writeValueAsString(data);
            out.write(("data:" + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            log.info("[Stream-Test] 发送 token-{}", i);
            Thread.sleep(500);
        }

        out.write(("data:" + objectMapper.writeValueAsString("[DONE]") + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        log.info("[Stream-Test] 完成");
    }

    /**
     * SSE 流式对话接口 — TokenStream → Flux.create() → 直接写 ServletOutputStream
     *
     * ★ SSE 事件类型（前端按 type 字段分发）：
     *   {"type":"token",     "content":"..."}
     *   {"type":"tool-start","toolName":"alarmQuery","args":"..."}
     *   {"type":"tool-end",  "toolName":"alarmQuery","result":"..."}
     *   {"type":"done"}
     *   {"type":"error",    "message":"..."}
     *
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('agent:chat')")
    public void chatStream(
            @RequestParam(defaultValue = "default-user") String userId,
            @RequestParam String message,
            HttpServletResponse response
    ) throws Exception {
        log.info("[Chat-SSE] 流式请求 userId={}, message={}", userId, truncate(message));

        // ★ 禁用 Tomcat response buffer（必须在 getOutputStream() 前）
        response.setBufferSize(0);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        ServletOutputStream out = response.getOutputStream();
        long requestStartTime = System.currentTimeMillis();

        // 1. 护轨检查
        InputGuardrail.GuardrailResult guardrailResult = inputGuardrail.check(message);
        if (!guardrailResult.isAllowed()) {
            log.info("[Chat-SSE] 护轨拦截 userId={}, 命中词={}", userId, guardrailResult.getHitKeyword());
            writeSseEvent(out, "token", Map.of("content", "[护轨拦截] " + guardrailResult.getHitKeyword()));
            writeSseEvent(out, "token", Map.of("content", guardrailResult.getMessage()));
            writeSseEvent(out, "done", Map.of());
            return;
        }

        // 2. TokenStream → Flux.create() 桥接 + 工具执行事件
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean hasError = new AtomicBoolean(false);
        AtomicInteger toolCallCount = new AtomicInteger(0);
        AtomicLong firstTokenTime = new AtomicLong();
        AtomicReference<TokenUsage> finalTokenUsage = new AtomicReference<>();

        TokenStream tokenStream = opsAssistant.chatStream(userId, message);

        // ★ 注册工具执行回调 — 排障过程透明化的关键
        tokenStream.onToolExecuted((ToolExecution toolExecution) -> {
            toolCallCount.incrementAndGet();
            String toolName = toolExecution.request().name();
            String args = toolExecution.request().arguments();
            log.info("[Chat-SSE] 工具开始执行: toolName={}, args={}", toolName, truncate(args));
            writeSseEvent(out, "tool-start", Map.of("toolName", toolName, "args", args));
            // 工具执行结果在同一个回调里（同步执行完成后 result 已有值）
            String result = toolExecution.result() != null
                    ? toolExecution.result().toString()
                    : "(无返回内容)";
            log.info("[Chat-SSE] 工具执行完成: toolName={}, resultLen={}", toolName,
                    result != null ? result.length() : 0);
            writeSseEvent(out, "tool-end", Map.of("toolName", toolName, "result", result));
        });

        Flux<String> tokenFlux = Flux.create((FluxSink<String> sink) -> {
            tokenStream.onPartialResponse(token -> {
                if (firstTokenTime.get() == 0) firstTokenTime.set(System.currentTimeMillis());
                sink.next(token);
            });
            tokenStream.onCompleteResponse(chatResponse -> {
                if (chatResponse.tokenUsage() != null) finalTokenUsage.set(chatResponse.tokenUsage());
                log.info("[Chat-SSE] TokenStream 完成, userId={}", userId);
                sink.complete();
            });
            tokenStream.onError(error -> {
                log.error("[Chat-SSE] TokenStream 异常, userId={}", userId, error);
                sink.error(error);
            });
        }, FluxSink.OverflowStrategy.BUFFER);

        // 3. 订阅 Flux
        tokenFlux
                .doOnNext(token -> writeSseEvent(out, "token", Map.of("content", token)))
                .doOnError(error -> {
                    log.error("[Chat-SSE] 流式异常, userId={}", userId, error);
                    writeSseEvent(out, "error", Map.of("message", error.getMessage()));
                    Map<String, Object> doneFields = new LinkedHashMap<>();
                    TokenUsage tu = finalTokenUsage.get();
                    doneFields.put("inputTokens", tu != null ? (tu.inputTokenCount() != null ? tu.inputTokenCount() : 0) : 0);
                    doneFields.put("outputTokens", tu != null ? (tu.outputTokenCount() != null ? tu.outputTokenCount() : 0) : 0);
                    doneFields.put("totalTokens", tu != null ? (tu.totalTokenCount() != null ? tu.totalTokenCount() : 0) : 0);
                    doneFields.put("toolCallCount", toolCallCount.get());
                    long ft = firstTokenTime.get();
                    doneFields.put("firstTokenMs", ft > 0 ? ft - requestStartTime : 0);
                    writeSseEvent(out, "done", doneFields);
                    hasError.set(true);
                    latch.countDown();
                })
                .doOnComplete(() -> {
                    log.info("[Chat-SSE] 流式完成, userId={}", userId);
                    Map<String, Object> doneFields = new LinkedHashMap<>();
                    TokenUsage tu = finalTokenUsage.get();
                    doneFields.put("inputTokens", tu != null ? (tu.inputTokenCount() != null ? tu.inputTokenCount() : 0) : 0);
                    doneFields.put("outputTokens", tu != null ? (tu.outputTokenCount() != null ? tu.outputTokenCount() : 0) : 0);
                    doneFields.put("totalTokens", tu != null ? (tu.totalTokenCount() != null ? tu.totalTokenCount() : 0) : 0);
                    doneFields.put("toolCallCount", toolCallCount.get());
                    long ft = firstTokenTime.get();
                    doneFields.put("firstTokenMs", ft > 0 ? ft - requestStartTime : 0);
                    writeSseEvent(out, "done", doneFields);
                    latch.countDown();
                })
                .subscribe();

        tokenStream.start();

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("[Chat-SSE] servlet 线程释放, userId={}", userId);
    }

    /**
     * 写结构化 SSE 事件 — type 区分 token / tool-start / tool-end / done / error
     */
    private void writeSseEvent(ServletOutputStream out, String type, Map<String, Object> fields) {
        try {
            Map<String, Object> event = new LinkedHashMap<>(fields);
            event.put("type", type);
            String json = objectMapper.writeValueAsString(event);
            out.write(("data:" + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            log.error("[Chat-SSE] SSE写入失败: type={}", type, e);
        }
    }

    private String truncate(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
