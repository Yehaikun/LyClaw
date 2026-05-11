package lyjew.com.lyclaw.orchestration.web;

import lyjew.com.lyclaw.base.exception.LyClawException;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GlobalErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request,
                                                   ErrorAttributeOptions options) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("timestamp", Instant.now().toString());
        attrs.put("path", request.path());
        attrs.put("status", resolveStatus(request));
        attrs.put("error", getError(request).getMessage());
        attrs.put("traceId", MDC.get("traceId"));
        attrs.put("spanId", MDC.get("spanId"));
        attrs.put("service", MDC.get("service"));
        return attrs;
    }

    private int resolveStatus(ServerRequest request) {
        Throwable error = getError(request);
        if (error instanceof LyClawException le) {
            return le.getHttpStatus();
        }
        return 500;
    }
}
