package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 当前时间工具 —— 返回当前日期和时间。
 *
 * <p>模型需要知道当前日期时间时调用此工具（如"今天星期几"、"现在几点"）。
 * 返回格式：yyyy-MM-dd HH:mm:ss。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
@Component
public class CurrentTimeTool implements Tool {

    /** 工具名称常量 */
    private static final String TOOL_NAME = "current_time";

    /** 日期时间格式化器 */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        // 获取当前日期时间
        LocalDateTime now = LocalDateTime.now();

        // 格式化为可读字符串
        String formatted = now.format(FORMATTER);

        // 返回结果
        return ToolResult.success("当前时间: " + formatted);
    }

    @Override
    public ToolDefinition getDefinition() {
        // 当前时间工具不需要参数
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("获取当前的日期和时间。当需要知道当前时间、"
                        + "日期或星期几时使用。不需要任何参数。")
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }
}