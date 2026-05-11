package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.autoconfigure.facade.ConditionFilter;
import lyjew.com.lyclaw.autoconfigure.processor.ToolAnnotationProcessor;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.framework.exception.ToolNotFoundException;
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
 */
@AutoConfiguration
@ConditionalOnClass(ToolRegistry.class)
public class ToolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry() {
            private final Map<String, Tool> tools = new ConcurrentHashMap<>();

            @Override
            public void register(Tool tool) {
                tools.put(tool.getName(), tool);
            }

            @Override
            public Tool get(String name) {
                return tools.get(name);
            }

            @Override
            public List<ToolDefinition> getAllDefinitions() {
                return tools.values().stream()
                        .map(Tool::getDefinition)
                        .toList();
            }

            @Override
            public ToolResult execute(ToolCall call, ChatContext ctx) {
                Tool tool = tools.get(call.getName());
                if (tool == null) {
                    throw new ToolNotFoundException("Tool not found: " + call.getName());
                }
                return tool.execute(call, ctx);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolAnnotationProcessor toolAnnotationProcessor(ToolRegistry registry) {
        return new ToolAnnotationProcessor(registry);
    }

    @Bean
    public ConditionFilter conditionFilter(Environment env) {
        return new ConditionFilter(env);
    }
}
