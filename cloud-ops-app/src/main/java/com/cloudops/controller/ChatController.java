package com.cloudops.controller;

import com.cloudops.config.ModelManager;
import com.cloudops.guardrail.InputGuardrail;
import com.cloudops.observability.RequestMetrics;
import com.cloudops.security.context.RequestContextStore;
import com.cloudops.security.context.SecurityContext;
import com.cloudops.security.context.UserContext;
import com.cloudops.tool.RequestContextHolder;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    private final ModelManager modelManager;
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
            String reply = modelManager.getOpsAssistant().chat(userId, message);
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
            return modelManager.getOpsAssistant().chat(userId, message);
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
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("[Chat-SSE] 流式请求 userId={}, loginUser={}, message={}", userId, auth != null ? auth.getName() : "(匿名)", truncate(message));

        // ★ 禁用 Tomcat response buffer（必须在 getOutputStream() 前）
        response.setBufferSize(0);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        ServletOutputStream out = response.getOutputStream();
        long requestStartTime = System.currentTimeMillis();

        try {
            // 1. 护轨检查
            InputGuardrail.GuardrailResult guardrailResult = inputGuardrail.check(message);
            if (!guardrailResult.isAllowed()) {
                log.info("[Chat-SSE] 护轨拦截 userId={}, 命中词={}", userId, guardrailResult.getHitKeyword());
                writeSseEvent(out, "token", Map.of("content", "[护轨拦截] " + guardrailResult.getHitKeyword()));
                writeSseEvent(out, "token", Map.of("content", guardrailResult.getMessage()));
                writeSseEvent(out, "done", Map.of());
                return;
            }

            // ★ 安全上下文跨线程传播：将当前 HTTP 线程的 UserContext 存入请求级存储
            //   LangChain4j 的 Tool 执行可能在线程池（lTaskExecutor）上运行，
            //   ThreadLocal 不会自动传播。DataPermissionInterceptor 和 Tool 权限校验
            //   会从 RequestContextStore 兜底获取用户身份。
            UserContext currentUser = SecurityContext.capture();
            if (currentUser != null) {
                RequestContextStore.put(userId, currentUser);
                // ★ 设置当前请求 userId（供 PermissionCheckerConfig 回退使用）
                RequestContextHolder.setCurrentUserId(userId);
                log.debug("[Chat-SSE] 已传播安全上下文: userId={}, user={}", userId, currentUser.username());
            }

            // ★ 初始化本次请求的可观测性指标快照（done 事件时回传前端）
            RequestMetrics.create(userId);

            // 2. TokenStream → Flux.create() 桥接 + 工具执行事件
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean hasError = new AtomicBoolean(false);
            AtomicInteger toolCallCount = new AtomicInteger(0);
            AtomicLong firstTokenTime = new AtomicLong();
            AtomicReference<TokenUsage> finalTokenUsage = new AtomicReference<>();

            TokenStream tokenStream = modelManager.getOpsAssistant().chatStream(userId, message);

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
                        Map<String, Object> doneFields = buildDoneFields(
                                finalTokenUsage.get(), userId, toolCallCount.get(),
                                firstTokenTime.get() > 0 ? firstTokenTime.get() - requestStartTime : 0);
                        writeSseEvent(out, "done", doneFields);
                        hasError.set(true);
                        latch.countDown();
                    })
                    .doOnComplete(() -> {
                        log.info("[Chat-SSE] 流式完成, userId={}", userId);
                        Map<String, Object> doneFields = buildDoneFields(
                                finalTokenUsage.get(), userId, toolCallCount.get(),
                                firstTokenTime.get() > 0 ? firstTokenTime.get() - requestStartTime : 0);
                        writeSseEvent(out, "done", doneFields);
                        latch.countDown();
                    })
                    .subscribe();

            tokenStream.start();

            // ★ 带超时的 await（120秒）
            //   正常情况：onComplete/doOnComplete → latch.countDown() → 立即返回
            //   异常情况：LangChain4j 内部吞异常导致 onComplete/onError 都不触发
            //            → 超时后主动写 error+done 关闭流，避免 servlet 线程永久阻塞 + 前端卡死
            try {
                if (!latch.await(120, TimeUnit.SECONDS)) {
                    // 超时 — 说明 TokenStream 的 onComplete/onError 都没触发（内部异常被吞）
                    log.error("[Chat-SSE] latch.await 超时(120s)，TokenStream 可能内部异常，userId={}", userId);
                    writeSseEvent(out, "error", Map.of("message", "响应超时：Agent 处理时间过长或遇到内部异常"));
                    writeSseEvent(out, "done", Map.of());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            log.info("[Chat-SSE] servlet 线程释放, userId={}", userId);

        } catch (Exception e) {
            // ★ 捕获所有异常，写 SSE error 事件，不让异常传播到 Spring Boot /error 转发
            //   （否则 /error 路径无 SecurityContext → 触发 401 误判）
            log.error("[Chat-SSE] 异常, userId={}, error={}", userId, e.getMessage(), e);
            writeSseEvent(out, "error", Map.of("message", "Agent 调用异常: " + e.getMessage()));
            writeSseEvent(out, "done", Map.of());
        } finally {
            // ★ 清理请求级安全上下文与可观测指标（无论成功/异常都执行）
            RequestMetrics.remove(userId);
            RequestContextStore.remove(userId);
            RequestContextHolder.clear();
        }
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

    /**
     * 组装 done 事件的统计字段：token 用量 + 工具调用数 + 本次请求可观测明细(RAG/工具) + 当前模型名
     * 前端 stats-bar 据此展示；model 字段用于前端检测模型切换并弹提示。
     */
    private Map<String, Object> buildDoneFields(TokenUsage tu, String userId, int toolCallCount, long firstTokenMs) {
        Map<String, Object> doneFields = new LinkedHashMap<>();
        doneFields.put("inputTokens", tu != null && tu.inputTokenCount() != null ? tu.inputTokenCount() : 0);
        doneFields.put("outputTokens", tu != null && tu.outputTokenCount() != null ? tu.outputTokenCount() : 0);
        doneFields.put("totalTokens", tu != null && tu.totalTokenCount() != null ? tu.totalTokenCount() : 0);
        doneFields.put("toolCallCount", toolCallCount);
        doneFields.put("firstTokenMs", firstTokenMs);
        // 本次请求的可观测明细（RAG 召回数 / 工具成功失败）
        RequestMetrics.Snapshot metrics = RequestMetrics.get(userId);
        if (metrics != null) {
            doneFields.putAll(metrics.toMap());
        }
        // 当前激活模型名（前端徽标 + 切换提示）
        if (modelManager.getActiveDef() != null) {
            doneFields.put("model", modelManager.getActiveDef().getLabel());
        }
        return doneFields;
    }

    private String truncate(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
