package lyjew.com.lyclaw.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.MDC;
import lyjew.com.lyclaw.tracing.TraceConstants;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StructuredLogHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private StructuredLogHelper() {
    }

    private static Map<String, Object> base() {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("timestamp", Instant.now().toString());
        log.put("traceId", MDC.get(TraceConstants.MDC_TRACE_ID));
        log.put("spanId", MDC.get(TraceConstants.MDC_SPAN_ID));
        log.put("service", MDC.get(TraceConstants.MDC_SERVICE));
        return log;
    }

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

    public static Map<String, Object> ctx(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    public static Map<String, Object> ctx() {
        return new LinkedHashMap<>();
    }
}
