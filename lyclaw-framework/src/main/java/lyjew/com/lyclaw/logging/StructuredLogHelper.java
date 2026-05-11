package lyjew.com.lyclaw.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.MDC;
import lyjew.com.lyclaw.tracing.TraceConstants;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化日志辅助工具，生成 JSON 格式的结构化日志条目。
 *
 * <p>每条日志自动附带 traceId、spanId、service 等 MDC 追踪信息。
 * 支持 event、error、warn、info、debug 五种日志级别，以及便捷的上下文构建方法。</p>
 */
public final class StructuredLogHelper {

    /** 预配置 ObjectMapper，支持 Java 8 时间类型序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private StructuredLogHelper() { /* 工具类 */ }

    /**
     * 构建日志基础字段映射，自动从 MDC 注入追踪信息。
     *
     * @return 包含 timestamp、traceId、spanId、service 的 LinkedHashMap
     */
    private static Map<String, Object> base() {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("timestamp", Instant.now().toString());
        log.put("traceId", MDC.get(TraceConstants.MDC_TRACE_ID));
        log.put("spanId", MDC.get(TraceConstants.MDC_SPAN_ID));
        log.put("service", MDC.get(TraceConstants.MDC_SERVICE));
        return log;
    }

    /**
     * 生成一条事件类型日志（无日志级别）。
     *
     * @param event   事件名称
     * @param message 日志消息
     * @param context 附加上下文键值对，可为 null
     * @return JSON 格式的结构化日志字符串
     */
    public static String event(String event, String message, Map<String, Object> context) {
        Map<String, Object> log = base();
        log.put("event", event);
        log.put("message", message);
        if (context != null) {
            log.putAll(context);
        }
        try {
            return MAPPER.writeValueAsString(log);
        } catch (Exception e) {
            return "{\"error\":\"log serialization failed\"}";
        }
    }

    /**
     * 生成 ERROR 级别的结构化日志。
     *
     * @param message 日志消息
     * @param ex      关联的异常对象，可为 null
     * @param context 附加上下文键值对，可为 null
     * @return JSON 格式的结构化日志字符串
     */
    public static String error(String message, Throwable ex, Map<String, Object> context) {
        Map<String, Object> log = base();
        log.put("level", "ERROR");
        log.put("message", message);
        if (ex != null) {
            log.put("exception", ex.getClass().getName());
            log.put("exceptionMessage", ex.getMessage());
        }
        if (context != null) {
            log.putAll(context);
        }
        try {
            return MAPPER.writeValueAsString(log);
        } catch (Exception e) {
            return "{\"error\":\"log serialization failed\"}";
        }
    }

    /**
     * 生成 WARN 级别的结构化日志。
     *
     * @param message 日志消息
     * @param context 附加上下文键值对，可为 null
     * @return JSON 格式的结构化日志字符串
     */
    public static String warn(String message, Map<String, Object> context) {
        Map<String, Object> log = base();
        log.put("level", "WARN");
        log.put("message", message);
        if (context != null) {
            log.putAll(context);
        }
        try {
            return MAPPER.writeValueAsString(log);
        } catch (Exception e) {
            return "{\"error\":\"log serialization failed\"}";
        }
    }

    /**
     * 生成 INFO 级别的结构化日志。
     *
     * @param message 日志消息
     * @param context 附加上下文键值对，可为 null
     * @return JSON 格式的结构化日志字符串
     */
    public static String info(String message, Map<String, Object> context) {
        Map<String, Object> log = base();
        log.put("level", "INFO");
        log.put("message", message);
        if (context != null) {
            log.putAll(context);
        }
        try {
            return MAPPER.writeValueAsString(log);
        } catch (Exception e) {
            return "{\"error\":\"log serialization failed\"}";
        }
    }

    /**
     * 生成 DEBUG 级别的结构化日志。
     *
     * @param message 日志消息
     * @param context 附加上下文键值对，可为 null
     * @return JSON 格式的结构化日志字符串
     */
    public static String debug(String message, Map<String, Object> context) {
        Map<String, Object> log = base();
        log.put("level", "DEBUG");
        log.put("message", message);
        if (context != null) {
            log.putAll(context);
        }
        try {
            return MAPPER.writeValueAsString(log);
        } catch (Exception e) {
            return "{\"error\":\"log serialization failed\"}";
        }
    }

    /**
     * 构建包含单个键值对的便捷上下文映射。
     *
     * @param key   键名
     * @param value 值
     * @return 包含单条键值对的 LinkedHashMap
     */
    public static Map<String, Object> ctx(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    /**
     * 构建一个空的上下文映射，方便后续链式添加键值对。
     *
     * @return 空的 LinkedHashMap
     */
    public static Map<String, Object> ctx() {
        return new LinkedHashMap<>();
    }
}
