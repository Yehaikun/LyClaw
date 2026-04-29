package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.context.impl.FullWindowContextBuilder;
import lyjew.com.lyclaw.interceptor.impl.LoggingInterceptor;
import lyjew.com.lyclaw.interceptor.impl.RateLimitInterceptor;
import lyjew.com.lyclaw.interceptor.impl.SensitiveDataInterceptor;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.pipeline.impl.stages.ContextBuildStage;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.storage.SessionStorage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyEngineConfig — 用户自定义 Bean 配置。
 *
 * <p>补充 EngineAutoConfiguration 中因外部依赖跳过自动装配的 Bean。
 * 当 SessionStorage、MemoryManager、SkillRegistry 等 Bean 就绪后，Spring 自动注入到此配置类。
 */
@Configuration
public class MyEngineConfig {

    /** 限流拦截器：每分钟最多 60 次请求。第二版使用配置中心动态调整。 */
    @Bean
    public RateLimitInterceptor rateLimitInterceptor() {
        return new RateLimitInterceptor(60);
    }

    /** 敏感数据脱敏拦截器。默认将所有消息中的数字序列替换为 ****。 */
    @Bean
    public SensitiveDataInterceptor sensitiveDataInterceptor() {
        return new SensitiveDataInterceptor();
    }

    /** 日志拦截器。记录请求/响应的关键信息。无配置参数。 */
    @Bean
    public LoggingInterceptor loggingInterceptor() {
        return new LoggingInterceptor();
    }

    /**
     * 全量窗口上下文构建策略。
     * 将全部会话历史 + 全部记忆 + 全部可用工具注入上下文。
     */
    @Bean
    public FullWindowContextBuilder fullWindowContextBuilder(
            @Autowired(required = false) SessionStorage sessionStorage,
            @Autowired(required = false) MemoryManager memoryManager,
            @Autowired(required = false) SkillRegistry skillRegistry) {
        return new FullWindowContextBuilder(sessionStorage, memoryManager, skillRegistry);
    }

    /**
     * 上下文构建 Pipeline Stage。
     * 依赖已注册的 SessionStorage、MemoryManager、ContextBuilder、SkillRegistry。
     */
    @Bean
    public ContextBuildStage contextBuildStage(
            @Autowired(required = false) SessionStorage sessionStorage,
            @Autowired(required = false) MemoryManager memoryManager,
            @Autowired(required = false) ContextBuilder contextBuilder,
            @Autowired(required = false) SkillRegistry skillRegistry) {
        return new ContextBuildStage(
            sessionStorage, memoryManager, contextBuilder, skillRegistry);
    }
}
