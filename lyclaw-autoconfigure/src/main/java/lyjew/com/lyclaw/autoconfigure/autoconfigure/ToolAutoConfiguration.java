package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.facade.ConditionFilter;
import lyjew.com.lyclaw.autoconfigure.processor.ToolAnnotationProcessor;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.exception.ToolNotFoundException;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-configuration for the {@link ToolRegistry} and its related
 * discovery / filtering beans.
 *
 * <p>The {@code toolRegistry} bean is only created when no other
 * {@link ToolRegistry} implementation (e.g. {@code DefaultToolRegistry})
 * is already present in the context — this avoids duplicate registries.</p>
 */
@AutoConfiguration
@ConditionalOnClass(ToolRegistry.class)
public class ToolAutoConfiguration {

    /**
     * Fallback {@link ToolRegistry} — only created when the action module's
     * {@code DefaultToolRegistry} is not available (services outside the action scope).
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry() {
            private final Map<String, Tool> tools = new ConcurrentHashMap<>();

            @Override
            public void register(Tool tool) { tools.put(tool.getName(), tool); }

            @Override
            public Tool get(String name) { return tools.get(name); }

            @Override
            public List<ToolDefinition> getAllDefinitions() {
                return tools.values().stream().map(Tool::getDefinition).toList();
            }

            @Override
            public ToolResult execute(ToolCall call, ChatContext ctx) {
                Tool tool = tools.get(call.getName());
                if (tool == null) throw new ToolNotFoundException("Tool not found: " + call.getName());
                return tool.execute(call, ctx);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolAnnotationProcessor toolAnnotationProcessor(ToolRegistry registry) {
        return new ToolAnnotationProcessor(registry);
    }

    /**
     * 注册条件过滤器，根据环境配置决定工具是否可用。
     *
     * @param env Spring环境对象
     * @return 条件过滤器实例
     */
    @Bean
    public ConditionFilter conditionFilter(Environment env) {
        return new ConditionFilter(env);
    }
}
