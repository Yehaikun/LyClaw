package lyjew.com.lyclaw.action.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.annotation.tool.Tool;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily 搜索引擎工具，同时使用 {@code @Tool} 注解和实现 {@link lyjew.com.lyclaw.tool.Tool} 接口。
 *
 * <p>注解提供声明式元数据（名称、描述、分组），接口提供完整执行契约。
 * {@code ToolAnnotationProcessor} 检测到 bean 同时满足两者时，直接注册而不走 adapter 包装。</p>
 */
@Tool(name = "web_search",
      description = "搜索互联网获取最新信息，基于Tavily搜索引擎",
      readonly = true,
      group = "builtin")
public class TavilyWebSearchTool implements lyjew.com.lyclaw.tool.Tool {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 返回此工具的标识名称。
     *
     * <p>该名称在整个工具系统中唯一标识此工具，用于工具注册表索引、
     * 工具调用策略检查以及 LLM 的工具选择决策。
     * 返回值固定为 {@code "web_search"}，与类上
     * {@link lyjew.com.lyclaw.annotation.tool.Tool @Tool} 注解中的 {@code name} 属性严格保持一致。
     * 工具注册表以该名称为键存储和查找工具实例，因此不得返回 null 或空字符串。</p>
     *
     * @return 工具名称字符串，固定为 {@code "web_search"}
     */
    @Override
    public String getName() {
        return "web_search";
    }

