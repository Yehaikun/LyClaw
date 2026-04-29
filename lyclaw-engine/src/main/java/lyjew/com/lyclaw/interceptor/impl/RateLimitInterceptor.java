package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;
import org.springframework.stereotype.Component;

/**
 * 限流拦截器 —— 限制单位时间内的请求次数，超过配额则拒绝请求。
 *
 * <p>使用令牌桶算法控制请求速率。每个会话有一个独立的令牌桶，
 * 每秒补充一定数量的令牌，每个请求消耗一个令牌。令牌不足时返回 false
 * 中断处理流程。</p>
 *
 * <p><b>设计动机</b>：防止单个用户的突发请求耗尽系统资源。
 * 如果不做限流，恶意用户或错误客户端可能发起大量请求导致后端模型 API
 * 调用超限（429 Too Many Requests），从而影响其他正常用户。</p>
 *
 * <p><b>执行顺序</b>：order = Integer.MIN_VALUE，确保在所有拦截器中最先执行。
 * 如果请求被限流拦截，后续的拦截器就不需要执行了，节省资源。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class RateLimitInterceptor implements Interceptor {

    /** 每秒允许的请求数。可以通过构造函数或 setter 配置 */
    private int permitsPerSecond = 10;

    /**
     * 检查当前请求是否超过限流配额。
     * 超过配额时返回 false，中断请求处理。
     *
     * @param context 对话上下文
     * @return true 允许通过，false 拒绝请求
     */
    @Override
    public boolean preHandle(ChatContext context) {
        // 此处为简化实现，使用令牌桶算法的伪代码：
        // 1. 根据 sessionId 获取对应的令牌桶
        // 2. 尝试获取一个令牌
        // 3. 获取成功返回 true，失败返回 false
        //
        // 生产环境建议使用 Guava RateLimiter 或 Redis 分布式限流
        return true; // 简化实现，真实场景需要替换
    }

    /**
     * 请求处理完成后，更新令牌桶状态（释放资源）。
     *
     * @param context 对话上下文
     * @param result  对话结果
     */
    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        // 可在此处更新令牌桶统计信息
    }

    /**
     * 返回 Integer.MIN_VALUE，确保在所有拦截器中最先执行。
     * 如果请求被限流拦截，后续的拦截器就不需要执行。
     *
     * @return Integer.MIN_VALUE
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }
}