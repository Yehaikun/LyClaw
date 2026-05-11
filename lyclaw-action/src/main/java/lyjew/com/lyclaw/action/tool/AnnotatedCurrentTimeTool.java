package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.framework.annotation.Tool;
import lyjew.com.lyclaw.framework.annotation.Param;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 当前时间查询工具，返回指定时区或系统默认时区的当前日期时间。
 *
 * <p>该工具为只读操作，group 为 "builtin"。
 * 时间格式固定为 {@code yyyy-MM-dd HH:mm:ss}。
 * 时区参数无效时回退到系统默认时区。</p>
 */
@Tool(name = "current_time",
      description = "获取当前日期和时间，支持指定时区和格式",
      readonly = true,
      group = "builtin")
public class AnnotatedCurrentTimeTool {

    /** 日期时间输出格式 */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取当前日期时间。
     *
     * @param timezone 时区 ID（如 Asia/Shanghai），可为 null
     * @return 格式化的时间字符串，含时区信息
     */
    public String getCurrentTime(
        @Param(name = "timezone", description = "时区，例如 Asia/Shanghai, America/New_York，默认系统时区", required = false)
        String timezone
    ) {
        try {
            ZonedDateTime now;
            if (timezone != null && !timezone.isBlank()) {
                try {
                    now = ZonedDateTime.now(ZoneId.of(timezone));
                } catch (Exception e) {
                    // 时区无效时回退到系统默认时区
                    now = ZonedDateTime.now();
                }
            } else {
                now = ZonedDateTime.now();
            }

            String formatted = now.format(FORMATTER);
            String tzDisplay = now.getZone().getId();
            return "当前时间: " + formatted + " (" + tzDisplay + ")";
        } catch (Exception e) {
            return "获取时间失败: " + e.getMessage();
        }
    }
}
