package lyjew.com.lyclaw.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.chat.AbstractChatModel;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * OpenAI 协议 ChatModel 实现——"配置即 Provider"的核心适配器。
 *
 * <p>覆盖 85%+ 的 AI Provider（DeepSeek、OpenAI、Groq、MiniMax 等所有 OpenAI 兼容端点），
 * 只需改 YAML 配置中的 baseUrl 和 apiKey 就能切换 Provider，无需写 Java 代码。
 *
 * <p>使用 WebClient（Reactive）替代 OkHttp，支持结构化 SSE 解析：
 * 自动拼接 content、增量聚合 tool_calls、提取 thinking 内容、累加 usage。
 */
public class OpenAiProtocolChatModel extends AbstractChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProtocolChatModel.class);

    private static final String DEFAULT_MODEL = "default";
    private static final String ENDPOINT = "/chat/completions";

    private final String provider;
    private final ModelCapabilities capabilities;
    private final ObjectMapper objectMapper;
    private WebClient webClient;

    /**
     * @param provider Provider 标识
     * @param config   模型配置（baseUrl、apiKey、model）
     */
    public OpenAiProtocolChatModel(String provider, ModelConfig config) {
        super(config.getBaseUrl(), config.getApiKey(), config.getModel());
        this.provider = provider;
        this.capabilities = ModelCapabilities.openAiDefaults();
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + this.apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 带完整参数构造。
     */
    public OpenAiProtocolChatModel(String provider, String baseUrl, String apiKey, String model,
                                   ModelCapabilities capabilities) {
        super(baseUrl, apiKey, model);
        this.provider = provider;
        this.capabilities = capabilities != null ? capabilities : ModelCapabilities.openAiDefaults();
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + this.apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    protected String getDefaultBaseUrl() {
        return "https://api.openai.com";
    }

    @Override
    protected String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    protected Object buildNativeRequest(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel() != null && !request.getModel().isEmpty()
                ? request.getModel() : model);

        // 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }
        for (Message msg : request.getMessages()) {
            Map<String, Object> msgMap = new LinkedHashMap<>();
            msgMap.put("role", msg.getRole());
            msgMap.put("content", msg.getContent() != null ? msg.getContent() : "");
            if (msg.getThinking() != null && !msg.getThinking().isEmpty()) {
                msgMap.put("reasoning_content", msg.getThinking());
            }
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ToolCall tc : msg.getToolCalls()) {
                    String callId = tc.getToolCallId() != null ? tc.getToolCallId()
                            : (tc.getId() != null ? tc.getId() : "");
                    toolCalls.add(Map.of(
                            "id", callId,
                            "type", "function",
                            "function", Map.of(
                                    "name", tc.getName() != null ? tc.getName() : "",
                                    "arguments", tc.getArguments() != null ? tc.getArguments() : "")));
                }
                msgMap.put("tool_calls", toolCalls);
            }
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                msgMap.put("tool_call_id", msg.getToolCallId());
            }
            messages.add(msgMap);
        }
        body.put("messages", messages);

        // 可选参数
        body.put("stream", request.isStream());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getMaxTokens() != null) body.put("max_tokens", request.getMaxTokens());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());

        // 工具定义
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolDefinition td : request.getTools()) {
                Map<String, Object> funcDef = new LinkedHashMap<>();
                funcDef.put("name", td.getName());
                funcDef.put("description", td.getDescription() != null ? td.getDescription() : "");
                if (td.getParameters() != null) funcDef.put("parameters", td.getParameters());
                tools.add(Map.of("type", "function", "function", funcDef));
            }
            body.put("tools", tools);
            body.put("tool_choice", request.getToolChoice() != null ? request.getToolChoice() : "auto");
        }

        // 思考模式
        if (request.isThinkingEnabled() && capabilities.isThinking()) {
            body.put("thinking", Map.of("type", "enabled"));
            if (request.getThinkingBudget() != null) {
                body.put("thinking", Map.of("type", "enabled", "budget_tokens", request.getThinkingBudget()));
            }
        }

        // 停止序列
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            body.put("stop", request.getStopSequences());
        }

        return body;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Flux<String> sendNativeRequest(Object nativeRequest) {
        log.debug("OpenAI request body: {}", nativeRequest);
        return webClient.post()
                .uri(ENDPOINT)
                .bodyValue(nativeRequest)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("OpenAI API error: status={} body={}", response.statusCode().value(), body);
                                    return Mono.error(ModelException.withRawResponse(
                                            response.statusCode().value(),
                                            "OpenAI API 错误: " + body, body));
                                }))
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(300))
                .doOnError(e -> log.error("OpenAI request error: {}", e.getMessage(), e));
    }

    @Override
    protected ModelResponse parseChunk(String rawChunk) {
        // SSE 格式："data: {...}" 或 "[DONE]"
        if (rawChunk == null || rawChunk.isEmpty() || "[DONE]".equals(rawChunk.trim())) {
            return ModelResponse.builder().content("").finishReason("stop").build();
        }

        String json = rawChunk;
        if (json.startsWith("data: ")) {
            json = json.substring(6);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                return ModelResponse.builder().content("").build();
            }

            JsonNode choice = choices.get(0);
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                    ? choice.get("finish_reason").asText() : null;

            // 兼容流式(delta)和非流式(message)两种响应格式
            JsonNode delta = choice.get("delta");
            JsonNode message = choice.get("message");
            JsonNode source = delta != null ? delta : message;

            String content = "";
            String thinking = "";
            List<ModelResponse.ToolCallRequest> toolCalls = null;

            if (source != null) {
                if (source.has("content") && !source.get("content").isNull()) {
                    content = source.get("content").asText();
                }

                // thinking 内容提取
                if (source.has("reasoning_content") && !source.get("reasoning_content").isNull()) {
                    thinking = source.get("reasoning_content").asText();
                }

                // tool_calls
                if (source.has("tool_calls") && !source.get("tool_calls").isNull()) {
                    toolCalls = new ArrayList<>();
                    for (JsonNode tc : source.get("tool_calls")) {
                        ModelResponse.ToolCallRequest tcr = new ModelResponse.ToolCallRequest();
                        if (tc.has("id") && !tc.get("id").isNull()) {
                            tcr.setId(tc.get("id").asText());
                        }
                        if (tc.has("index") && !tc.get("index").isNull()) {
                            tcr.setIndex(tc.get("index").asInt());
                        }
                        JsonNode function = tc.get("function");
                        if (function != null) {
                            if (function.has("name") && !function.get("name").isNull()) {
                                tcr.setName(function.get("name").asText());
                            }
                            if (function.has("arguments") && !function.get("arguments").isNull()) {
                                tcr.setArguments(function.get("arguments").asText());
                            }
                        }
                        toolCalls.add(tcr);
                    }
                }
            }

            // usage
            Usage usage = null;
            if (root.has("usage") && !root.get("usage").isNull()) {
                JsonNode u = root.get("usage");
                int prompt = u.has("prompt_tokens") ? u.get("prompt_tokens").asInt() : 0;
                int completion = u.has("completion_tokens") ? u.get("completion_tokens").asInt() : 0;
                usage = new Usage(prompt, completion);
            }

            return ModelResponse.builder()
                    .id(root.has("id") ? root.get("id").asText() : null)
                    .model(root.has("model") ? root.get("model").asText() : null)
                    .content(content)
                    .thinking(thinking)
                    .toolCalls(toolCalls)
                    .finishReason(finishReason)
                    .usage(usage)
                    .build();

        } catch (JsonProcessingException e) {
            log.debug("SSE chunk 解析失败: {}", rawChunk, e);
            return ModelResponse.builder().content("").build();
        }
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // OpenAI 兼容的 token 估算：约 4 字符 ~= 1 token
        return text.length() / 4;
    }

    @Override
    public Mono<Boolean> validate() {
        // 发最小请求验证连通性
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
        body.put("max_tokens", 1);

        return webClient.post()
                .uri(ENDPOINT)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false)
                .timeout(Duration.ofSeconds(10));
    }

    /** 更新 API 密钥（运行时热更新） */
    public void updateApiKey(String newApiKey) {
        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + newApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /** 更新端点 URL（运行时热更新） */
    public void updateBaseUrl(String newBaseUrl) {
        String key = this.apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(newBaseUrl)
                .defaultHeader("Authorization", "Bearer " + key)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
