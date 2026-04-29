package lyjew.com.lyclaw.adapter.deepseek;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.client.ModelApiClient;
import lyjew.com.lyclaw.dto.request.OpenAIRequest;
import lyjew.com.lyclaw.dto.response.OpenAIResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.parser.ParserImpl.OpenAIResponseParser;
import lyjew.com.lyclaw.template.AbstractModelAdapter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DeepSeek OpenAI 格式适配器
 *
 * 负责将统一请求转换为 OpenAI 兼容格式的请求，
 * 并将 OpenAI 格式响应转换为统一的 ModelResponse。
 *
 * DeepSeek OpenAI 格式特点：
 * - 端点 /chat/completions
 * - system 提示放在 messages 数组的第一条（role="system"）
 * - content 是纯字符串，不是数组——和 Anthropic 格式的最大区别
 * - Token 字段：prompt_tokens / completion_tokens / total_tokens
 * - 支持 thinking 配置和 reasoning_effort（low/medium/high）
 */
@Slf4j
@Component
public class DeepSeekOpenAIAdapter extends AbstractModelAdapter {

    // ========== 常量 ==========

    private static final String ENDPOINT = "/chat/completions";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    // ========== 依赖 ==========

    private final ModelApiClient httpClient;
    private final OpenAIResponseParser responseParser;
    private final ObjectMapper objectMapper;

    public DeepSeekOpenAIAdapter(ModelApiClient httpClient,
                                 OpenAIResponseParser responseParser,
                                 ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    // ========== 元信息 ==========

    @Override
    public String getProvider() {
        return "deepseek-openai";
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
            // DeepSeek 设 maxTokens=1 时 finish_reason 可能是 "length"，仍是正常响应
            return response != null
                    && response.getUsage() != null
                    && response.getUsage().getTotalTokens() > 0;        } catch (Exception e) {
            log.warn("[{}] 连接验证失败: {}", getProvider(), e.getMessage());
            return false;
        }
    }

    // ========== 请求构建 ==========

    @Override
    protected Object buildRequest(ChatRequest request) {
        return buildOpenAIRequest(request, request.isStream());
    }

    @Override
    protected Object buildStreamRequest(ChatRequest request) {
        return buildOpenAIRequest(request, true);
    }

    /**
     * 构建 OpenAI 格式请求体
     * 普通请求和流式请求只有 stream 字段不同
     */
    private OpenAIRequest buildOpenAIRequest(ChatRequest request, boolean stream) {
        String modelName = (request.getModel() != null && !request.getModel().isEmpty())
                ? request.getModel() : this.model;

        OpenAIRequest.OpenAIRequestBuilder builder = OpenAIRequest.builder()
                .model(modelName)
                .stream(stream);

        // 最大 Token 数
        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            builder.maxTokens(request.getMaxTokens());
        }

        // 温度
        if (request.getTemperature() != null) {
            builder.temperature(clampTemperature(request.getTemperature(), 0.0, 2.0));
        }

