package lyjew.com.lyclaw.adapter.deepseek;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
 * DeepSeek 模型适配器，使用与 OpenAI 兼容的 HTTP API 格式进行通信。
 *
 * <p>该类负责将上层统一的 {@link lyjew.com.lyclaw.model.ChatRequest ChatRequest}
 * 转换为 OpenAI 风格的请求格式，并调用 DeepSeek 的
 * {@code /chat/completions} 端点进行对话补全。</p>
 *
 * <p>主要功能包括：</p>
 * <ul>
 *   <li>将内部统一聊天请求构建为 OpenAI 格式的 JSON 请求体</li>
 *   <li>支持同步与流式两种调用模式</li>
 *   <li>支持 Function Calling（工具调用），将工具定义映射为 OpenAI tool 格式</li>
 *   <li>支持思考（thinking/reasoning）模式，向 DeepSeek 传递推理强度参数</li>
 *   <li>解析 OpenAI 格式的响应，转换为内部统一的 {@link lyjew.com.lyclaw.model.ModelResponse ModelResponse}</li>
 *   <li>从 SSE（Server-Sent Events）流中增量提取文本、工具调用和 Token 用量信息</li>
 * </ul>
 */
@Slf4j
@Component
public class DeepSeekOpenAIAdapter extends AbstractModelAdapter {

    /** DeepSeek Chat API 端点路径 */
    private static final String ENDPOINT = "/chat/completions";
    /** DeepSeek API 默认基础 URL */
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    /** 默认使用的 DeepSeek 模型标识 */
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    /** HTTP 客户端，负责发送同步和流式请求 */
    private final ModelApiClient httpClient;
    /** OpenAI 格式响应解析器 */
    private final OpenAIResponseParser responseParser;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * 通过依赖注入构造 DeepSeek 适配器。
     *
     * @param httpClient     HTTP 客户端实现
     * @param responseParser OpenAI 响应解析器
     * @param objectMapper   Jackson ObjectMapper
     */
    public DeepSeekOpenAIAdapter(ModelApiClient httpClient,
                                 OpenAIResponseParser responseParser,
                                 ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     * <p>返回提供商标识字符串 "deepseek-openai"。</p>
     */
    @Override
    public String getProvider() {
        return "deepseek-openai";
    }

    /**
     * {@inheritDoc}
     * <p>返回 DeepSeek API 默认基础 URL。</p>
     */
    @Override
    protected String getDefaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    /**
     * {@inheritDoc}
     * <p>返回默认使用的 DeepSeek 模型名称。</p>
     */
    @Override
    protected String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    /**
     * 使用启发式方法估算文本的 Token 数量。
     *
     * <p>DeepSeek 不提供独立的 tokenizer API，因此采用字符长度除以 2.5
     * 的简单估算，适用于中英文混合场景。</p>
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
     * <p>发送一个最小化的 "hi" 对话请求，确认：
     * <ul>
     *   <li>API Key 有效（未被拒）</li>
     *   <li>网络可达</li>
     *   <li>模型可正常响应并返回 Token 用量</li>
     * </ul>
     * </p>
     *
     * @return true 表示配置有效、连接正常
     */
    @Override
    public boolean validate() {
        if (!isConfigured()) {
            return false;
        }
        try {
            // 构造最小化验证请求：仅含一条"hi"消息，限制 maxTokens=1
            ChatRequest testRequest = ChatRequest.builder()
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content("hi")
                            .build()))
                    .maxTokens(1)
                    .build();

            ModelResponse response = chat(testRequest);
            return response != null
                    && response.getUsage() != null
                    && response.getUsage().getTotalTokens() > 0;
        } catch (Exception e) {
            log.warn("[{}] 连接验证失败: {}", getProvider(), e.getMessage());
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * <p>将内部统一请求构建为 OpenAI 格式的非流式请求体。</p>
     */
    @Override
    protected Object buildRequest(ChatRequest request) {
        return buildOpenAIRequest(request, request.isStream());
    }

    /**
     * {@inheritDoc}
     * <p>将内部统一请求构建为 OpenAI 格式的流式请求体（stream=true）。</p>
     */
    @Override
    protected Object buildStreamRequest(ChatRequest request) {
        return buildOpenAIRequest(request, true);
    }

    /**
     * 将内部统一聊天请求构建为 OpenAI 兼容的请求体。
     *
     * <p>转换过程包括：
     * <ol>
     *   <li>设置模型名、stream 标记、max_tokens、temperature（钳制到 [0.0, 2.0]）、top_p、stop 序列</li>
     *   <li>转换消息列表（system 提示、用户/助手/工具消息、工具调用结果）</li>
     *   <li>如有工具定义，转换为 OpenAI tools 数组格式</li>
     *   <li>解析工具选择策略（auto/none/required 或指定工具名）</li>
     *   <li>配置思考模式（thinking enabled/disabled + reasoning_effort）</li>
     * </ol>
     * </p>
     *
     * @param request 内部统一聊天请求
     * @param stream  是否为流式请求
     * @return OpenAI 格式的请求体对象
     */
    private OpenAIRequest buildOpenAIRequest(ChatRequest request, boolean stream) {
        // 优先使用请求中指定的模型名，否则回退到适配器默认模型
        String modelName = (request.getModel() != null && !request.getModel().isEmpty())
                ? request.getModel() : this.model;

        OpenAIRequest.OpenAIRequestBuilder builder = OpenAIRequest.builder()
                .model(modelName)
                .stream(stream);

        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            builder.maxTokens(request.getMaxTokens());
        }
        if (request.getTemperature() != null) {
            // DeepSeek temperature 范围 [0.0, 2.0]，做边界钳制
            builder.temperature(clampTemperature(request.getTemperature(), 0.0, 2.0));
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            builder.stop(request.getStopSequences());
        }
        builder.messages(buildMessages(request));
        if (request.hasTools()) {
            builder.tools(buildTools(request.getTools()));
            builder.toolChoice(resolveToolChoice(request));
        }
        if (request.isThinkingEnabled()) {
            // 思考模式开启：传递 reasoning_effort 参数
            // 预算 > 8000 时使用 high，否则 medium
            builder.thinking(OpenAIRequest.Thinking.builder()
                    .type("enabled")
                    .build());
            builder.reasoningEffort(
                    request.getThinkingBudget() != null && request.getThinkingBudget() > 8000
                            ? "high" : "medium");
        } else {
            // 显式禁用思考模式
            builder.thinking(OpenAIRequest.Thinking.builder()
                    .type("disabled")
                    .build());
        }

        return builder.build();
    }

