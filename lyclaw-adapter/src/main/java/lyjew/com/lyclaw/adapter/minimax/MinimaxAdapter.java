package lyjew.com.lyclaw.adapter.minimax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.client.ModelApiClient;
import lyjew.com.lyclaw.dto.request.AnthropicRequest;
import lyjew.com.lyclaw.dto.response.AnthropicResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.parser.ParserImpl.AnthropicResponseParser;
import lyjew.com.lyclaw.template.AbstractModelAdapter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MiniMax 模型适配器
 *
 * 负责将统一请求转换为 MiniMax Anthropic 兼容格式的请求，
 * 并将 MiniMax 响应转换为统一的 ModelResponse。
 *
 * MiniMax API 特点：
 * - Anthropic 兼容格式，端点 /anthropic/v1/messages
 * - system 提示放在顶层 system 字段，不在 messages 数组中
 * - content 是对象数组 [{type:"thinking", thinking:"..."}, {type:"text", text:"..."}]
 * - Token 字段：input_tokens / output_tokens，没有 total_tokens
 * - 业务状态码在 base_resp.status_code 中，0 表示成功
 */
@Slf4j
@Component
public class MinimaxAdapter extends AbstractModelAdapter {

    // ========== 常量 ==========

    private static final String ENDPOINT = "/anthropic/v1/messages";
    private static final String DEFAULT_BASE_URL = "https://api.minimaxi.com";
    private static final String DEFAULT_MODEL = "MiniMax-M2.7";

    // ========== 依赖 ==========

    private final ModelApiClient httpClient;
    private final AnthropicResponseParser responseParser;
    private final ObjectMapper objectMapper;