        // Top-P
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }

        // 停止序列
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            builder.stop(request.getStopSequences());
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
            builder.thinking(OpenAIRequest.Thinking.builder()
                    .type("enabled")
                    .build());
            builder.reasoningEffort(
                    request.getThinkingBudget() != null && request.getThinkingBudget() > 8000
                            ? "high" : "medium");
        }

        return builder.build();
    }

    /**
     * 构建消息列表
     * OpenAI 格式：system 是 messages[0]，content 是字符串
     */
    private List<OpenAIRequest.Message> buildMessages(ChatRequest request) {
        List<OpenAIRequest.Message> messages = new ArrayList<>();

        // system 提示——放在 messages 数组第一条
        if (request.hasSystemPrompt()) {
            OpenAIRequest.Message systemMsg = new OpenAIRequest.Message();
            systemMsg.setRole("system");
            systemMsg.setContent(request.getSystemPrompt());
            messages.add(systemMsg);
        }

        // 其他消息
        for (Message msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) {
                continue; // 跳过，用 ChatRequest.systemPrompt 替代
            }

            OpenAIRequest.Message oaiMsg = new OpenAIRequest.Message();
            oaiMsg.setRole(msg.getRole());
            oaiMsg.setContent(msg.getContent());

            // 工具返回消息——需要 tool_call_id 关联
            if ("tool".equals(msg.getRole()) && msg.getToolCalls() != null
                    && !msg.getToolCalls().isEmpty()) {
                oaiMsg.setToolCallId(msg.getToolCalls().get(0).getToolCallId());
            }

            // AI 消息包含工具调用——需要 tool_calls 数组
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null
                    && !msg.getToolCalls().isEmpty()) {
                List<OpenAIRequest.ToolCall> toolCalls = msg.getToolCalls().stream()
                        .map(tc -> {
                            OpenAIRequest.FunctionCall fc = new OpenAIRequest.FunctionCall();
                            fc.setName(tc.getName());
                            fc.setArguments(tc.getArguments());

                            OpenAIRequest.ToolCall oaiTc = new OpenAIRequest.ToolCall();
                            oaiTc.setId(tc.getToolCallId());
                            oaiTc.setType("function");
                            oaiTc.setFunction(fc);
                            return oaiTc;
                        })
                        .collect(Collectors.toList());
                oaiMsg.setToolCalls(toolCalls);
            }

            messages.add(oaiMsg);
        }

        return messages;
    }

    /**
     * 将统一工具定义转为 OpenAI 格式工具列表
     * OpenAI 格式: {type: "function", function: {name, description, parameters}}
     */
    private List<Map<String, Object>> buildTools(List<ToolDefinition> toolDefs) {
        return toolDefs.stream()
                .map(td -> {
                    Map<String, Object> function = new HashMap<>();
                    function.put("name", resolveDisplayName(td));
                    function.put("description", td.getDescription());
                    function.put("parameters", td.getParameters());

                    Map<String, Object> tool = new HashMap<>();
                    tool.put("type", "function");
                    tool.put("function", function);
                    return tool;
                })
                .collect(Collectors.toList());
    }

    /**
     * 解析工具选择策略
     * - null / 空: "auto"
     * - 指定工具名: {"type": "function", "function": {"name": "xxx"}}
     */
    private Object resolveToolChoice(ChatRequest request) {
        if (request.getToolChoice() != null && !request.getToolChoice().isEmpty()) {
            Map<String, Object> function = new HashMap<>();
            function.put("name", request.getToolChoice());

            Map<String, Object> choice = new HashMap<>();
            choice.put("type", "function");
            choice.put("function", function);
            return choice;
        }
        return "auto";
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
        OpenAIResponse resp = (OpenAIResponse) apiResponse;

        OpenAIResponse.Choice firstChoice = resp.getFirstChoice();
        if (firstChoice == null) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                    "响应中没有 choices 数据");
        }

        OpenAIResponse.ResponseMessage message = firstChoice.getMessage();

        // 构建统一响应
        ModelResponse.ModelResponseBuilder builder = ModelResponse.builder()
                .id(resp.getId())
                .content(message.getContent())
                .model(resp.getModel())
                .finishReason(firstChoice.getFinishReason());

        // Token 用量
        if (resp.getUsage() != null) {
            builder.usage(Usage.of(
                    resp.getUsage().getPromptTokens(),
                    resp.getUsage().getCompletionTokens()));
        }

        // 工具调用转换
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            List<ModelResponse.ToolCallRequest> toolCalls = message.getToolCalls().stream()
                    .map(tc -> ModelResponse.ToolCallRequest.builder()
                            .id(tc.getId())
                            .name(tc.getFunction().getName())
                            .arguments(tc.getFunction().getArguments())
                            .build())
                    .collect(Collectors.toList());
            builder.toolCalls(toolCalls);
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