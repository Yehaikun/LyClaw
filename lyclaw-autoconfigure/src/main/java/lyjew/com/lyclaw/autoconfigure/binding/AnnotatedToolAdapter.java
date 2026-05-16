package lyjew.com.lyclaw.autoconfigure.binding;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 将带有 {@code @Tool} 注解的普通 Java 对象（POJO）适配为 {@link lyjew.com.lyclaw.tool.Tool}
 * 接口的适配器类，实现了适配器设计模式。
 *
 * <p><b>适配器模式的作用：</b>在 LyClaw 框架中，工具执行的核心契约是 {@code Tool} 接口，
 * 但开发者通常不会直接实现该接口，而是通过 {@code @Tool} 和 {@code @Param} 注解声明工具行为。
 * 本适配器充当了注解驱动声明与接口契约之间的桥梁——它将反射调用包装在标准的 {@code execute()}
 * 方法中，使得框架的其他组件（如 Pipeline、ToolRegistry）可以无差别地对待注解式工具和
 * 接口实现式工具。</p>
 *
 * <p><b>核心组件与协作关系：</b></p>
 * <ul>
 *   <li><b>target（目标对象）：</b>持有原始的 Spring Bean 实例引用，工具执行时将在此实例上
 *       通过反射调用指定的方法。</li>
 *   <li><b>executeMethod（执行方法）：</b>由 {@link lyjew.com.lyclaw.autoconfigure.processor.ToolAnnotationProcessor}
 *       发现并传递进来的方法对象，代表带有 {@code @Tool} 注解或 {@code @Param} 注解的具体执行入口。</li>
 *   <li><b>bindingDescriptor（参数绑定描述符）：</b>{@link ParameterBindingDescriptor} 实例，
 *       负责解析执行方法的参数列表（包括参数名、类型、是否必填、默认值等元数据），并在执行时
 *       将 LLM 传递的命名参数映射到方法的形式参数上，同时进行必要的类型转换。</li>
 *   <li><b>definition（工具定义）：</b>{@link lyjew.com.lyclaw.model.ToolDefinition} 实例，
 *       包含工具的完整元数据，包括 JSON Schema 格式的参数定义，供 LLM 在函数调用时参考。</li>
 * </ul>
 *
 * <p><b>执行流程：</b>当 LLM 决定调用某个工具时，框架通过 {@code execute(ToolCall, ChatContext)}
 * 方法触发适配器。适配器先将 ToolCall 中的参数映射表转换为标准 Map，然后委托给
 * {@link ParameterBindingDescriptor#bindAndInvoke(Object, Map)} 完成参数绑定和反射调用，
 * 最后将返回值和耗时信息封装为 {@link lyjew.com.lyclaw.tool.ToolExecutionResult} 返回。</p>
 *
 * <p><b>异常处理：</b>如果反射调用过程中发生任何异常（如参数类型不匹配、方法访问权限问题、
 * 业务逻辑抛出异常等），适配器会捕获异常并返回一个标记为失败的 ToolExecutionResult，
 * 其中包含异常信息，确保异常不会中断管道执行。</p>
 */
@Slf4j
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
            log.error("工具执行异常: tool={}, args={}, error={}", name, toolCall.getArguments(),
                    e.getMessage(), e);
            return ToolExecutionResult.builder()
                    .success(false)
                    .error(e.getMessage() != null ? e.getMessage() : e.getClass().getName())
                    .elapsedMs(elapsed)
                    .build();
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }
}
