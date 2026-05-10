package lyjew.com.lyclaw.controller;

import lyjew.com.lyclaw.facade.LyClawFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.util.SseEmitterWriter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;

/**
 * 流式聊天接口。
 * <p>使用 StreamingResponseBody 手动写 SSE 格式，每个内容块写完后立即 flush，
 * 前端能逐 token 收到数据。</p>
 *
 * <p>数据流：</p>
 * <ol>
 *   <li>Engine 返回 Flux&lt;String&gt;（元素为 OpenAI SSE 格式行：data: {...json...}）</li>
 *   <li>Controller 解析每个元素的 JSON，提取 content 字段</li>
 *   <li>用标准 event:message\ndata:content\n\n 格式写到 OutputStream，flush</li>
 *   <li>工具调用事件（包含 "type":"tool_call"）原样透传</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatController {

    private final LyClawFacade lyClawFacade;

    /** 复用 ObjectMapper 实例，减少频繁 new */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public ChatController(LyClawFacade lyClawFacade) {
        this.lyClawFacade = lyClawFacade;
    }

    @PostMapping("/stream")
    public ResponseEntity<StreamingResponseBody> streamChat(@RequestBody ChatRequest request) {
        // Initialize session ID if not provided
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(UUID.randomUUID().toString());
        }
        if (request.getMessages() != null) {
            for (Message msg : request.getMessages()) {
                if (msg.getCreatedAt() == null) {
                    msg.setCreatedAt(LocalDateTime.now());
                }
            }
        }
        request.setStream(true);

        // Execute via LyClawFacade
        Flux<String> flux = lyClawFacade.chat(request);

        StreamingResponseBody body = outputStream -> {
            CountDownLatch latch = new CountDownLatch(1);

            flux.subscribe(
                line -> {
                    if (line == null || line.isBlank()) return;
                    try {
                        SseEmitterWriter.writeEvent(outputStream, line, MAPPER);
                    } catch (Exception e) {
                        log.warn("SSE write error: {}", e.getMessage());
                    }
                },
                error -> {
                    log.error("SSE stream error: {}", error.getMessage());
                    latch.countDown();
                },
                latch::countDown
            );

            try { latch.await(); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try { outputStream.flush(); } catch (Exception ignored) {}
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

}
