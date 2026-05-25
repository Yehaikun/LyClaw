package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.exception.LyClawException;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.FirstAvailableRouter;
import lyjew.com.lyclaw.enums.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.util.*;

/**
 * 路由策略注册处理器，扫描 {@code @ModelRouter} 注解的 Bean 并注册到框架路由表。
 *
 * <p>执行顺序：LOWEST_PRECEDENCE - 190，在 ChatModel 注册之后。
 * 如果没有任何路由 Bean，框架使用 FirstAvailableRouter 作为兜底。</p>
 */
public class ModelRouterPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterPostProcessor.class);

    private final Map<String, lyjew.com.lyclaw.chat.ModelRouter> routers = new LinkedHashMap<>();
    private lyjew.com.lyclaw.chat.ModelRouter defaultRouter;
    private final ChatModelRegistry modelRegistry;

    /**
     * 构造 ModelRouter 后处理器，注入 ChatModelRegistry 依赖。
     *
     * <p>ChatModelRegistry 在此处理器中有两个用途：一是作为兜底——当没有自定义路由
     * Bean 时，使用 FirstAvailableRouter 作为默认路由并传入此 registry；二是在日志
     * 中记录与注册表相关的诊断信息。通过构造器注入确保依赖在处理器激活前已就绪。</p>
     *
     * @param modelRegistry ChatModel 注册表实例，由 ChatAutoConfiguration 创建并注入
     */
    public ModelRouterPostProcessor(ChatModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * 返回此 BeanPostProcessor 的执行顺序值，数值越小优先级越高。
     *
     * <p>返回 {@code Ordered.LOWEST_PRECEDENCE - 190}，确保在 ChatModelPostProcessor
     * （优先级为 -200）之后执行。这个顺序至关重要——必须先有 ChatModel 注册到
     * Registry 中，路由策略才能从中选择可用的模型。如果路由器在模型注册之前执行，
     * 会导致路由表为空，无法正常工作。</p>
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE} - 190
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 190;
    }

    /**
     * Spring Bean 后处理器核心方法，在 Bean 初始化完成后被容器调用，负责发现和注册模型路由策略。
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li><b>注解检测：</b>检查 Bean 类上是否有 {@code @ModelRouter} 注解，无注解则跳过。</li>
     *   <li><b>接口校验：</b>强制要求标注了 @ModelRouter 的 Bean 必须实现 {@code ModelRouter}
     *       接口，未实现则抛出 {@link LyClawException} 异常（ADAPTER_NOT_FOUND），防止
     *       配置错误导致路由选择失败。</li>
     *   <li><b>路由注册：</b>从注解中提取 name 属性作为路由策略的唯一标识，将路由实例存入
     *       {@code routers} Map 中，并输出包含延迟估计信息的注册日志。</li>
     *   <li><b>默认路由设置：</b>如果注解的 {@code defaultRouter} 属性为 true，则将该路由
     *       设为全局默认路由。如果已存在默认路由，记录警告日志并忽略此设置，确保默认路由
     *       的唯一性。</li>
     * </ol>
     *
     * <p><b>兜底机制：</b>{@link #getActiveRouter()} 方法提供了三级回退策略——优先使用
     * 显式声明的默认路由，其次使用注册表中任意一个路由，最后回退到 FirstAvailableRouter。
     * 这确保了即使没有任何自定义路由，系统也能以"首选可用"策略正常工作。</p>
     *
     * @param bean Spring 容器中已初始化的 Bean 实例，可能带有 @ModelRouter 注解
     * @param beanName Bean 在 Spring 容器中的注册名称，用于日志记录
     * @return 始终返回原始 bean 实例
     * @throws BeansException 当 @ModelRouter 标注的 Bean 未实现 ModelRouter 接口时抛出
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        lyjew.com.lyclaw.annotation.chat.ModelRouter annotation =
                bean.getClass().getAnnotation(lyjew.com.lyclaw.annotation.chat.ModelRouter.class);
        if (annotation == null) return bean;

        if (!(bean instanceof lyjew.com.lyclaw.chat.ModelRouter router)) {
            throw new LyClawException(
                    ErrorCode.ADAPTER_NOT_FOUND.code(), ErrorCode.ADAPTER_NOT_FOUND.httpStatus(),
                    "类 " + bean.getClass().getName() + " 标注了 @ModelRouter 但未实现 ModelRouter 接口");
        }

        String name = annotation.name();
        routers.put(name, router);
        log.info("注册路由策略: {} ({}), 延迟估计: {}ms",
                name, annotation.description(), annotation.estimatedLatencyMs());

        if (annotation.defaultRouter()) {
            if (defaultRouter != null) {
                log.warn("已有默认路由 {}，{} 的 defaultRouter=true 被忽略",
                        defaultRouter.getClass().getSimpleName(), beanName);
            } else {
                defaultRouter = router;
                log.info("设置默认路由策略: {}", name);
            }
        }

        return bean;
    }

    /** 获取当前激活的路由策略 */
    public lyjew.com.lyclaw.chat.ModelRouter getActiveRouter() {
        if (defaultRouter != null) return defaultRouter;
        if (!routers.isEmpty()) return routers.values().iterator().next();
        return new FirstAvailableRouter(modelRegistry);
    }

    /** 获取所有已注册的路由策略 */
    public Map<String, lyjew.com.lyclaw.chat.ModelRouter> getRouters() {
        return Collections.unmodifiableMap(routers);
    }
}
