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

@Slf4j
@Component
public class EngineSelector {

    private final List<Engine> engines = new ArrayList<>();

    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    public void init() {
        Map<String, Engine> engineBeans = applicationContext.getBeansOfType(Engine.class);
        if (engineBeans.isEmpty()) {
            log.warn("未找到任何 Engine 实现！请检查 @Component 注解和包扫描配置");
        }
        for (Engine engine : engineBeans.values()) {
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
