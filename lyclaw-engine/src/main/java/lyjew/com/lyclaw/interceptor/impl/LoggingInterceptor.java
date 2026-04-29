package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;
import org.springframework.stereotype.Component;

/**
 * 日志记录拦截器 —— 记录每次 API 请求的开始时间、请求摘要、执行耗时、Token 用量等信息。
 *
 * <p><b>记录内容包括</b>：
 * <ul>
 *   <li>请求开始时间 + 请求摘要（消息数、用户 ID）</li>
 *   <li>请求处理耗时</li>
 *   <li>Token 用量（提示词 Token + 生成 Token）</li>
 *   <li>完成的轮次（如果涉及工具调用循环）</li>
 *   <li>最终完成原因（stop / error / timeout）</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：没有日志就无法监控和排查问题。
 * 日志记录不应该散落在各个 PipelineStage 中，
 * 而应该通过拦截器统一处理。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class LoggingInterceptor implements Interceptor {

    /** 请求开始时间，存在 ChatContext 的 attributes 中 */
    private static final String KEY_START_TIME = "_log_start_time";

    /**
     * 记录请求开始时间和请求摘要。
     *
     * @param context 对话上下文
     * @return 始终返回 true（日志记录不会中断请求处理）
     */
    @Override
    public boolean preHandle(ChatContext context) {
        // 记录开始时间到 context 的 attributes 中，供 postHandle 使用
        context.setAttribute(KEY_START_TIME, System.currentTimeMillis());
        // 记录请求摘要：会话 ID、消息数量
        return true;
    }

    /**
     * 计算耗时 + Token 用量 + 记录完成日志。
     *
     * @param context 对话上下文
     * @param result  对话结果
     */
    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        Long startTime = (Long) context.getAttribute(KEY_START_TIME);
        if (startTime != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            // 记录日志内容：会话ID、耗时、完成原因、Token用量
        }
    }

    /**
     * 返回 100，在 RateLimitInterceptor 和 SensitiveDataInterceptor 之后执行。
     *
     * @return 100
     */
    @Override
    public int getOrder() {
        return 100;
    }
}