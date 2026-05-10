package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.security.SecurityManager;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InterceptorStage implements PipelineStage {

    private final InterceptorChain interceptorChain;
    private final SecurityManager securityManager;
    private final ContentFilter contentFilter;

    public InterceptorStage(InterceptorChain interceptorChain,
                            SecurityManager securityManager,
                            ContentFilter contentFilter) {
        this.interceptorChain = interceptorChain;
        this.securityManager = securityManager;
        this.contentFilter = contentFilter;
        log.info("[InterceptorStage] Initialized: interceptors={}",
                interceptorChain.getInterceptors().size());
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("[InterceptorStage] Starting: executing {} interceptors...",
                interceptorChain.getInterceptors().size());

        boolean chainPassed = interceptorChain.preHandle(context);
        if (!chainPassed) {
            log.warn("[InterceptorStage] Interceptor chain rejected the request, breaking pipeline");
            context.setAttribute("__chain_break_reason__", "Interceptor chain rejected request");
            chain.breakChain(context);
            return;
        }
        log.info("[InterceptorStage] Interceptor chain passed");

        if (securityManager != null) {
            try {
                var approval = securityManager.approve(context, "EXECUTE_CHAT");
                if (!approval.isApproved()) {
                    log.warn("[InterceptorStage] Security check denied: {}", approval.getReason());
                    context.setAttribute("__chain_break_reason__",
                            "Security denied: " + approval.getReason());
                    chain.breakChain(context);
                    return;
                }
                log.info("[InterceptorStage] Security check passed: sandboxLevel={}", approval.getSandboxLevel());
            } catch (Exception e) {
                log.error("[InterceptorStage] Security check error: {}", e.getMessage(), e);
                context.setAttribute("__chain_break_reason__",
                        "Security check exception: " + e.getMessage());
                chain.breakChain(context);
                return;
            }
        }

        if (contentFilter != null) {
            try {
                String userMessage = context.getRequest().getLastUserMessage();
                if (userMessage != null && !userMessage.isEmpty()) {
                    FilterResult filterResult = contentFilter.filter(userMessage, context);
                    if (!filterResult.isPassed()) {
                        log.warn("[InterceptorStage] Content filter blocked: {} (filter={})",
                                filterResult.getReason(), contentFilter.getFilterName());
                        context.setAttribute("__chain_break_reason__",
                                "Content filter blocked: " + filterResult.getReason());
                        context.setAttribute("__filter_result__", filterResult);
                        chain.breakChain(context);
                        return;
                    }
                    log.info("[InterceptorStage] Content filter passed: filter={}", contentFilter.getFilterName());
                }
            } catch (Exception e) {
                log.error("[InterceptorStage] Content filter error: {}", e.getMessage(), e);
                context.setAttribute("__chain_break_reason__",
                        "Content filter exception: " + e.getMessage());
                chain.breakChain(context);
                return;
            }
        }

        log.info("[InterceptorStage] Completed — all checks passed");
        chain.next(context);
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public String getStageName() {
        return "Interceptor";
    }
}
