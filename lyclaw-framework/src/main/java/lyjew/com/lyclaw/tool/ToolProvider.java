package lyjew.com.lyclaw.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.ToolExecutor;

/**
 * 动态工具提供者 — 将"工具怎么描述给 LLM"（ToolDefinition）和"工具怎么执行"
 * （ToolExecutor）完全解耦。
 *
 * <p>灵感来自 langchain4j 的 ToolProvider。与静态的 {@link Tool} 接口不同，
 * ToolProvider 允许每次调用时动态决定提供哪些工具，适用于：
 * <ul>
 *   <li>MCP 协议工具（运行时发现）</li>
 *   <li>权限驱动的工具（不同用户看到不同工具）</li>
 *   <li>条件工具（根据上下文决定是否可用）</li>
 * </ul>
 *
 * <p>每个 ToolProvider 返回一个映射：ToolDefinition（给 LLM 看的菜单）
 * → ToolExecutor（实际执行逻辑）。两者完全独立，可以独立测试、独立替换。
 *
 * <pre>
 * ToolProvider provider = request -> {
 *     var result = new ToolProviderResult();
 *     result.add(ToolDefinition.builder().name("weather")...build(),
 *                (name, id, args) -> fetchWeather(args));
 *     return result;
 * };
 * toolRegistry.registerProvider(provider);
 * </pre>
 */
@FunctionalInterface
public interface ToolProvider {

    /**
     * 是否为动态提供者。动态提供者每次调用时重新查询工具列表，
     * 静态提供者只在注册时查询一次。
     */
    default boolean isDynamic() {
        return true;
    }

    /**
     * 根据当前请求上下文提供工具。
     *
     * @param request 工具提供请求，包含当前的 ChatRequest 和上下文信息
     * @return 工具清单（定义 + 执行器的映射）
     */
    ToolProviderResult provideTools(ToolProviderRequest request);

    /**
     * 工具提供结果 — 将 ToolDefinition（spec）和 ToolExecutor（exec）配对。
     */
    class ToolProviderResult {
        private final Map<String, ToolDefinition> definitions = new HashMap<>();
        private final Map<String, ToolExecutor> executors = new HashMap<>();

        /**
         * 注册一对工具（spec + executor）。
         *
         * @param definition 工具定义（给 LLM 看的描述）
         * @param executor   工具执行器（实际执行逻辑）
         */
        public void add(ToolDefinition definition, ToolExecutor executor) {
            String name = definition.getName();
            definitions.put(name, definition);
            executors.put(name, executor);
        }

        /** 将所有工具定义作为不可修改列表返回（用于发给 LLM 的 tools 字段） */
        public List<ToolDefinition> getDefinitions() {
            return List.copyOf(definitions.values());
        }

        /** 按名称查找工具执行器 */
        public ToolExecutor getExecutor(String toolName) {
            return executors.get(toolName);
        }

        /** 按名称查找工具定义 */
        public ToolDefinition getDefinition(String toolName) {
            return definitions.get(toolName);
        }

        /** 所有工具名称 */
        public java.util.Set<String> getToolNames() {
            return Collections.unmodifiableSet(definitions.keySet());
        }

        /** 工具数量 */
        public int size() {
            return definitions.size();
        }

        public boolean isEmpty() {
            return definitions.isEmpty();
        }
    }
}
