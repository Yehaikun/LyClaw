package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第二阶段 —— 拦截器执行阶段。
 *
 * <p>按 @Order 顺序执行所有注册的拦截器的 preHandle() 方法。
 * 如果任何一个拦截器抛出异常，流程终止并交由 ErrorPolicy 处理。</p>
 *
 * <p><b>设计动机</b>：拦截器（限流、日志、脱敏）是横切关注点，不应该散落在各个阶段中。
 * 集中在一个阶段执行，既保证了执行顺序可控，又使得新增拦截器时只需加 @Component。
 * <ul>
 *   <li>RateLimitInterceptor — 检查请求频率</li>
 *   <li>LoggingInterceptor — 记录请求日志</li>
 *   <li>SensitiveDataInterceptor — 敏感数据脱敏</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see InterceptorChain
 */
@Component
public class InterceptorStage implements PipelineStage {

    /** 拦截器链管理器 */
    private final InterceptorChain interceptorChain;

    public InterceptorStage(InterceptorChain interceptorChain) {
        this.interceptorChain = interceptorChain;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 执行所有拦截器的 preHandle
        interceptorChain.preHandle(context);
        chain.next(context);
    }

    @Override
    public int getOrder() {
        return 1; // 第二阶段
    }

    @Override
    public String getStageName() {
        return "Interceptor";
    }
}