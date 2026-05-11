package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.base.exception.LyClawException;
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

    public ModelRouterPostProcessor(ChatModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 190;
    }

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
