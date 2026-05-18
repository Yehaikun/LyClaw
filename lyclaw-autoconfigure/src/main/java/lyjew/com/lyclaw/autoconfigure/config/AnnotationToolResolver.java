package lyjew.com.lyclaw.autoconfigure.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.annotation.tool.Tool;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolProvider;
import lyjew.com.lyclaw.tool.ToolProviderRequest;
import lyjew.com.lyclaw.tool.ToolResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 基于注解扫描和 ToolProvider 的默认工具解析器。
 *
 * <p>扫描所有带 @Tool 注解的 Bean 和 ToolProvider 动态工具，
 * 支持根据 AgentContext 动态筛选工具。
 */
public class AnnotationToolResolver implements ToolResolver {

    private static final Logger log = LoggerFactory.getLogger(AnnotationToolResolver.class);

    private final ApplicationContext applicationContext;
    private final List<ToolProvider> toolProviders;
    private final Map<String, lyjew.com.lyclaw.tool.Tool> toolCache = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public AnnotationToolResolver(ApplicationContext applicationContext,
                                   List<ToolProvider> toolProviders) {
        this.applicationContext = applicationContext;
        this.toolProviders = toolProviders != null ? toolProviders : List.of();
        buildCache();
    }

    private void buildCache() {
        Map<String, Object> toolBeans = applicationContext.getBeansWithAnnotation(Tool.class);
        for (Map.Entry<String, Object> entry : toolBeans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> clz = bean.getClass();
            if (clz.getName().contains("$$")) clz = clz.getSuperclass();
            Tool ann = clz.getAnnotation(Tool.class);
            if (ann == null) continue;

            for (Method method : clz.getDeclaredMethods()) {
                String toolName = ann.name().isEmpty() ? method.getName() : ann.name();
                ToolDefinition def = buildDefinition(toolName, ann.description(), method);
                toolCache.put(toolName, new AnnotationTool(toolName, bean, method, def));
            }
        }

        for (ToolProvider provider : toolProviders) {
            ToolProviderRequest req = new ToolProviderRequest(null);
            ToolProvider.ToolProviderResult result = provider.provideTools(req);
            for (ToolDefinition def : result.getDefinitions()) {
                String name = def.getName();
                toolCache.put(name, new ProviderTool(name, provider, def));
            }
        }

        log.info("ToolResolver 初始化完成: {} 个工具", toolCache.size());
    }

    private ToolDefinition buildDefinition(String name, String description, Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter p : method.getParameters()) {
            lyjew.com.lyclaw.annotation.tool.Param paramAnn =
                    p.getAnnotation(lyjew.com.lyclaw.annotation.tool.Param.class);
            String pName = paramAnn != null && !paramAnn.name().isEmpty()
                    ? paramAnn.name() : p.getName();
            String pDesc = paramAnn != null ? paramAnn.description() : "";
            boolean isRequired = paramAnn != null && paramAnn.required();

            Map<String, Object> propSchema = new LinkedHashMap<>();
            propSchema.put("type", mapJavaType(p.getType().getSimpleName()));
            if (!pDesc.isEmpty()) propSchema.put("description", pDesc);
            properties.put(pName, propSchema);

            if (isRequired) required.add(pName);
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (!required.isEmpty()) parameters.put("required", required);

        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    private String mapJavaType(String simpleName) {
        return switch (simpleName.toLowerCase()) {
            case "int", "integer", "long", "short", "byte" -> "integer";
            case "double", "float", "bigdecimal" -> "number";
            case "boolean" -> "boolean";
            case "list", "arraylist", "linkedlist", "set", "collection" -> "array";
            case "map", "hashmap", "linkedhashmap" -> "object";
            default -> "string";
        };
    }

    // ── 动态筛选 ──

    @Override
    public List<ToolDefinition> resolveTools(AgentContext ctx) {
        boolean dynamicFiltering = true;
        if (ctx != null) {
            Object val = ctx.getAttribute("tool.dynamicFiltering");
            if (val instanceof Boolean b) dynamicFiltering = b;
        }

        List<ToolDefinition> allDefs = new ArrayList<>();
        for (lyjew.com.lyclaw.tool.Tool tool : toolCache.values()) {
            ToolDefinition def = tool.getDefinition();
            if (dynamicFiltering && ctx != null) {
                if (!shouldInclude(def, ctx)) continue;
            }
            allDefs.add(def);
        }
        return allDefs;
    }

    protected boolean shouldInclude(ToolDefinition def, AgentContext ctx) {
        if (ctx.getSandboxLevel() != null) {
            boolean isDangerous = def.getName().contains("shell")
                    || def.getName().contains("exec")
                    || def.getName().contains("sudo");
            if (isDangerous && ctx.getSandboxLevel() == SandboxLevel.SANDBOX) {
                return false;
            }
        }
        return true;
    }

    @Override
    public lyjew.com.lyclaw.tool.Tool resolve(String toolName, AgentContext ctx) {
        return toolCache.get(toolName);
    }

    @Override
    public List<String> allToolNames() {
        return new ArrayList<>(toolCache.keySet());
    }

    // ── 内部类 ──

    private record AnnotationTool(String name, Object bean, Method method, ToolDefinition definition)
            implements lyjew.com.lyclaw.tool.Tool {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override public String getName() { return name; }
        @Override public ToolDefinition getDefinition() { return definition; }

        @Override
        public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = MAPPER.readValue(toolCall.getArguments(), Map.class);
                Object[] params = new Object[method.getParameterCount()];
                int i = 0;
                for (Parameter p : method.getParameters()) {
                    lyjew.com.lyclaw.annotation.tool.Param pa =
                            p.getAnnotation(lyjew.com.lyclaw.annotation.tool.Param.class);
                    String pName = pa != null && !pa.name().isEmpty()
                            ? pa.name() : p.getName();
                    params[i++] = args != null ? args.getOrDefault(pName, null) : null;
                }
                Object result = method.invoke(bean, params);
                String text = result != null ? result.toString() : "";
                return ToolExecutionResult.success(text, name);
            } catch (Exception e) {
                return ToolExecutionResult.failure(e.getMessage(), name);
            }
        }
    }

    private record ProviderTool(String name, ToolProvider provider, ToolDefinition definition)
            implements lyjew.com.lyclaw.tool.Tool {
        @Override public String getName() { return name; }
        @Override public ToolDefinition getDefinition() { return definition; }

        @Override
        public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
            try {
                ToolProviderRequest req = context != null && context.getRequest() != null
                        ? new ToolProviderRequest(context.getRequest())
                        : new ToolProviderRequest(null);
                ToolProvider.ToolProviderResult result = provider.provideTools(req);
                ToolExecutor executor = result.getExecutor(name);
                if (executor == null) {
                    return ToolExecutionResult.failure("工具未在提供者中找到: " + name, name);
                }
                String output = executor.execute(name, toolCall.getToolCallId(), toolCall.getArguments());
                return ToolExecutionResult.success(output, name);
            } catch (Exception e) {
                return ToolExecutionResult.failure(e.getMessage(), name);
            }
        }
    }
}