    /**
     * 执行 Tavily 网络搜索，从 ToolCall 中解析搜索关键词并发起 HTTP API 请求。
     *
     * <p>完整执行流程：
     * <ol>
     *   <li><b>提取关键词</b> — 从 {@code toolCall.getArguments()} 中解析搜索关键词。
     *       首先尝试将 arguments 按 JSON 格式解析，从中提取 {@code "query"} 字段；
     *       若解析失败或没有该字段，则将整个 arguments 字符串作为关键词</li>
     *   <li><b>关键词校验</b> — 检查关键词是否为 null 或空白字符串，若是则返回失败结果，
     *       错误信息为 "搜索关键词为空"</li>
     *   <li><b>API 密钥检查</b> — 从环境变量 {@code TAVILY_API_KEY} 读取 API 密钥，
     *       若未配置（为 null 或空白）则返回失败结果，同时提示用户设置该环境变量</li>
     *   <li><b>构建请求</b> — 构造 HTTP POST 请求体（JSON 格式），包含以下字段：
     *       {@code api_key}（密钥）、{@code query}（关键词）、{@code search_depth="basic"}、
     *       {@code include_answer=true}、{@code max_results=5}。
     *       请求目标为 {@value #TAVILY_API_URL}，超时时间为 15 秒</li>
     *   <li><b>发送请求</b> — 通过单例 {@link java.net.http.HttpClient} 发送同步 POST 请求，
     *       设置 {@code Content-Type: application/json} 请求头</li>
     *   <li><b>处理响应</b> — 若 HTTP 状态码为 200，调用 {@link #formatResponse(String)}
     *       将原始 JSON 响应格式化为可读文本，构建成功的 {@link ToolExecutionResult} 返回；
     *       若状态码非 200，返回包含错误状态码的失败结果</li>
     *   <li><b>异常处理</b> — 任何步骤抛出异常时，捕获异常并返回失败结果，
     *       错误信息包含异常消息</li>
     * </ol>
     * </p>
     *
     * <p>返回值始终携带工具名称 {@code "web_search"} 和执行耗时（毫秒）。</p>
     *
     * @param toolCall 工具调用对象，其 arguments 字段应包含 JSON 格式的搜索参数，
     *                 推荐格式为 {@code {"query": "搜索关键词"}}
     * @param context  当前对话上下文，可为 null（本方法目前未直接使用此参数，
     *                 预留以符合 {@link lyjew.com.lyclaw.tool.Tool} 接口契约，便于未来扩展上下文感知搜索）
     * @return ToolExecutionResult 包含搜索结果的执行结果对象，success 标记是否成功，
     *         result 包含格式化后的搜索结果文本（含答案摘要和搜索条目列表），
     *         error 包含失败原因描述，elapsedMs 记录 API 调用耗时
     */
    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        long start = System.currentTimeMillis();
        try {
            String query = extractQuery(toolCall.getArguments());
            if (query == null || query.isBlank()) {
                return ToolExecutionResult.failure("搜索关键词为空", "web_search");
            }

            String apiKey = System.getenv("TAVILY_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                return ToolExecutionResult.failure(
                        "Tavily API Key 未配置，请设置环境变量 TAVILY_API_KEY", "web_search");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", apiKey);
            body.put("query", query);
            body.put("search_depth", "basic");
            body.put("include_answer", true);
            body.put("max_results", 5);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_API_URL))
                    .header("Content-Type", "application/json")
                    .timeout(HTTP_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String formatted = formatResponse(response.body());
                long elapsed = System.currentTimeMillis() - start;
                return ToolExecutionResult.builder()
                        .success(true)
                        .result(formatted)
                        .toolName("web_search")
                        .elapsedMs(elapsed)
                        .build();
            } else {
                return ToolExecutionResult.failure(
                        "Tavily API 返回错误状态码: " + response.statusCode(), "web_search");
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return ToolExecutionResult.builder()
                    .success(false)
                    .error("搜索失败: " + e.getMessage())
                    .toolName("web_search")
                    .elapsedMs(elapsed)
                    .build();
        }
    }

    /**
     * 返回此工具的定义元数据，供 LLM 进行工具选择和参数填充。
     *
     * <p>返回的 {@link ToolDefinition} 是一个不可变的描述性对象，包含以下字段：
     * <ul>
     *   <li><b>name</b> — 工具唯一标识名，固定为 {@code "web_search"}</li>
     *   <li><b>description</b> — 面向 LLM 的工具功能描述，说明这是一个基于 Tavily 搜索引擎的
     *       互联网信息检索工具，用于获取最新信息</li>
     *   <li><b>parameters</b> — 以 JSON Schema 格式描述工具接受的参数。当前定义了一个
     *       对象类型的参数结构，内部包含一个名为 {@code "query"} 的字符串属性
     *       （描述为 "搜索关键词"），且该属性被标记为必填项（{@code required}）</li>
     *   <li><b>source</b> — 工具来源标识，固定为 {@code "builtin"}，表示此为系统内置工具，
     *       区别于用户自定义或动态注册的外部工具</li>
     *   <li><b>readOnly</b> — 只读标记，固定为 {@code true}，表示网络搜索不会修改本地系统状态，
     *       沙箱安全层据此允许该工具在只读级别下执行</li>
     * </ul>
     * </p>
     *
     * <p>工具注册表在启动时收集所有工具的定义，汇总后传递给 LLM 作为 function calling
     * 的工具清单。LLM 根据定义中的名称和描述判断何时调用此工具，
     * 并根据 parameters 的 JSON Schema 构造符合格式的参数 JSON。</p>
     *
     * @return ToolDefinition 包含工具名称、功能描述、参数 Schema、来源和只读标记的定义对象，
     *         该对象为每次调用时新建，不会被外部修改
     */
    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of(
                "query", Map.of("type", "string", "description", "搜索关键词")
        ));
        params.put("required", List.of("query"));

        return ToolDefinition.builder()
                .name("web_search")
                .description("搜索互联网获取最新信息，基于Tavily搜索引擎")
                .parameters(params)
                .source("builtin")
                .readOnly(true)
                .build();
    }

    private String extractQuery(String arguments) {
        try {
            JsonNode node = MAPPER.readTree(arguments);
            if (node.has("query")) {
                return node.get("query").asText();
            }
        } catch (Exception e) {
            // 非 JSON 格式时当作纯文本查询
        }
        return arguments;
    }

    private String formatResponse(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            StringBuilder sb = new StringBuilder();

            if (root.has("answer") && !root.get("answer").isNull()) {
                sb.append("答案摘要:\n");
                sb.append(root.get("answer").asText()).append("\n\n");
            }

            if (root.has("results") && root.get("results").isArray()) {
                sb.append("搜索结果:\n\n");
                int i = 1;
                for (JsonNode r : root.get("results")) {
                    sb.append(i++).append(". ");
                    sb.append(r.get("title").asText()).append("\n");
                    sb.append("   URL: ").append(r.get("url").asText()).append("\n");
                    if (r.has("content")) {
                        String content = r.get("content").asText();
                        if (content.length() > 300) {
                            content = content.substring(0, 300) + "...";
                        }
                        sb.append("   ").append(content).append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return responseBody;
        }
    }
}
