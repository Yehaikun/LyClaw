package lyjew.com.lyclaw.autoconfigure.config;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;
import lyjew.com.lyclaw.config.AgentConfigSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 @Agent 注解读取配置的配置源，优先级 50。
 *
 * <p>扫描所有带 @Agent 注解的 Bean，提取核心属性 + {@code extensions} 键值对。
 */
public class AnnotationAgentConfigSource implements AgentConfigSource, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Map<String, String> loadConfig(String agentName) {
        Map<String, String> config = new HashMap<>();
        if (applicationContext == null) return config;

        Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(Agent.class);
        for (Object bean : agentBeans.values()) {
            Agent ann = bean.getClass().getAnnotation(Agent.class);
            if (ann == null) continue;
            String name = ann.name().isEmpty() ? bean.getClass().getSimpleName() : ann.name();
            if (!name.equals(agentName)) continue;

            if (!ann.description().isEmpty()) config.put("description", ann.description());
            if (!ann.model().isEmpty()) config.put("model", ann.model());
            if (!ann.provider().isEmpty()) config.put("provider", ann.provider());
            if (!ann.version().isEmpty()) config.put("version", ann.version());
            for (Extension ext : ann.extensions()) {
                config.put(ext.key(), ext.value());
            }
            break;
        }
        return config;
    }

    @Override
    public List<String> listAgentNames() {
        if (applicationContext == null) return List.of();
        List<String> names = new ArrayList<>();
        Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(Agent.class);
        for (Object bean : agentBeans.values()) {
            Agent ann = bean.getClass().getAnnotation(Agent.class);
            if (ann == null) continue;
            String name = ann.name().isEmpty() ? bean.getClass().getSimpleName() : ann.name();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    @Override public int getPriority() { return 50; }
    @Override public String getSourceName() { return "annotation"; }
}
