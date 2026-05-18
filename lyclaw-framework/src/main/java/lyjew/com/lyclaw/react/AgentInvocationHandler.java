package lyjew.com.lyclaw.react;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.annotation.agent.V;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 接口的动态代理 InvocationHandler。
 *
 * <p>每次调用 Agent 接口方法时，通过 AgentHook 链执行扩展逻辑，
 * 自动构建 ChatRequest、委托 ReActEngine 执行 ReAct 循环，并将结果解析为方法声明的返回类型。</p>
 *
 * <h3>Hook 执行顺序</h3>
 * <ol>
 *   <li>AgentHook#beforeRequest — 按 order 升序（安全审核、内容过滤）</li>
 *   <li>AgentHook#wrapToolExecutor — 按 order 升序装饰（沙箱、审批）</li>
 *   <li>ReActEngine 执行</li>
 *   <li>AgentHook#afterResult — 按 order 降序（校验、日志）</li>
 * </ol>
 */
public class AgentInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentInvocationHandler.class);

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final String defaultSystemPrompt;
    private final String modelOverride;
    private final String providerOverride;
    private final List<AgentHook> hooks;

    public AgentInvocationHandler(ChatFacade chatFacade, ReActEngine reActEngine,
                                   ToolRegistry toolRegistry, String defaultSystemPrompt,
                                   String modelOverride, String providerOverride,
                                   List<AgentHook> hooks) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.modelOverride = modelOverride;
        this.providerOverride = providerOverride;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        String systemPrompt = resolveSystemMessage(method, args);
        String userMessage = resolveUserMessage(method, args);

        ChatRequest request = buildChatRequest(method, userMessage, systemPrompt);
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString().substring(0, 8);

        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args);
        ctx.setChatRequest(request);

        // 1. beforeRequest hooks (按 order 排序)
        List<AgentHook> sorted = new ArrayList<>(hooks);
        sorted.sort(Comparator.comparingInt(AgentHook::getOrder));
        for (AgentHook hook : sorted) {
            hook.beforeRequest(ctx);
        }

        // 如果 hook 修改了 userMessage 或 systemPrompt，重新构建 request
        if (!userMessage.equals(ctx.getUserMessage())
                || !Objects.equals(systemPrompt, ctx.getSystemPrompt())) {
            request = buildChatRequest(method, ctx.getUserMessage(), ctx.getSystemPrompt());
            ctx.setChatRequest(request);
        }

        // 2. 构建 ToolExecutor（通过 wrapToolExecutor 装饰链）
        ToolExecutor toolExecutor = buildToolExecutor(request);
        for (AgentHook hook : sorted) {
            toolExecutor = hook.wrapToolExecutor(toolExecutor, ctx);
        }

        // 3. 执行 ReAct 循环
        Class<?> returnType = method.getReturnType();
        String result;

        if (returnType == Flux.class) {
            return reActEngine.executeStream(chatFacade, request, toolExecutor)
                    .mapNotNull(event -> "message".equals(event.event()) ? event.data() : null);
        }

        result = reActEngine.execute(chatFacade, request, toolExecutor);

        // 4. afterResult hooks (按 order 降序)
        for (int i = sorted.size() - 1; i >= 0; i--) {
            result = sorted.get(i).afterResult(result, ctx);
        }

        if (returnType == Mono.class) {
            return Mono.justOrEmpty(result);
        }

        if (returnType == void.class || returnType == Void.class) {
            return null;
        }

        return result;
    }

    private String resolveSystemMessage(Method method, Object[] args) {
        SystemMessage sm = method.getAnnotation(SystemMessage.class);
        if (sm != null && !sm.value().isEmpty()) {
            return substituteTemplates(sm.value(), method, args);
        }
        return defaultSystemPrompt;
    }

    private String resolveUserMessage(Method method, Object[] args) {
        UserMessage methodUM = method.getAnnotation(UserMessage.class);
        if (methodUM != null && !methodUM.value().isEmpty()) {
            return substituteTemplates(methodUM.value(), method, args);
        }

        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(UserMessage.class)) {
                Object val = (args != null && i < args.length) ? args[i] : null;
                return val != null ? val.toString() : "";
            }
        }

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof String s) return s;
            }
            if (args.length > 0 && args[0] != null) return args[0].toString();
        }
        return "";
    }

    private String substituteTemplates(String template, Method method, Object[] args) {
        String result = template;
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            V v = params[i].getAnnotation(V.class);
            if (v != null) {
                String replacement = (args != null && i < args.length && args[i] != null)
                        ? args[i].toString() : "";
                result = result.replace("{{" + v.value() + "}}", replacement);
            }
        }
        return result;
    }

    private ChatRequest buildChatRequest(Method method, String userMessage, String systemPrompt) {
        ChatRequest.ChatRequestBuilder builder = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user(userMessage))))
                .toolChoice("auto");

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.systemPrompt(systemPrompt);
        }

        if (modelOverride != null && !modelOverride.isEmpty()) {
            builder.model(modelOverride);
        }

        Class<?> returnType = method.getReturnType();
        if (returnType == Flux.class) {
            builder.stream(true);
        }

        ChatRequest request = builder.build();
        List<ToolDefinition> tools = resolveToolDefinitions(request);
        request.setTools(tools);

        return request;
    }

    private List<ToolDefinition> resolveToolDefinitions(ChatRequest request) {
        return toolRegistry.getAllDefinitions(request);
    }

    private ToolExecutor buildToolExecutor(ChatRequest request) {
        return (toolName, toolCallId, argumentsJson) -> {
            try {
                ToolCall toolCall = ToolCall.builder()
                        .toolCallId(toolCallId)
                        .name(toolName)
                        .arguments(argumentsJson)
                        .build();
                ToolExecutionResult result = toolRegistry.execute(toolCall, null);
                if (!result.isSuccess()) {
                    result = toolRegistry.executeByName(toolName, toolCallId, argumentsJson, request);
                }
                if (result.isSuccess()) {
                    return result.getResult() != null ? result.getResult() : "";
                }
                return "Error: " + (result.getError() != null ? result.getError() : "unknown");
            } catch (Exception e) {
                log.error("Tool execution failed: tool={} toolCallId={}", toolName, toolCallId, e);
                return "Error: " + e.getMessage();
            }
        };
    }

    }
