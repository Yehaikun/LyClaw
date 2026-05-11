package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Deprecated
public class CurrentTimeTool implements Tool {

    private static final String TOOL_NAME = "current_time";
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String timezone = extractTimezone(toolCall);
            ZonedDateTime now;
            if (timezone != null && !timezone.isBlank()) {
                try {
                    now = ZonedDateTime.now(ZoneId.of(timezone));
                } catch (Exception e) {
                    log.warn("无效的时区: {}, 回退到系统默认时区", timezone);
                    now = ZonedDateTime.now();
                }
            } else {
                now = ZonedDateTime.now();
            }

            String formatted = now.format(FORMATTER);
            String tzDisplay = now.getZone().getId();
            long elapsed = System.currentTimeMillis() - startTime;
            return new ToolResult(true, "当前时间: " + formatted + " (" + tzDisplay + ")", null, elapsed, 0);
        } catch (Exception e) {
            log.error("获取时间失败", e);
            return ToolResult.failure("获取时间失败: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .source("builtin")
                .description("获取当前的日期和时间。可指定 IANA 时区（如 Asia/Shanghai），不指定则返回系统默认时区时间。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "timezone", Map.of(
                                        "type", "string",
                                        "description", "IANA 时区标识符，如 Asia/Shanghai、America/New_York"
                                )
                        )
                ))
                .build();
    }

    private String extractTimezone(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) return null;
        int tzIdx = args.indexOf("\"timezone\"");
        if (tzIdx < 0) return null;
        int start = args.indexOf("\"", args.indexOf(":", tzIdx + 10) + 1) + 1;
        int end = args.indexOf("\"", start);
        if (end < 0 || end <= start) return null;
        return args.substring(start, end);
    }
}
