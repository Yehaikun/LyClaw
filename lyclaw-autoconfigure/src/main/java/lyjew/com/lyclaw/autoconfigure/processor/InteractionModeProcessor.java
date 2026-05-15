package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.react.InteractionMode;
import lyjew.com.lyclaw.react.ReActEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link BeanPostProcessor} 实现，自动发现标注了 {@link InteractionMode} 注解
 * 的 Spring Bean，验证接口实现并构建按名称索引的交互模式注册表。
 *
 * <p>处理流程：
 * <ol>
 *   <li>检查 Bean 是否标注了 {@code @InteractionMode} 注解</li>
 *   <li>校验 Bean 是否实现了 {@link ReActEngine} 接口</li>
 *   <li>提取注解中的 name、description、isDefault 属性</li>
 *   <li>将引擎实例存入 {@code engines} 注册表，记录默认引擎</li>
 * </ol>
 *
 * <p>在所有单例初始化完成后输出启动摘要日志，展示已发现的所有交互模式。
 */
public class InteractionModeProcessor implements BeanPostProcessor, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(InteractionModeProcessor.class);

    private final Map<String, ReActEngine> engines = new LinkedHashMap<>();
    private String defaultName;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        InteractionMode annotation = bean.getClass().getAnnotation(InteractionMode.class);
        if (annotation == null) return bean;

        if (!(bean instanceof ReActEngine engine)) {
            log.error("类 {} 标注了 @InteractionMode 但未实现 ReActEngine 接口，已跳过",
                    bean.getClass().getName());
            return bean;
        }

        String name = annotation.name();
        engines.put(name, engine);
        if (annotation.isDefault()) {
            if (defaultName != null) {
                log.warn("检测到多个 @InteractionMode 声明 isDefault=true: {} 和 {}，"
                        + "将使用后发现的 {}", defaultName, name, name);
            }
            defaultName = name;
        }

        log.info("注册 InteractionMode: name={}, class={}, isDefault={}, description={}",
                name, bean.getClass().getSimpleName(), annotation.isDefault(), annotation.description());
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        log.info("============================================");
        log.info("  InteractionModeProcessor 交互模式扫描完成");
        log.info("  共发现 {} 个交互模式", engines.size());
        if (engines.isEmpty()) {
            log.info("  (无交互模式可用，将退化为简单 LLM 调用)");
        } else {
            engines.forEach((name, engine) -> {
                InteractionMode ann = engine.getClass().getAnnotation(InteractionMode.class);
                String marker = name.equals(defaultName) ? " [默认]" : "";
                log.info("    - {} ({}){} — {}",
                        name, engine.getClass().getSimpleName(), marker,
                        ann != null ? ann.description() : "");
            });
        }
        log.info("============================================");
    }

    /**
     * 获取默认交互模式引擎。
     *
     * @return 默认引擎，无可用引擎时返回 null,防御性编程：实际几乎不可能为null
     */
    public ReActEngine getDefault() {
        if (defaultName != null) {
            ReActEngine engine = engines.get(defaultName);
            if (engine != null) return engine;
        }
        return engines.isEmpty() ? null : engines.values().iterator().next();
    }

    /**
     * 按名称获取指定交互模式引擎。
     *
     * @param name 交互模式名称（如 "react"、"cot"）
     * @return 对应引擎，不存在时返回 null
     */
    public ReActEngine get(String name) {
        return engines.get(name);
    }

    /** @return 只读的交互模式注册表 */
    public Map<String, ReActEngine> getEngines() {
        return Collections.unmodifiableMap(engines);
    }

    /** @return 已发现的交互模式数量 */
    public int getEngineCount() {
        return engines.size();
    }
}
