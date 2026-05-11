package lyjew.com.lyclaw.autoconfigure.binding;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Adapts a {@code @Tool}-annotated POJO method into the framework {@link Tool}
 * interface.  Parameter binding is handled by {@link ParameterBindingDescriptor}.
 */
public class AnnotatedToolAdapter implements Tool {

    private final Object target;
    private final String name;
    private final String description;
    private final boolean readonly;
    private final ToolDefinition definition;
    private final Method executeMethod;
    private final ParameterBindingDescriptor bindingDescriptor;

    public AnnotatedToolAdapter(Object target, String name, String description,
                                 boolean readonly, ToolDefinition definition,
                                 Method executeMethod) {
        this.target = target;
        this.name = name;
        this.description = description;
        this.readonly = readonly;
        this.definition = definition;
        this.executeMethod = executeMethod;
        this.bindingDescriptor = new ParameterBindingDescriptor(executeMethod);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> args = ParameterBinder.bindToMap(toolCall.getArguments());
            Object result = bindingDescriptor.bindAndInvoke(target, args);
            long elapsed = System.currentTimeMillis() - start;
            String resultStr = result != null ? result.toString() : "";
            return new ToolResult(true, resultStr, null, elapsed, 0);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new ToolResult(false, null, e.getMessage(), elapsed, 0);
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }
}
