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
 * MiniMax 模型适配器，使用与 Anthropic Messages API 兼容的格式与 MiniMax 服务通信。
 *
 * <p>MiniMax 的 API 兼容 Anthropic 风格（基础路径 {@code /anthropic/v1/messages}），
 * 因此该类继承 {@link lyjew.com.lyclaw.template.AbstractModelAdapter}，
 * 负责将上层统一聊天请求转换为 Anthropic 格式的请求体，
 * 并处理 Anthropic 格式的响应。</p>
 *
 * <p>与 DeepSeek 适配器的主要区别：</p>
 * <ul>
 *   <li>消息体使用 Anthropic 风格（content 为数组，每个元素含 type 标记）</li>
 *   <li>工具定义使用 Anthropic input_schema 格式</li>
 *   <li>响应中的 content 数组可能包含 text 和 thinking 两种类型的内容块</li>
 *   <li>不支持 SSE 流式内容提取方法（{@code extractSse*} 系列未重写）</li>
 * </ul>
 */
@Slf4j
@Component
public class MinimaxAdapter extends AbstractModelAdapter {

    /** MiniMax Anthropic API 端点路径 */
    private static final String ENDPOINT = "/anthropic/v1/messages";
    /** MiniMax API 默认基础 URL */
    private static final String DEFAULT_BASE_URL = "https://api.minimaxi.com";
    /** 默认使用的 MiniMax 模型标识 */
    private static final String DEFAULT_MODEL = "MiniMax-M2.7";

