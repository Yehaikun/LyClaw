package lyjew.com.lyclaw.tracing;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

public class TraceHeaderFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String traceId = httpReq.getHeader(TraceConstants.HEADER_TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String parentSpanId = httpReq.getHeader(TraceConstants.HEADER_PARENT_SPAN_ID);
        String spanId = UUID.randomUUID().toString().replace("-", "");

        MDC.put(TraceConstants.MDC_TRACE_ID, traceId);
        MDC.put(TraceConstants.MDC_SPAN_ID, spanId);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            MDC.put("parentSpanId", parentSpanId);
        }

        try {
            httpResp.setHeader(TraceConstants.HEADER_TRACE_ID, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceConstants.MDC_TRACE_ID);
            MDC.remove(TraceConstants.MDC_SPAN_ID);
            MDC.remove("parentSpanId");
        }
    }
}
