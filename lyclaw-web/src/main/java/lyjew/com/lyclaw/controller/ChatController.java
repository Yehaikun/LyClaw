package lyjew.com.lyclaw.controller;

import jakarta.annotation.PostConstruct;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.engine.impl.DefaultEngine;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.storage.ConfigStorage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 流式聊天测试接口。
 * <p>提供 SSE（Server-Sent Events）端点供前端流式输出测试。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@CrossOrigin("*")
public class ChatController {

    private final DefaultEngine defaultEngine;
    private final ConfigStorage configStorage;
    private final ModelAdapterFactory adapterFactory;

    public ChatController(DefaultEngine defaultEngine,
                          ConfigStorage configStorage,
                          ModelAdapterFactory adapterFactory) {
        this.defaultEngine = defaultEngine;
        this.configStorage = configStorage;
        this.adapterFactory = adapterFactory;
    }

    /**
     * 启动时自动初始化适配器配置。
     * <p>从 configStorage 读取已有配置，加载适配器。
     * 如果没有配置，会用默认的 DeepSeek 配置。</p>
     */
    @PostConstruct
    public void init() {
        try {
            // DeepSeek 配置（用测试中的 API Key）
            ModelConfig dsConfig = ModelConfig.builder()
                    .id("cfg-deepseek-default")
                    .name("deepseek-openai").provider("deepseek-openai")
                    .apiKey("sk-b1da578246114c2383616f49b5651f1d")
                    .model("deepseek-chat")
                    .baseUrl("https://api.deepseek.com")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            configStorage.save(dsConfig);
            adapterFactory.getConfiguredAdapter(dsConfig);
            log.info("DeepSeek 适配器已初始化");
        } catch (Exception e) {
            log.error("适配器初始化失败", e);
        }
    }

    /**
     * 流式对话接口。
     * <p>每次请求自动创建新会话（无 sessionId == 新会话）。</p>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        // 确保有 sessionId，没有就新生成
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(UUID.randomUUID().toString());
        }
        // 确保消息有 createdAt
        if (request.getMessages() != null) {
            for (Message msg : request.getMessages()) {
                if (msg.getCreatedAt() == null) {
                    msg.setCreatedAt(LocalDateTime.now());
                }
            }
        }
        // 标记为流式
        request.setStream(true);

        // adapter 返回的每个 token 已经是 SSE 格式 (data:xxx)，
        // 用 map 提取 content 字段的值，前端直接拼接即可
        return defaultEngine.execute(request)
                .map(line -> {
                    if (line == null || line.isBlank()) return "";
                    // DeepSeek 格式: data: {...} 或 data:[DONE]
                    if (line.startsWith("data:")) {
                        String jsonPart = line.substring(5).trim();
                        if ("[DONE]".equals(jsonPart)) return "";
                        // 尝试解析 JSON 提取 content
                        try {
                            int idx = jsonPart.indexOf("\"content\":\"");
                            if (idx > 0) {
                                int start = idx + 11;
                                int end = jsonPart.indexOf("\"", start);
                                return end > start ? jsonPart.substring(start, end) : "";
                            }
                            return "";
                        } catch (Exception e) {
                            return "";
                        }
                    }
                    return "";
                })
                .filter(s -> !s.isEmpty());
    }
}
