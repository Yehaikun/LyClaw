package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第二阶段 —— 拦截器执行阶段。
 *
 * <p>按 @Order 顺序执行所有注册的拦截器的 preHandle() 方法。
 * 如果任何一个拦截器抛出异常，流程终止并交由 ErrorPolicy 处理。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see InterceptorChain
 */
@Slf4j
@Component
public class InterceptorStage implements PipelineStage {

    private final InterceptorChain interceptorChain;

    public InterceptorStage(InterceptorChain interceptorChain) {
        this.interceptorChain = interceptorChain;
        log.info("  [InterceptorStage] 构造器");
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("  [InterceptorStage] 开始：执行 {} 个拦截器的 preHandle...",
                interceptorChain.getInterceptors().size());
        interceptorChain.preHandle(context);
        log.info("  [InterceptorStage] 完成");
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