    /** HTTP 客户端 */
    private final ModelApiClient httpClient;
    /** Anthropic 格式响应解析器 */
    private final AnthropicResponseParser responseParser;
    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /**
     * 通过依赖注入构造 MiniMax 适配器。
     *
     * @param httpClient     HTTP 客户端
     * @param responseParser Anthropic 响应解析器
     * @param objectMapper   Jackson ObjectMapper
     */
    public MinimaxAdapter(ModelApiClient httpClient,
                          AnthropicResponseParser responseParser,
                          ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     * <p>返回提供商标识字符串 "minimax"。</p>
     */
    @Override
    public String getProvider() {
        return "minimax";
    }

    /**
     * {@inheritDoc}
     * <p>返回 MiniMax API 默认基础 URL。</p>
     */
    @Override
    protected String getDefaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    /**
     * {@inheritDoc}
     * <p>返回默认使用的 MiniMax 模型名称。</p>
     */
    @Override
    protected String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    /**
     * 使用启发式方法估算文本的 Token 数量。
     *
     * <p>采用字符长度除以 2.5 的简单估算，适用于中英文混合场景。</p>
     *
     * @param text 待估算的文本；为 null 或空字符串时返回 0
     * @return 估算的 Token 数量
     */
    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 2.5);
    }

    /**
     * 验证适配器配置是否有效。
     *
     * <p>发送最小化的 "hi" 对话请求，确认 API Key 有效且模型可正常响应。</p>
     *
     * @return true 表示配置有效
     */
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
            return response != null && response.getUsage() != null
                    && response.getUsage().getCompletionTokens() >= 0;
        } catch (Exception e) {
            log.warn("[{}] 连接验证失败: {}", getProvider(), e.getMessage());
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * <p>将内部统一请求构建为 Anthropic 格式的非流式请求体。</p>
     */
    @Override
    protected Object buildRequest(ChatRequest request) {
        return buildAnthropicRequest(request, request.isStream());
    }

    /**
     * {@inheritDoc}
     * <p>将内部统一请求构建为 Anthropic 格式的流式请求体（stream=true）。</p>
     */
    @Override
    protected Object buildStreamRequest(ChatRequest request) {
        return buildAnthropicRequest(request, true);
    }

    /**
     * 将内部统一聊天请求构建为 Anthropic 兼容的请求体。
     *
     * <p>转换包括模型名、stream、max_tokens（默认 2048）、system 提示词、
     * temperature（钳制到 [0.01, 1.0]）、top_p、stop_sequences、
     * 消息列表（Anthropic content 数组格式）、工具定义（input_schema 格式）、
     * 工具选择策略、思考模式（budget_tokens）等字段。</p>
     *
     * @param request 内部统一聊天请求
     * @param stream  是否为流式请求
     * @return Anthropic 格式的请求体对象
     */
    private AnthropicRequest buildAnthropicRequest(ChatRequest request, boolean stream) {
        // 优先使用请求中指定的模型名，否则回退到适配器默认模型
        String modelName = (request.getModel() != null && !request.getModel().isEmpty())
                ? request.getModel() : this.model;

        AnthropicRequest.AnthropicRequestBuilder builder = AnthropicRequest.builder()
                .model(modelName)
                .stream(stream)
                // maxTokens 必填，默认 2048
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048);

        // system 提示词在 Anthropic 中是顶层字段
        if (request.hasSystemPrompt()) {
            builder.system(request.getSystemPrompt());
        }
        if (request.getTemperature() != null) {
            // Anthropic temperature 范围 [0.01, 1.0]
            builder.temperature(clampTemperature(request.getTemperature(), 0.01, 1.0));
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            builder.stopSequences(request.getStopSequences());
        }
        builder.messages(buildMessages(request));
        if (request.hasTools()) {
            builder.tools(buildTools(request.getTools()));
            builder.toolChoice(resolveToolChoice(request));
        }
        if (request.isThinkingEnabled()) {
            // Anthropic 风格 thinking：type=enabled + budget_tokens
            builder.thinking(AnthropicRequest.Thinking.builder()
                    .type("enabled")
                    .budgetTokens(request.getThinkingBudget())
                    .build());
        }

        return builder.build();
    }

    /**
     * 将内部统一消息列表转换为 Anthropic 格式的消息列表。
     *
     * <p>过滤掉 system 角色消息（已单独处理），将其余消息均转换为
     * content 数组格式（每个元素为 {@code {"type": "text", "text": "..."}}）。</p>
     *
     * @param request 内部统一聊天请求
     * @return Anthropic 格式的消息列表
     */
    private List<AnthropicRequest.Message> buildMessages(ChatRequest request) {
        return request.getMessages().stream()
                // 过滤 system 消息（它们已在顶层 system 字段中处理）
                .filter(msg -> !"system".equals(msg.getRole()))
                .map(msg -> {
                    // Anthropic 消息的 content 是数组，每个元素有 type 字段
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
     * 将内部工具定义转换为 Anthropic 格式的工具列表。
     *
     * <p>与 OpenAI 格式的主要区别：参数 schema 使用 {@code input_schema} 字段名。</p>
     *
     * @param toolDefs 内部工具定义列表
     * @return Anthropic 格式的工具列表
     */
    private List<Map<String, Object>> buildTools(List<ToolDefinition> toolDefs) {
        return toolDefs.stream()
                .map(td -> {
                    Map<String, Object> tool = new HashMap<>();
                    tool.put("name", resolveDisplayName(td));
                    tool.put("description", td.getDescription());
                    // Anthropic 使用 input_schema 而非 parameters
                    tool.put("input_schema", td.getParameters());
                    return tool;
                })
                .collect(Collectors.toList());
    }

    /**
     * 解析 Anthropic 风格的工具选择策略。
     *
     * <p>支持 auto/any/none 字符串、指定具体工具名、以及原始 Map 格式。</p>
     *
     * @param request 聊天请求
     * @return Anthropic 工具选择格式
     */
    private Object resolveToolChoice(ChatRequest request) {
        Object rawChoice = request.getToolChoice();

        if (rawChoice instanceof String) {
            String tc = (String) rawChoice;
            if ("auto".equals(tc) || "any".equals(tc) || "none".equals(tc)) {
                return Map.of("type", tc);
            }
            if (!tc.isEmpty()) {
                // 指定具体工具名：{"type": "tool", "name": "xxx"}
                Map<String, Object> choice = new HashMap<>();
                choice.put("type", "tool");
                choice.put("name", tc);
                return choice;
            }
        }

        if (rawChoice instanceof Map) {
            return rawChoice;
        }

        return Map.of("type", "auto");
    }

    /**
     * 解析 API 返回的原始 JSON 字符串为 AnthropicResponse。
     *
     * @param rawResponse 原始响应 JSON
     * @return 解析后的 AnthropicResponse 对象
     */
    @Override
    protected Object parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR, "响应为空");
        }
        return responseParser.parse(rawResponse);
    }

    /**
     * 将 Anthropic 格式的 API 响应转换为内部统一响应。
     *
     * <p>遍历 content 数组，分别提取 text 类型的文本内容和 thinking 类型的思考内容。
     * 如果文本为空但存在思考内容，则用思考内容兜底。
     * 同时映射 Token 用量（input_tokens/output_tokens）和业务状态码。</p>
     *
     * @param apiResponse 解析后的 AnthropicResponse 对象
     * @return 内部统一 ModelResponse
     */
    @Override
    protected ModelResponse toUnifiedResponse(Object apiResponse) {
        AnthropicResponse resp = (AnthropicResponse) apiResponse;

        String textContent = "";
        String thinking = "";

        // 遍历 content 数组，按类型分别拼接
        if (resp.getContent() != null) {
            for (AnthropicResponse.ContentBlock block : resp.getContent()) {
                if ("text".equals(block.getType())) {
                    textContent += block.getText();
                } else if ("thinking".equals(block.getType())) {
                    thinking += block.getThinking();
                }
            }
        }

        // 如果没有 text 块但有 thinking 块，用 thinking 兜底
        if (textContent.isEmpty() && !thinking.isEmpty()) {
            log.debug("[{}] 响应中没有 text 块，使用 thinking 内容兜底，thinking长度={}",
                    getProvider(), thinking.length());
            textContent = thinking;
        }

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
                // Anthropic 使用 stop_reason 代替 finish_reason
                .finishReason(resp.getStopReason());

        // 映射 Token 用量
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

    /**
     * 发送同步 HTTP POST 请求到 MiniMax API。
     *
     * @param apiRequest 已构建的请求体对象
     * @return API 返回的 JSON 字符串
     */
    @Override
    protected String sendRequest(Object apiRequest) {
        String url = baseUrl + ENDPOINT;
        Map<String, String> headers = buildHeaders();

        try {
            String body = objectMapper.writeValueAsString(apiRequest);
            return httpClient.post(url, headers, body);
        } catch (JsonProcessingException e) {
            throw ModelException.of(ErrorCode.MODEL_INVALID_REQUEST,
                    "请求序列化失败: " + e.getMessage());
        }
    }

    /**
     * 发送流式 HTTP POST 请求，返回 SSE 事件流。
     *
     * @param apiRequest 已构建的请求体对象
     * @return SSE 文本行的 Flux 流
     */
    @Override
    protected Flux<String> sendStreamRequest(Object apiRequest) {
        String url = baseUrl + ENDPOINT;
        Map<String, String> headers = buildHeaders();

        try {
            String body = objectMapper.writeValueAsString(apiRequest);
            return httpClient.postStream(url, headers, body);
        } catch (JsonProcessingException e) {
            return Flux.error(ModelException.of(ErrorCode.MODEL_INVALID_REQUEST,
                    "请求序列化失败: " + e.getMessage()));
        }
    }

    /**
     * 构建 HTTP 请求头。
     *
     * @return 包含 Bearer token 认证和 Content-Type 的头信息
     */
    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    /**
     * 获取工具定义中用于传递给 LLM 的显示名称。
     *
     * @param td 工具定义
     * @return 工具显示名称
     */
    private String resolveDisplayName(ToolDefinition td) {
        return (td.getDisplayName() != null && !td.getDisplayName().isEmpty())
                ? td.getDisplayName() : td.getName();
    }

    /**
     * 将 temperature 值钳制到指定的闭区间内。
     */
    private double clampTemperature(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
