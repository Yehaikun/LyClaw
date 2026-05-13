package lyjew.com.lyclaw.autoconfigure.binding;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 将 @Tool 注解的 POJO 适配为 Tool 接口。
 * 通过 ParameterBindingDescriptor 完成参数绑定和反射调用。
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
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> args = ParameterBinder.bindToMap(toolCall.getArguments());
            Object result = bindingDescriptor.bindAndInvoke(target, args);
            long elapsed = System.currentTimeMillis() - start;
            String resultStr = result != null ? result.toString() : "";
            return ToolExecutionResult.builder()
                    .success(true)
                    .result(resultStr)
                    .elapsedMs(elapsed)
                    .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
             return ToolExecutionResult.builder()
                     .success(false)
                     .error(e.getMessage())
                     .elapsedMs(elapsed)
                     .build();
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }
}