    public MinimaxAdapter(ModelApiClient httpClient,
                          AnthropicResponseParser responseParser,
                          ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    // ========== 元信息 ==========

    @Override
    public String getProvider() {
        return "minimax";
    }

    @Override
    protected String getDefaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    @Override
    protected String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    // ========== Token 估算 ==========

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 2.5);
    }

    // ========== 连接验证 ==========

    @Override
    public boolean validate() {
        if (!isConfigured()) {
            return false;
        }
        try {
            ChatRequest testRequest = ChatRequest.builder()
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content("hi")
                            .build()))
                    .maxTokens(1)
                    .build();

            ModelResponse response = chat(testRequest);
            // 只要拿到响应且没有错误，就认为 Key 有效
            // maxTokens=1 时 stop_reason 可能是 "max_tokens"，也是正常的
            return response != null && response.getUsage() != null
                    && response.getUsage().getCompletionTokens() >= 0;
        } catch (Exception e) {
            log.warn("[{}] 连接验证失败: {}", getProvider(), e.getMessage());
            return false;
        }
    }

    // ========== 请求构建 ==========

    @Override
    protected Object buildRequest(ChatRequest request) {
        return buildAnthropicRequest(request, request.isStream());
    }

    @Override
    protected Object buildStreamRequest(ChatRequest request) {
        return buildAnthropicRequest(request, true);
    }

    private AnthropicRequest buildAnthropicRequest(ChatRequest request, boolean stream) {
        String modelName = (request.getModel() != null && !request.getModel().isEmpty())
                ? request.getModel() : this.model;

        AnthropicRequest.AnthropicRequestBuilder builder = AnthropicRequest.builder()
                .model(modelName)
                .stream(stream)
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048);

        // system 提示——Anthropic 格式放在顶层
        if (request.hasSystemPrompt()) {
            builder.system(request.getSystemPrompt());
        }

        // 温度
        if (request.getTemperature() != null) {
            builder.temperature(clampTemperature(request.getTemperature(), 0.01, 1.0));
        }

        // Top-P
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }

        // 停止序列
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            builder.stopSequences(request.getStopSequences());
        }

        // 消息列表
        builder.messages(buildMessages(request));

        // 工具列表
        if (request.hasTools()) {
            builder.tools(buildTools(request.getTools()));
            builder.toolChoice(resolveToolChoice(request));
        }

        // 思考模式
        if (request.isThinkingEnabled()) {
            builder.thinking(AnthropicRequest.Thinking.builder()
                    .type("enabled")
                    .budgetTokens(request.getThinkingBudget())
                    .build());
        }

        return builder.build();
    }

    /**
     * 构建消息列表——Anthropic 格式 content 是数组
     */
    private List<AnthropicRequest.Message> buildMessages(ChatRequest request) {
        return request.getMessages().stream()
                .filter(msg -> !"system".equals(msg.getRole()))
                .map(msg -> {
                    List<Map<String, Object>> contentBlocks = new ArrayList<>();
                    Map<String, Object> textBlock = new HashMap<>();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.getContent() != null ? msg.getContent() : "");
                    contentBlocks.add(textBlock);

                    AnthropicRequest.Message anthMsg = new AnthropicRequest.Message();
                    anthMsg.setRole(msg.getRole());
                    anthMsg.setContent(contentBlocks);
                    return anthMsg;
                })
                .collect(Collectors.toList());
    }

    /**
     * 统一工具定义 → Anthropic 格式
     * {name, description, input_schema}
     */
    private List<Map<String, Object>> buildTools(List<ToolDefinition> toolDefs) {
        return toolDefs.stream()
                .map(td -> {
                    Map<String, Object> tool = new HashMap<>();
                    tool.put("name", resolveDisplayName(td));
                    tool.put("description", td.getDescription());
                    tool.put("input_schema", td.getParameters());
                    return tool;
                })
                .collect(Collectors.toList());
    }

    private Object resolveToolChoice(ChatRequest request) {
        if (request.getToolChoice() != null && !request.getToolChoice().isEmpty()) {
            Map<String, Object> choice = new HashMap<>();
            choice.put("type", "tool");
            choice.put("name", request.getToolChoice());
            return choice;
        }
        return Map.of("type", "auto");
    }

    // ========== 响应解析 ==========

    @Override
    protected Object parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR, "响应为空");
        }
        return responseParser.parse(rawResponse);
    }

    @Override
    protected ModelResponse toUnifiedResponse(Object apiResponse) {
        AnthropicResponse resp = (AnthropicResponse) apiResponse;

        // 提取文本——过滤 thinking 块，只取 text 块
        String textContent = "";
        String thinking = "";

        if (resp.getContent() != null) {
            for (AnthropicResponse.ContentBlock block : resp.getContent()) {
                if ("text".equals(block.getType())) {
                    textContent += block.getText();
                } else if ("thinking".equals(block.getType())) {
                    thinking += block.getThinking();
                }
            }
        }

        // 当 content 为空但 thinking 非空时，用 thinking 兜底
        // MiniMax 对部分 prompt（尤其是英文）可能只返回 thinking 块，没有 text 块
        if (textContent.isEmpty() && !thinking.isEmpty()) {
            log.debug("[{}] 响应中没有 text 块，使用 thinking 内容兜底，thinking长度={}",
                    getProvider(), thinking.length());
            textContent = thinking;
        }

        // ★ 当 content 为空时打印警告
        if (textContent.isEmpty() && thinking.isEmpty()) {
            log.warn("[{}] 响应中没有 text 和 thinking 内容块，stop_reason={}, content块数={}",
                    getProvider(),
                    resp.getStopReason(),
                    resp.getContent() != null ? resp.getContent().size() : 0);
        }

        ModelResponse.ModelResponseBuilder builder = ModelResponse.builder()
                .id(resp.getId())
                .content(textContent.isEmpty() ? null : textContent)
                .thinking(thinking.isEmpty() ? null : thinking)
                .model(resp.getModel())
                .finishReason(resp.getStopReason());

        // Token 用量——MiniMax 无 total_tokens
        if (resp.getUsage() != null) {
            builder.usage(Usage.of(
                    resp.getUsage().getInputTokens(),
                    resp.getUsage().getOutputTokens()));
        }

        // 检查 MiniMax 业务状态码
        if (resp.getBaseResp() != null && !resp.getBaseResp().isSuccess()) {
            log.warn("[{}] 业务状态码异常: code={}, msg={}",
                    getProvider(),
                    resp.getBaseResp().getStatusCode(),
                    resp.getBaseResp().getStatusMsg());
        }

        return builder.build();
    }

    // ========== HTTP 发送 ==========

    @Override
    protected String sendRequest(Object apiRequest) {
        String url = baseUrl + ENDPOINT;
        Map<String, String> headers = buildHeaders();

        try {
            String body = objectMapper.writeValueAsString(apiRequest);
            log.debug("[{}] 请求体: {}", getProvider(), body);
            return httpClient.post(url, headers, body);
        } catch (JsonProcessingException e) {
            throw ModelException.of(ErrorCode.MODEL_INVALID_REQUEST,
                    "请求序列化失败: " + e.getMessage());
        }
    }

    @Override
    protected Flux<String> sendStreamRequest(Object apiRequest) {
        String url = baseUrl + ENDPOINT;
        Map<String, String> headers = buildHeaders();

        try {
            String body = objectMapper.writeValueAsString(apiRequest);
            log.debug("[{}] 流式请求体: {}", getProvider(), body);
            return httpClient.postStream(url, headers, body);
        } catch (JsonProcessingException e) {
            return Flux.error(ModelException.of(ErrorCode.MODEL_INVALID_REQUEST,
                    "请求序列化失败: " + e.getMessage()));
        }
    }

    // ========== 私有辅助 ==========

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private String resolveDisplayName(ToolDefinition td) {
        return (td.getDisplayName() != null && !td.getDisplayName().isEmpty())
                ? td.getDisplayName() : td.getName();
    }

    private double clampTemperature(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}