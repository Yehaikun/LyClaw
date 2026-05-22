package lyjew.com.lyclaw.tracing;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

import jakarta.annotation.PostConstruct;

/**
 * 链路追踪自动配置 — 启用 Reactor 上下文自动传播，注册 per-key MDC accessor。
 *
 * <p>使用 per-key accessor（而非单一的 map-based accessor）：
 * Reactor 的 clearMissing 逻辑对每个 key 独立判断，
 * 避免一个 key 缺失导致整个 MDC 被误清。
 */
@Configuration
public class TraceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TraceAutoConfiguration.class);

    static {
        Hooks.enableAutomaticContextPropagation();
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(new PerKeyMdcAccessor(TraceConstants.MDC_TRACE_ID))
                .registerThreadLocalAccessor(new PerKeyMdcAccessor(TraceConstants.MDC_SPAN_ID));
    }

    @PostConstruct
    void init() {
        log.info("TraceAutoConfiguration initialized — MDC context propagation enabled");
    }

    @Bean
    TraceWebFilter traceWebFilter() {
        return new TraceWebFilter();
    }

    /**
     * Per-key MDC ThreadLocalAccessor — 仅管理单个 MDC 键。
     * key 使用 "slf4j.<mdcKey>" 格式，与 context-propagation 的
     * SelectiveMdcThreadLocalAccessor 命名惯例一致。
     */
    static class PerKeyMdcAccessor implements ThreadLocalAccessor<String> {

        private final String mdcKey;

        PerKeyMdcAccessor(String mdcKey) {
            this.mdcKey = mdcKey;
        }

        @Override
        public Object key() {
            return "slf4j." + mdcKey;
        }

        @Override
        public String getValue() {
            return MDC.get(mdcKey);
        }

        @Override
        public void setValue(String value) {
            if (value != null) {
                MDC.put(mdcKey, value);
            }
        }

        @Override
        public void setValue() {
            // no-op: TraceWebFilter.doFinally 已负责清理 MDC。
            // 不能在此处 remove，否则 Reactor 的 clearMissing 机制
            // 会在每次上下文快照生命周期中误清 TraceWebFilter 刚设置的 MDC 值。
        }
    }
}
