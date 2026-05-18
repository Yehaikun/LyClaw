package lyjew.com.lyclaw.config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 配置解析器——从多个 {@link AgentConfigSource} 按优先级合并配置。
 *
 * <p>优先级（数值越大越优先）：
 * <pre>
 *   application.yml     →  10
 *   DB 存储             →  60
 *   配置中心             →  70
 *   @Agent 注解          →  50
 *   AgentBuilder        → 100  (最高优先级)
 * </pre>
 */
public class AgentConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigResolver.class);

    private final List<AgentConfigSource> sources = new ArrayList<>();

    /** 注册配置源（按优先级自动排序） */
    public void registerSource(AgentConfigSource source) {
        sources.add(source);
        sources.sort(Comparator.comparingInt(AgentConfigSource::getPriority).reversed());
    }

    /** 解析指定 agent 的合并配置 */
    public AgentConfig resolve(String agentName) {
        // key → (priority, value)
        Map<String, PriorityValue> merged = new LinkedHashMap<>();

        for (AgentConfigSource source : sources) {
            Map<String, String> config = source.loadConfig(agentName);
            if (config == null || config.isEmpty()) continue;

            int prio = source.getPriority();
            for (Map.Entry<String, String> e : config.entrySet()) {
                String key = e.getKey();
                PriorityValue existing = merged.get(key);
                if (existing == null || prio > existing.priority) {
                    merged.put(key, new PriorityValue(e.getValue(), prio, source.getSourceName()));
                }
            }
        }

        AgentConfig result = new AgentConfig(agentName);

        // 提取核心字段
        PriorityValue desc = merged.remove("description");
        if (desc != null) result.setDescription(desc.value);
        PriorityValue version = merged.remove("version");
        if (version != null) result.setVersion(version.value);
        PriorityValue model = merged.remove("model");
        if (model != null) result.setModel(model.value);
        PriorityValue provider = merged.remove("provider");
        if (provider != null) result.setProvider(provider.value);

        // 其余全部进入 extensions
        for (Map.Entry<String, PriorityValue> e : merged.entrySet()) {
            result.addExtension(e.getKey(), e.getValue().value);
        }

        log.debug("AgentConfig resolved for {}: {}", agentName, result);
        return result;
    }

    /** 从 @Agent 注解解析核心属性 */
    public static AgentConfig fromAnnotation(Agent ann) {
        AgentConfig config = new AgentConfig(ann.name());
        if (!ann.description().isEmpty()) config.setDescription(ann.description());
        if (!ann.model().isEmpty()) config.setModel(ann.model());
        if (!ann.provider().isEmpty()) config.setProvider(ann.provider());
        if (!ann.version().isEmpty()) config.setVersion(ann.version());
        for (Extension ext : ann.extensions()) {
            config.addExtension(ext.key(), ext.value());
        }
        return config;
    }

    /** 从 Map 解析（用于 yml / DB 配置源） */
    public static AgentConfig fromMap(String name, Map<String, String> map) {
        AgentConfig config = new AgentConfig(name);
        config.setDescription(map.get("description"));
        config.setModel(map.get("model"));
        config.setProvider(map.get("provider"));
        config.setVersion(map.getOrDefault("version", "1.0.0"));
        for (Map.Entry<String, String> e : map.entrySet()) {
            String k = e.getKey();
            if ("description".equals(k) || "model".equals(k) || "provider".equals(k)
                    || "version".equals(k) || "name".equals(k)) continue;
            config.addExtension(k, e.getValue());
        }
        return config;
    }

    private record PriorityValue(String value, int priority, String source) {}
}