    /**
     * 将内部统一消息列表转换为 OpenAI 格式的消息列表。
     *
     * <p>转换规则：
     * <ul>
     *   <li>system 消息：从请求的 systemPrompt 字段单独提取到列表首位</li>
     *   <li>普通 user/assistant 消息：直接映射 role 和 content</li>
     *   <li>tool 消息（工具执行结果）：设置 tool_call_id</li>
     *   <li>assistant 消息中的 tool_calls：映射函数调用的 id/type/function</li>
     * </ul>
     * </p>
     *
     * @param request 内部统一聊天请求
     * @return OpenAI 格式的消息列表
     */
    private List<OpenAIRequest.Message> buildMessages(ChatRequest request) {
        List<OpenAIRequest.Message> messages = new ArrayList<>();

        // 系统提示词单独添加为 system 角色消息
        if (request.hasSystemPrompt()) {
            OpenAIRequest.Message systemMsg = new OpenAIRequest.Message();
            systemMsg.setRole("system");
            systemMsg.setContent(request.getSystemPrompt());
            messages.add(systemMsg);
        }

        for (Message msg : request.getMessages()) {
            // 跳过已单独处理的 system 消息
            if ("system".equals(msg.getRole())) {
                continue;
            }

            OpenAIRequest.Message oaiMsg = new OpenAIRequest.Message();
            oaiMsg.setRole(msg.getRole());
            oaiMsg.setContent(msg.getContent());

            // tool 角色消息：绑定工具调用 ID
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null
                    && !msg.getToolCallId().isEmpty()) {
                oaiMsg.setToolCallId(msg.getToolCallId());
            } else if ("tool".equals(msg.getRole()) && msg.getToolCalls() != null
                    && !msg.getToolCalls().isEmpty()) {
                oaiMsg.setToolCallId(msg.getToolCalls().get(0).getToolCallId());
            }

            // assistant 角色消息中的 tool_calls：转换为 OpenAI ToolCall 格式
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
     * 将内部工具定义列表转换为 OpenAI tools 数组格式。
     *
     * <p>每个工具映射为 {@code {"type": "function", "function": {name, description, parameters}}}。</p>
     *
     * @param toolDefs 内部工具定义列表
     * @return OpenAI 格式的工具列表
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
     * 解析工具选择策略。
     *
     * <p>支持以下格式：
     * <ul>
     *   <li>字符串值 "auto" / "none" / "required"：原样返回</li>
     *   <li>字符串值（工具名）：转换为 {@code {"type": "function", "function": {"name": "xxx"}}}</li>
     *   <li>Map 类型：直接透传</li>
     *   <li>其他情况：默认返回 "auto"</li>
     * </ul>
     * </p>
     *
     * @param request 聊天请求，包含 toolChoice 字段
     * @return OpenAI 工具选择格式
     */
    private Object resolveToolChoice(ChatRequest request) {
        Object rawChoice = request.getToolChoice();

        if (rawChoice instanceof String) {
            String tc = (String) rawChoice;
            if ("auto".equals(tc) || "none".equals(tc) || "required".equals(tc)) {
                return tc;
            }
            if (!tc.isEmpty()) {
                // 指定具体工具名，构造 {"type":"function","function":{"name": 工具名}}
                Map<String, Object> function = new HashMap<>();
                function.put("name", tc);

                Map<String, Object> choice = new HashMap<>();
                choice.put("type", "function");
                choice.put("function", function);
                return choice;
            }
        }

        if (rawChoice instanceof Map) {
            return rawChoice;
        }

        return "auto";
    }

    /**
     * 解析 API 返回的原始 JSON 字符串。
     *
     * @param rawResponse 原始响应 JSON
     * @return 解析后的 OpenAIResponse 对象
     * @throws ModelException 响应为空或无法解析时
     */
    @Override
    protected Object parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR, "响应为空");
        }
        return responseParser.parse(rawResponse);
    }

    /**
     * 将 OpenAI 格式的 API 响应转换为内部统一响应。
     *
     * <p>提取第一个 choice 的 message 内容、模型名、finish_reason、
     * Token 用量，以及 tool_calls。</p>
     *
     * @param apiResponse 解析后的 OpenAIResponse 对象
     * @return 内部统一 ModelResponse
     * @throws ModelException 响应中缺少 choices 数据时
     */
    @Override
    protected ModelResponse toUnifiedResponse(Object apiResponse) {
        OpenAIResponse resp = (OpenAIResponse) apiResponse;

        OpenAIResponse.Choice firstChoice = resp.getFirstChoice();
        if (firstChoice == null) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                    "响应中没有 choices 数据");
        }

        OpenAIResponse.ResponseMessage message = firstChoice.getMessage();

        ModelResponse.ModelResponseBuilder builder = ModelResponse.builder()
                .id(resp.getId())
                .content(message.getContent())
                .model(resp.getModel())
                .finishReason(firstChoice.getFinishReason());

        // 映射 Token 用量信息
        if (resp.getUsage() != null) {
            builder.usage(Usage.of(
                    resp.getUsage().getPromptTokens(),
                    resp.getUsage().getCompletionTokens()));
        }

        // 映射工具调用请求
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

    /**
     * 发送同步 HTTP POST 请求到 DeepSeek API。
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
     * @return 包含 Authorization (Bearer token) 和 Content-Type 的头信息
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
     * <p>优先使用 displayName，为空时回退到 name。</p>
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
     *
     * @param value 原始值
     * @param min   最小值
     * @param max   最大值
     * @return 钳制后的值
     */
    private double clampTemperature(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 从 SSE 流原始文本中增量提取工具调用请求。
     *
     * <p>遍历每一行 SSE 事件，解析 JSON 中的 delta.tool_calls 数组，
     * 按 index 分组聚合，将同一 index 的多次增量拼接为完整的工具调用。
     * 支持流式传输中分片到达的 arguments 字段。</p>
     *
     * @param rawSSE 完整的 SSE 流原始文本
     * @return 提取到的工具调用请求列表
     */
    @Override
    public List<ModelResponse.ToolCallRequest> extractSseToolCalls(String rawSSE) {
        if (rawSSE == null || rawSSE.isEmpty()) return List.of();
        List<ModelResponse.ToolCallRequest> result = new ArrayList<>();

        for (String line : rawSSE.split("\n")) {
            String json = stripSseDataPrefix(line);
            if (json == null) continue;

            try {
                JsonNode root = objectMapper.readTree(json);
                // 获取 delta 节点（流式增量）
                JsonNode delta = root.path("choices").get(0).path("delta");
                JsonNode tcNode = delta.get("tool_calls");
                if (tcNode == null || !tcNode.isArray()) continue;

                for (JsonNode tc : tcNode) {
                    int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                    String id = tc.has("id") ? tc.get("id").asText() : null;
                    JsonNode func = tc.get("function");
                    String name = (func != null && func.has("name")) ? func.get("name").asText() : null;
                    String args = (func != null && func.has("arguments")) ? func.get("arguments").asText() : null;

                    // 在已有列表中按 index 查找或创建，然后追加参数片段
                    ModelResponse.ToolCallRequest existing = findOrCreate(result, idx, id, name);
                    if (args != null && !args.isEmpty()) {
                        existing.appendArguments(args);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * 从 SSE 流原始文本中提取纯文本内容。
     *
     * <p>优先提取 delta.content，若不存在则提取 delta.reasoning_content
     * （思考模式下的推理内容），将各增量拼接返回。</p>
     *
     * @param rawSSE 完整的 SSE 流原始文本
     * @return 拼接后的纯文本内容
     */
    @Override
    public String extractSsePlainText(String rawSSE) {
        if (rawSSE == null || rawSSE.isEmpty()) return "";
        StringBuilder text = new StringBuilder();

        for (String line : rawSSE.split("\n")) {
            String json = stripSseDataPrefix(line);
            if (json == null) continue;
            try {
                JsonNode delta = objectMapper.readTree(json)
                        .path("choices").get(0).path("delta");
                JsonNode content = delta.get("content");
                if (content != null && !content.isNull()) {
                    text.append(content.asText());
                } else {
                    // 思考模式下提取 reasoning_content
                    JsonNode reasoning = delta.get("reasoning_content");
                    if (reasoning != null && !reasoning.isNull()) {
                        text.append(reasoning.asText());
                    }
                }
            } catch (Exception ignored) {}
        }
        return text.toString();
    }

    /**
     * 从 SSE 流中提取 Token 用量信息。
     *
     * <p>从最后一条 SSE 事件倒序扫描，找到包含 usage 字段的 JSON 对象，
     * 解析 prompt_tokens、completion_tokens、total_tokens。
     * 如果找不到则返回全零的用量字符串。</p>
     *
     * @param rawSSE 完整的 SSE 流原始文本
     * @return 格式为 "prompt=N completion=N total=N" 的用量字符串
     */
    @Override
    public String extractSseTokenUsage(String rawSSE) {
        String[] lines = rawSSE.split("\n");
        // 从最后一行倒序查找（usage 信息通常在最后一个事件中）
        for (int i = lines.length - 1; i >= 0; i--) {
            String json = stripSseDataPrefix(lines[i]);
            if (json == null) continue;
            try {
                JsonNode usage = objectMapper.readTree(json).get("usage");
                if (usage != null) {
                    long prompt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0;
                    long completion = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0;
                    long total = usage.has("total_tokens") ? usage.get("total_tokens").asLong() : 0;
                    return "prompt=" + prompt + " completion=" + completion + " total=" + total;
                }
            } catch (Exception ignored) {}
        }
        return "prompt=0 completion=0 total=0";
    }

    /**
     * 去除 SSE 行前缀 "data:" 并返回实际 JSON 内容。
     *
     * <p>空行和 "[DONE]" 标记返回 null 表示跳过。</p>
     *
     * @param line 单行 SSE 事件
     * @return JSON 字符串，或 null 表示应跳过该行
     */
    private String stripSseDataPrefix(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring(5).trim();
        }
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) return null;
        return trimmed;
    }

    /**
     * 在工具调用列表中按 index 查找已有记录，不存在则创建新的。
     *
     * <p>用于 SSE 流式解析中按 index 分组聚合同一个工具调用的多次增量。</p>
     *
     * @param list  工具调用请求列表
     * @param index 工具调用在列表中的序号
     * @param id    工具调用 ID（仅首次出现时有值）
     * @param name  工具名称（仅首次出现时有值）
     * @return 已有或新创建的 ToolCallRequest 对象
     */
    private ModelResponse.ToolCallRequest findOrCreate(
            List<ModelResponse.ToolCallRequest> list,
            int index, String id, String name) {

        for (ModelResponse.ToolCallRequest tcr : list) {
            if (tcr.getIndex() == index) {
                return tcr;
            }
        }
        ModelResponse.ToolCallRequest tcr = ModelResponse.ToolCallRequest.builder()
                .index(index)
                .id(id != null ? id : "")
                .name(name != null ? name : "")
                .arguments("")
                .build();
        list.add(tcr);
        return tcr;
    }
}
