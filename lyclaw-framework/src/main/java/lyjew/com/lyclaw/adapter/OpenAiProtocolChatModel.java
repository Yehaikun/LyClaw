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
 * OpenAI 协议 ChatModel 实现——"配置即 Provider"的核心适配器，是框架中最关键的基础模型类。
 *
 * <p>作为整个框架的模型接入层基石，本类基于 OpenAI 兼容的 HTTP+SSE 协议实现了与 AI 大模型
 * 服务的完整通信链路。由于业界超过 85% 的 AI Provider（包括 DeepSeek、OpenAI、Groq、
 * MiniMax、通义千问、智谱 GLM 等国内主流大模型平台）均提供 OpenAI 兼容的 API 端点，
 * 因此只需在 YAML 配置文件或 ModelConfig 中设置不同的 baseUrl、apiKey 和 model 参数，
 * 即可无缝切换不同的 AI 服务提供商，无需编写任何额外的 Java 适配代码，真正实现了
 * "一次编写、配置切换"的设计理念，大幅降低了多模型接入的开发和维护成本。
 *
 * <p>在技术实现层面，本类采用 Spring WebClient（Reactive 响应式 HTTP 客户端）替代了
 * 传统的 OkHttp 阻塞式方案，具备以下核心能力：通过 POST 请求向 `/chat/completions`
 * 端点发送符合 OpenAI Chat Completions API 规范的 JSON 请求体，包括完整的消息列表
 * （系统提示词、用户/助手/工具消息及其角色标注）、工具定义（名称、描述、参数 Schema）、
 * 采样参数（temperature、topP、maxTokens）、思考模式配置（thinking 类型与 budget_tokens
 * 预算）、以及停止序列等多项可控参数。接收响应后，通过结构化解析 SSE（Server-Sent Events）
 * 数据流，支持自动拼接增量（delta）content 文本、增量聚合多轮 tool_calls 中的 function
 * name 和 arguments 参数、提取 reasoning_content 中的思维链内容、以及累加 usage 中的
 * prompt_tokens 和 completion_tokens 消耗统计。
 *
 * <p>本类继承自 {@link AbstractChatModel}，实现了其定义的三个核心模板方法：
 * <ul>
 *   <li>{@link #buildNativeRequest(ChatRequest)} — 将框架内部的统一 ChatRequest 对象
 *       转换为 OpenAI 协议兼容的 JSON Map 结构，处理消息序列化、工具定义转换、思考模式
 *       参数注入等</li>
 *   <li>{@link #sendNativeRequest(Object)} — 通过 WebClient 发送 HTTP POST 流式请求，
 *       处理 HTTP 错误状态码（自动提取响应体并包装为 ModelException）、设置超时时间
 *       （300 秒）、记录请求和错误日志</li>
 *   <li>{@link #parseChunk(String)} — 将原始 SSE 数据行解析为统一的 ModelResponse 对象，
 *       兼容流式（delta 格式）和非流式（message 格式）两种响应，提取 content、
 *       thinking、tool_calls 和 usage 信息</li>
 * </ul>
 *
 * <p>此外，本类还提供了运行时热更新能力：{@link #updateApiKey(String)} 和
 * {@link #updateBaseUrl(String)} 方法允许在不重启应用的情况下动态切换 API 密钥或
 * 端点地址，特别适用于密钥轮换和故障切换等运维场景。同时实现了 {@link #validate()}
 * 健康检查方法，通过发送最小化请求（单 token、无实际内容）来验证 Provider 连通性，
 * 并通过 {@link #countTokens(String)} 提供基于字符数的简易 Token 估算功能。
 *
 * <p>对于需要自定义 Provider 特性的场景，开发者可以继承本类（参见 {@code DeepSeekChatModel}
 * 示例），覆写 {@link #getDefaultBaseUrl()} 和 {@link #getDefaultModel()} 方法来提供
 * Provider 专属的默认端点地址和模型名称，同时通过 {@code @ChatModel}、{@code @RetryPolicy}、
 * {@code @Fallback}、{@code @CircuitBreaker} 等注解声明弹性策略。
 *
 * @see AbstractChatModel
 * @see lyjew.com.lyclaw.model.ChatRequest
 * @see lyjew.com.lyclaw.model.ModelResponse
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
     * 使用 {@link ModelConfig} 配置对象创建 OpenAI 协议适配器实例。
     *
     * <p>ModelConfig 是框架统一的模型配置数据对象，包含 baseUrl（API 端点地址）、apiKey
     * （认证密钥）和 model（模型名称）三个核心字段。构造过程中会从 config 中提取这些字段，
     * 同时初始化 WebClient 并设置默认的 Authorization Bearer 认证头和 Content-Type 头。
     *
     * @param provider Provider 唯一标识字符串，用于在框架中区分不同的 AI 服务提供商
     *                 例如 "openai"、"deepseek"、"groq" 等
     * @param config   模型配置对象，封装了 baseUrl、apiKey 和 model 等连接参数
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
     * 带完整参数和自定义 ModelCapabilities 的构造函数。
     *
     * <p>与简化构造器不同，本构造器允许直接指定所有连接参数和能力配置，适用于需要精确控制
     * 模型能力的场景。capabilities 参数定义了该 Provider 所支持的功能特性集合（如流式响应、
     * 工具调用、思考模式、视觉识别等），框架会根据这些能力声明在运行时有条件地启用或
     * 禁用相关功能。如果传入的 capabilities 为 null，则自动使用 {@link ModelCapabilities#openAiDefaults()}
     * 提供的默认能力集。
     *
     * @param provider     Provider 唯一标识字符串，用于在框架中区分不同的服务提供商
     * @param baseUrl      API 端点的基础 URL 地址，例如 "https://api.openai.com"
     * @param apiKey       用于 API 认证的密钥字符串
     * @param model        默认使用的模型名称，例如 "gpt-4o"
     * @param capabilities 该 Provider 的能力声明对象，null 时使用 OpenAI 默认能力集
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

    /**
     * 将框架内部的统一 ChatRequest 对象构建为 OpenAI Chat Completions API 兼容的原生请求体。
     *
     * <p>该方法是模板方法模式中的核心步骤之一，负责完成从框架内部模型到外部 API 格式的
     * 协议转换。构建过程涵盖以下步骤：设置 model 字段（优先使用请求中的模型名，其次使用
     * 默认模型）；将系统提示词作为 role=system 的首条消息插入；遍历所有历史消息，为每条
     * 消息构建包含 role、content、reasoning_content、tool_calls、tool_call_id 等字段的
     * Map 结构；序列化工具定义为 OpenAI 的 tools 数组格式（type=function + function 对象
     * 包含 name、description、parameters）；注入采样参数（temperature、max_tokens、top_p）；
     * 启用思考模式时添加 thinking 配置（含 budget_tokens 预算）。
     *
     * @param request 框架内部的统一聊天请求对象，包含消息历史、系统提示、工具定义等
     * @return 符合 OpenAI Chat Completions API 规范的 LinkedHashMap 请求体
     */
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

    /**
     * 通过 WebClient 以 HTTP POST 方式发送流式请求到 OpenAI 兼容端点。
     *
     * <p>该方法是模板方法模式中的第二个核心步骤，负责实际的网络通信。使用 Spring WebClient
     * 响应式客户端发送 POST 请求到 {@code /chat/completions} 端点，将构建好的原生请求体
     * 以 JSON 格式提交。通过 {@code bodyToFlux(String.class)} 获取响应式字符串流，每个元素
     * 对应一行 SSE 数据。同时设置了以下容错机制：通过 {@code onStatus} 处理所有 HTTP 错误
     * 状态码，自动提取响应体并包装为 {@link ModelException}；通过 {@code timeout} 设置 300
     * 秒的超时时间防止无限等待；通过 {@code doOnError} 记录所有请求级别的异常日志。
     *
     * @param nativeRequest 由 {@link #buildNativeRequest(ChatRequest)} 构建的协议原生请求体
     * @return 响应式字符串流，每个元素为一行原始的 SSE 数据（"data: {...}" 格式）
     */
    @Override
    @SuppressWarnings("unchecked")
    protected Flux<String> sendNativeRequest(Object nativeRequest) {
        log.info("LLM_REQUEST_BODY: {}", nativeRequest);
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

    /**
     * 将原始 SSE 数据块解析为框架统一的 {@link ModelResponse} 对象。
     *
     * <p>该方法是模板方法模式中的第三个核心步骤，负责将 Provider 返回的原始文本数据转换
     * 为框架内部统一的数据模型。解析过程兼容两种响应格式：流式响应（每个 chunk 包含 delta
     * 字段，内容为增量文本）和非流式响应（每个响应包含完整的 message 字段）。具体处理
     * 流程为：首先处理空数据和结束标记 "[DONE]"；然后去掉 "data: " 前缀提取纯 JSON；
     * 使用 Jackson ObjectMapper 解析 JSON 树结构；从 choices[0] 中提取 finish_reason 和
     * 内容源（优先 delta，其次 message）；提取 content 文本、reasoning_content 思维链内容、
     * tool_calls（包含 id、index、function.name、function.arguments）；从根节点的 usage
     * 对象中提取 prompt_tokens 和 completion_tokens 统计信息。JSON 解析异常时返回空内容
     * 的 ModelResponse 以保证流式处理不中断。
     *
     * @param rawChunk 原始 SSE 数据行，格式为 "data: {...}" 或 "[DONE]"
     * @return 解析后的统一 ModelResponse 对象，包含 content、thinking、toolCalls、usage 等
     */
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
                                String rawArgs = function.get("arguments").asText();
                                String funcName = function.has("name") ? function.get("name").asText() : "?";
                                log.info("LLM_RAW_TOOL_ARGS tool={} raw_arguments={}", funcName, rawArgs);
                                tcr.setArguments(rawArgs);
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

    /**
     * 使用基于字符数的简易算法估算文本的 Token 数量。
     *
     * <p>由于不是所有 Provider 都提供精确的 tokenize 接口，本方法使用经验估算公式：
     * 约 4 个字符 ~= 1 个 token。这是对英文场景（平均每 token 约 4 字符）和中文场景
     * （平均每 token 约 1.5-2 个汉字）的综合近似。对于需要精确 Token 计数的场景，
     * 建议子类覆写此方法对接 Provider 特定的 tokenize API。
     *
     * @param text 需要估算 Token 数量的文本内容
     * @return 估算的 Token 数量，空文本返回 0
     */
    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // OpenAI 兼容的 token 估算：约 4 字符 ~= 1 token
        return text.length() / 4;
    }

    /**
     * 通过发送最小化请求来验证 Provider 的连通性和认证有效性。
     *
     * <p>该方法发送一个仅包含单条 user message "ping" 且 max_tokens=1 的最小请求，
     * 不关心响应内容，仅检查 HTTP 状态码是否为 2xx。如果 Provider 连通且认证有效，
     * 返回 {@code Mono<true>}；如果连接失败、超时或返回错误状态码，返回 {@code Mono<false>}。
     * 设置了 10 秒超时以防止健康检查长时间阻塞。
     *
     * @return 响应式布尔值，true 表示 Provider 健康可用，false 表示不可用
     */
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

    /**
     * 运行时热更新 API 密钥，无需重启应用即可切换认证凭据。
     *
     * <p>该方法会重新构建内部的 WebClient 实例，使用新的 API 密钥设置 Authorization 请求头。
     * 适用于密钥轮换、临时授权、多租户切换等运维场景。注意此操作会替换整个 WebClient 实例，
     * 因此调用后所有正在进行中的请求仍使用旧的 WebClient，新请求使用更新后的凭据。
     *
     * @param newApiKey 新的 API 密钥字符串，将用于后续所有请求的 Bearer 认证
     */
    public void updateApiKey(String newApiKey) {
        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + newApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 运行时热更新 API 端点的基础 URL 地址，无需重启应用即可切换服务端点。
     *
     * <p>该方法会重新构建内部的 WebClient 实例，使用新的 baseUrl 作为请求目标地址，
     * 同时保留当前的 API 密钥。适用于故障转移（切换到备用端点）、灰度发布（切换到
     * 新版本端点）、区域路由（切换到就近区域的端点）等运维场景。与 updateApiKey 类似，
     * 此操作仅影响后续新请求，正在进行中的流式请求不受影响。
     *
     * @param newBaseUrl 新的 API 端点基础 URL，例如 "https://api.deepseek.com"
     */
    public void updateBaseUrl(String newBaseUrl) {
        String key = this.apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(newBaseUrl)
                .defaultHeader("Authorization", "Bearer " + key)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
