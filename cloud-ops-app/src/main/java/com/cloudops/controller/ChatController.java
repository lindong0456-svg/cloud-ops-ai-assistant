package com.cloudops.controller;

import com.cloudops.agent.OpsAssistant;
import com.cloudops.guardrail.InputGuardrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 对话接口
 *
 * ★ 第11轮：禁用 Tomcat response buffer + 直接写 ServletOutputStream 字节
 *
 * 之前用 PrintWriter + flush()，但 Tomcat 的 response output buffer（默认 8KB）
 * 和 OutputStreamWriter 的字符编码缓冲层会囤数据，flush() 只把数据从
 * PrintWriter 推到 Coyote buffer，没到网络层，导致所有 token 攒到最后一起发。
 *
 * 改为：
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
    public Map<String, Object> chatPost(
            @RequestParam(defaultValue = "default-user") String userId,
            @RequestParam String message
    ) {
        return chat(userId, message);
    }

    @GetMapping(value = "/chat/text", produces = MediaType.TEXT_PLAIN_VALUE)
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
     * SSE 流式对话接口 — 直接写 ServletOutputStream，每 token flush
     *
     * 不用 SseEmitter，不用 Reactor Flux 的 doOnNext，
     * 直接在 LangChain4j 的回调里写 response 并 flush。
     *
     * ★ setBufferSize(0) + ServletOutputStream 是绕过 Tomcat 缓冲的关键：
     *   - PrintWriter 走 OutputStreamWriter，有字符编码缓冲层
     *   - ServletOutputStream 直接写字节，flush() 直达 socket
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chatStream(
            @RequestParam(defaultValue = "default-user") String userId,
            @RequestParam String message,
            HttpServletResponse response
    ) throws Exception {
        log.info("[Chat-SSE] 流式请求 userId={}, message={}", userId, truncate(message));

        // ★ 禁用 Tomcat response buffer（必须在 getOutputStream() 前）
        response.setBufferSize(0);
        // 设置 SSE 响应头
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        // ★ 直接用 ServletOutputStream，绕过 OutputStreamWriter 编码缓冲
        ServletOutputStream out = response.getOutputStream();

        // 1. 护轨检查
        InputGuardrail.GuardrailResult guardrailResult = inputGuardrail.check(message);
        if (!guardrailResult.isAllowed()) {
            log.info("[Chat-SSE] 护轨拦截 userId={}, 命中词={}", userId, guardrailResult.getHitKeyword());
            writeSse(out, "[护轨拦截] " + guardrailResult.getHitKeyword());
            writeSse(out, guardrailResult.getMessage());
            writeSse(out, "[DONE]");
            return;
        }

        // 2. 用 CountDownLatch 阻塞 servlet 线程，直到 Flux 完成
        //    Flux 在 boundedElastic 线程上订阅，每个 token 直接写 response 并 flush
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean hasError = new AtomicBoolean(false);

        opsAssistant.chatStream(userId, message)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(token -> {
                    writeSse(out, token);
                })
                .doOnError(error -> {
                    log.error("[Chat-SSE] Flux 异常, userId={}", userId, error);
                    writeSse(out, "[ERROR] " + error.getMessage());
                    writeSse(out, "[DONE]");
                    hasError.set(true);
                    latch.countDown();
                })
                .doOnComplete(() -> {
                    log.info("[Chat-SSE] 流式完成, userId={}", userId);
                    writeSse(out, "[DONE]");
                    latch.countDown();
                })
                .subscribe();

        // 3. 阻塞等待 Flux 完成（servlet 线程）
        //    Spring MVC 在 servlet 线程返回前不会关闭 response
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("[Chat-SSE] servlet 线程释放, userId={}", userId);
    }

    /**
     * 写 SSE data 并立即 flush — 直接写字节到 ServletOutputStream
     */
    private void writeSse(ServletOutputStream out, String data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            out.write(("data:" + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            log.error("[Chat-SSE] 写入失败: {}", truncate(data), e);
        }
    }

    private String truncate(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
