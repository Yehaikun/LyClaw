package lyjew.com.lyclaw.engine;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.model.ChatRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 引擎选择器，负责管理和选择合适的 AI 引擎。
 *
 * <p>EngineSelector 是框架的路由核心。在 Spring 容器启动后，它通过
 * {@link #init()} 方法自动扫描所有实现了 {@link Engine} 接口的 Bean，
 * 并将其注册到内部引擎列表中。当收到聊天请求时，调用 {@link #select(ChatRequest)}
 * 按顺序遍历引擎，返回第一个支持该请求的引擎。</p>
 *
 * <p>选择策略为「首次匹配」——注册的第一个支持该请求的引擎被选中。
 * 引擎的注册顺序取决于 Spring 扫描顺序，如果需要优先级控制，
 * 可通过手动调用 {@link #register(Engine)} 来调整。</p>
 */
@Slf4j
@Component
public class EngineSelector {

    /** 已注册的引擎列表，按注册顺序排列 */
    private final List<Engine> engines = new ArrayList<>();

    /** Spring 应用上下文，用于自动发现 Engine Bean */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Spring 容器启动后的初始化回调。
     * 自动扫描并注册所有 Engine 接口的实现 Bean。
     */
    @PostConstruct
    public void init() {
        // 从 Spring 容器中获取所有 Engine 类型的 Bean
        Map<String, Engine> engineBeans = applicationContext.getBeansOfType(Engine.class);
        if (engineBeans.isEmpty()) {
            log.warn("未找到任何 Engine 实现！请检查 @Component 注解和包扫描配置");
        }
        // 遍历并注册所有发现的引擎
        for (Engine engine : engineBeans.values()) {
            register(engine);
        }
        log.debug("EngineSelector 初始化完成，共注册 {} 个引擎", engines.size());
    }

    /**
     * 根据请求选择第一个支持该请求的引擎。
     * 遍历已注册的引擎列表，返回首个 {@link Engine#supports(ChatRequest)} 返回 true 的引擎。
     *
     * @param request 聊天请求对象
     * @return 首个支持该请求的引擎，若无匹配则返回 null
     */
    public Engine select(ChatRequest request) {
        for (Engine engine : engines) {
            if (engine.supports(request)) {
                return engine;
            }
        }
        return null;
    }

    /**
     * 手动注册一个引擎到选择器中。
     * 通常由 {@link #init()} 自动调用，也可手动调用以控制优先级。
     *
     * @param engine 要注册的引擎实例
     */
    public void register(Engine engine) {
        engines.add(engine);
    }

    /**
     * 获取当前已注册的所有引擎列表（防御性拷贝）。
     *
     * @return 引擎列表的副本，外部修改不影响内部状态
     */
    public List<Engine> getEngines() {
        return new ArrayList<>(engines);
    }
}
