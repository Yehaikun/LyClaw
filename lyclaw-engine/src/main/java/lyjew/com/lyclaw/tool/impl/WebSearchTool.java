package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 网络搜索工具 —— 调用外部搜索 API 获取网络搜索结果。
 *
 * <p>模型需要获取实时信息时（如新闻、天气、最新数据），调用此工具。
 * 参数通过 ToolCall 的 arguments（JSON String）传递，包含关键词 searchQuery。</p>
 *
 * <p><b>参数格式</b>：{ "searchQuery": "要搜索的关键词" }</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
@Component
public class WebSearchTool implements Tool {

    /** 工具名称常量 */
    private static final String TOOL_NAME = "web_search";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        try {
            // 1. 解析参数 —— 从 toolCall 的 arguments 中提取搜索关键词
            String query = parseSearchQuery(toolCall);

            // 2. 执行搜索（此处为示例，实际应调用外部搜索 API）
            // TODO: 对接 Brave Search / Bing Search API
            String result = search(query);

            // 3. 返回搜索结果
            return ToolResult.success(result);
        } catch (Exception e) {
            // 搜索失败，返回错误信息
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        // 返回工具定义 —— 模型据此了解工具的功能和参数格式
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("搜索互联网获取实时信息。当需要了解新闻、天气、"
                        + "最新数据或其他实时信息时使用。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "searchQuery", Map.of(
                                        "type", "string",
                                        "description", "要搜索的关键词"
                                )
                        ),
                        "required", java.util.List.of("searchQuery")
                ))
                .build();
    }

    /**
     * 从 toolCall 的 arguments JSON 中解析搜索关键词。
     *
     * @param toolCall 工具调用请求
     * @return 搜索关键词
     */
    private String parseSearchQuery(ToolCall toolCall) {
        // 简单解析：从 JSON 参数中提取 searchQuery 字段
        // TODO: 使用 Jackson 解析
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) {
            return "";
        }
        // 临时简单解析方式 —— 后续替换为 Jackson 解析
        if (args.contains("\"searchQuery\"")) {
            int start = args.indexOf("\"searchQuery\"") + "\"searchQuery\"".length();
            start = args.indexOf(":", start) + 1;
            start = args.indexOf("\"", start) + 1;
            int end = args.indexOf("\"", start);
            return args.substring(start, end);
        }
        return args.replaceAll("\"", "").trim();
    }

    /**
     * 执行搜索操作。当前为模拟实现，后续对接真正的搜索 API。
     *
     * @param query 搜索关键词
     * @return 搜索结果文本
     */
    private String search(String query) {
        // 模拟搜索结果 —— 实际应调用搜索引擎 API
        return "Search results for '" + query + "':\n"
                + "- " + query + " 的相关信息（此处为模拟结果）\n"
                + "- 请参考: https://example.com/search?q=" + query;
    }
}