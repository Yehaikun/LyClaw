package lyjew.com.lyclaw.config;

import java.util.Map;

/**
 * Agent 配置源 SPI——可从不同来源加载 agent 配置。
 *
 * <p>各来源按优先级叠加：{@code yml (10) → DB (60) → 配置中心 (70) → 注解 (50) → Builder (100)}。
 * 优先级数值越大越优先，同 key 高优先级覆盖低优先级。
 *
 * <p>框架内置实现：
 * <ul>
 *   <li>YamlAgentConfigSource — 从 application.yml 读取</li>
 *   <li>AnnotationAgentConfigSource — 从 @Agent 注解读取</li>
 *   <li>BuilderAgentConfigSource — 从 LyClawAgent.builder() 读取</li>
 * </ul>
 */
public interface AgentConfigSource {
    /**
     * 加载指定 agent 的配置键值对。
     *
     * @param agentName agent 名称
     * @return 配置键值对，无配置时返回空 Map
     */
    Map<String, String> loadConfig(String agentName);

    /** 配置源优先级，数值越大越优先 */
    default int getPriority() { return 0; }

    /** 配置源名称，用于日志和调试 */
    default String getSourceName() { return getClass().getSimpleName(); }
}
