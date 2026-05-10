package lyjew.com.lyclaw.engine;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.model.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 引擎选择器 —— 遍历所有注册的 Engine，调用 supports() 返回第一个匹配的引擎。
 *
 * <p><b>自动注册机制</b>：通过 @PostConstruct 从 ApplicationContext 中
 * 发现所有 Engine 类型的 Bean 并自动注册。新增引擎只需写一个 @Component 类
 * 实现 Engine 接口，不需要手动调用 register()。</p>
 *
 * <p>引擎匹配机制：
 * <ol>
 *   <li>按注册顺序遍历内部引擎列表</li>
 *   <li>对每个引擎调用 supports(ChatRequest)</li>
 *   <li>返回第一个返回 true 的引擎</li>
 *   <li>没有匹配时返回 null（由调用方决定降级策略）</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Engine
 * @see lyjew.com.lyclaw.engine.impl.DefaultEngine
 */
@Slf4j
@Component
public class EngineSelector {

    /** 已注册的引擎列表 */
    private final List<Engine> engines = new ArrayList<>();

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 初始化时自动扫描并注册所有 Engine Bean。
     */
    @PostConstruct
    public void init() {
        Map<String, Engine> engineBeans = applicationContext.getBeansOfType(Engine.class);
        log.debug("扫描到 {} 个 Engine Bean: {}", engineBeans.size(), engineBeans.keySet());

        if (engineBeans.isEmpty()) {
            log.warn("未找到任何 Engine 实现！请检查 @Component 注解和包扫描配置");
        }

        for (Engine engine : engineBeans.values()) {
            log.debug("注册引擎: [{}] → {}", engine.getName(), engine.getClass().getSimpleName());
            register(engine);
        }

        log.debug("EngineSelector 初始化完成，共注册 {} 个引擎", engines.size());
    }

    public Engine select(ChatRequest request) {
        for (Engine engine : engines) {
            if (engine.supports(request)) {
                return engine;
            }
        }
        return null;
    }

    public void register(Engine engine) {
        engines.add(engine);
    }

    public List<Engine> getEngines() {
        return new ArrayList<>(engines);
    }
}