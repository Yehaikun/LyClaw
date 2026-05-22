package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 注册与分发中心 —— 对标 OpenClaw 的 PluginHookManager。
 * 按 hook 名称分组注册，分发时按优先级排序。
 */
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    private final Map<String, List<HookEntry>> registry = new ConcurrentHashMap<>();

    /** Convenience constructor that registers all provided hooks. */
    public HookRegistry() {}

    /** Convenience constructor that registers all provided hooks. */
    public HookRegistry(List<AgentHook> hooks) {
        if (hooks != null) {
            for (AgentHook hook : hooks) {
                register(hook, "agent-hook", hook.getOrder());
            }
        }
    }

    /** 注册一个 hook。 */
    public void register(AgentHook hook) {
        register(hook, "agent-hook", 100);
    }

    /** 注册一个 hook 并指定优先级和来源。 */
    public void register(AgentHook hook, String source, int priority) {
        if (hook == null) return;
        String[] hookNames = {
            "beforeRequest", "beforeModel", "afterModel", "wrapToolCall",
            "wrapToolExecutor", "afterResult",
            "beforeModelResolve", "modelCallStarted", "modelCallEnded",
            "llmInput", "llmOutput",
            "beforeAgentStart", "beforeAgentReply", "beforeAgentFinalize",
            "agentEnd", "beforeAgentRun",
            "beforeToolCall", "afterToolCall", "toolResultPersist",
            "sessionStart", "sessionEnd",
            "subagentSpawning", "subagentSpawned", "subagentEnded",
            "beforeCompaction", "afterCompaction",
            "messageReceived", "messageSending", "messageSent",
            "heartbeatPromptContribution"
        };
        for (String name : hookNames) {
            registerNamed(name, hook, source, priority);
        }
    }

    private void registerNamed(String hookName, AgentHook hook, String source, int priority) {
        registry.computeIfAbsent(hookName, k -> new CopyOnWriteArrayList<>())
                .add(new HookEntry(hook, source, priority));
        // Sort by priority descending (higher priority fires first)
        registry.get(hookName).sort((a, b) -> Integer.compare(b.priority, a.priority));
    }

    /** 获取注册到某个 hook 名称的所有 hook。 */
    public List<AgentHook> getHooks(String hookName) {
        List<HookEntry> entries = registry.getOrDefault(hookName, Collections.emptyList());
        List<AgentHook> hooks = new ArrayList<>(entries.size());
        for (HookEntry entry : entries) {
            hooks.add(entry.hook);
        }
        return Collections.unmodifiableList(hooks);
    }

    /** 获取所有已注册的 hook（去重，按优先级降序）。 */
    public List<AgentHook> getAllHooks() {
        Set<AgentHook> seen = new LinkedHashSet<>();
        for (List<HookEntry> entries : registry.values()) {
            for (HookEntry entry : entries) {
                seen.add(entry.hook);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(seen));
    }

    /** 获取所有注册的 hook 名称。 */
    public Set<String> getHookNames() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /** 获取注册的总 hook 数（按名称计数）。 */
    public int getTotalRegistrations() {
        int count = 0;
        for (List<HookEntry> entries : registry.values()) {
            count += entries.size();
        }
        return count;
    }

    /** 清除所有注册。 */
    public void clear() {
        registry.clear();
    }

    // ── 便捷分发方法 ──────────────────────────────────────────

    public void dispatchBeforeRequest(AgentContext ctx) {
        for (AgentHook hook : getHooks("beforeRequest")) {
            try { hook.beforeRequest(ctx); } catch (Exception e) { log.warn("Hook beforeRequest failed", e); }
        }
    }

    public List<Message> dispatchBeforeModel(List<Message> messages, AgentContext ctx) {
        for (AgentHook hook : getHooks("beforeModel")) {
            try { messages = hook.beforeModel(messages, ctx); } catch (Exception e) { log.warn("Hook beforeModel failed", e); }
        }
        return messages;
    }

    public String dispatchAfterModel(String response, AgentContext ctx) {
        for (AgentHook hook : getHooks("afterModel")) {
            try { response = hook.afterModel(response, ctx); } catch (Exception e) { log.warn("Hook afterModel failed", e); }
        }
        return response;
    }

    public ToolCall dispatchWrapToolCall(ToolCall toolCall, AgentContext ctx) {
        for (AgentHook hook : getHooks("wrapToolCall")) {
            try { toolCall = hook.wrapToolCall(toolCall, ctx); } catch (Exception e) { log.warn("Hook wrapToolCall failed", e); }
        }
        return toolCall;
    }

    public ToolExecutor dispatchWrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        for (AgentHook hook : getHooks("wrapToolExecutor")) {
            try { inner = hook.wrapToolExecutor(inner, ctx); } catch (Exception e) { log.warn("Hook wrapToolExecutor failed", e); }
        }
        return inner;
    }

    public String dispatchAfterResult(String result, AgentContext ctx) {
        for (AgentHook hook : getHooks("afterResult")) {
            try { result = hook.afterResult(result, ctx); } catch (Exception e) { log.warn("Hook afterResult failed", e); }
        }
        return result;
    }

    public void dispatchBeforeModelResolve(AgentContext ctx) {
        for (AgentHook hook : getHooks("beforeModelResolve")) {
            try { hook.beforeModelResolve(ctx); } catch (Exception e) { log.warn("Hook beforeModelResolve failed", e); }
        }
    }

    public void dispatchModelCallStarted(AgentContext ctx) {
        for (AgentHook hook : getHooks("modelCallStarted")) {
            try { hook.modelCallStarted(ctx); } catch (Exception e) { log.warn("Hook modelCallStarted failed", e); }
        }
    }

    public void dispatchModelCallEnded(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("modelCallEnded");
        log.debug("HookRegistry.dispatchModelCallEnded: 找到 {} 个 modelCallEnded 钩子", hooks.size());
        for (AgentHook hook : hooks) {
            try { hook.modelCallEnded(ctx); } catch (Exception e) { log.warn("Hook modelCallEnded failed: {}", e.getMessage(), e); }
        }
    }

    public void dispatchBeforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {
        for (AgentHook hook : getHooks("beforeToolCall")) {
            try { hook.beforeToolCall(toolName, toolCallId, args, ctx); } catch (Exception e) { log.warn("Hook beforeToolCall failed", e); }
        }
    }

    public void dispatchAfterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {
        for (AgentHook hook : getHooks("afterToolCall")) {
            try { hook.afterToolCall(toolName, toolCallId, result, ctx); } catch (Exception e) { log.warn("Hook afterToolCall failed", e); }
        }
    }

    public AgentFinalizeResult dispatchBeforeAgentFinalize(AgentContext ctx) {
        for (AgentHook hook : getHooks("beforeAgentFinalize")) {
            try {
                AgentFinalizeResult r = hook.beforeAgentFinalize(ctx);
                if (r != null && !r.isContinue()) return r;
            } catch (Exception e) { log.warn("Hook beforeAgentFinalize failed", e); }
        }
        return AgentFinalizeResult.continue_();
    }

    public void dispatchAgentEnd(AgentContext ctx) {
        for (AgentHook hook : getHooks("agentEnd")) {
            try { hook.agentEnd(ctx); } catch (Exception e) { log.warn("Hook agentEnd failed", e); }
        }
    }

    public void dispatchBeforeAgentRun(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("beforeAgentRun");
        log.debug("HookRegistry.dispatchBeforeAgentRun: 找到 {} 个 beforeAgentRun 钩子", hooks.size());
        for (AgentHook hook : hooks) {
            try { hook.beforeAgentRun(ctx); } catch (Exception e) { log.warn("Hook beforeAgentRun failed: {}", e.getMessage(), e); }
        }
    }

    // ── 内部类 ─────────────────────────────────────────────────

    private static class HookEntry {
        final AgentHook hook;
        final String source;
        final int priority;

        HookEntry(AgentHook hook, String source, int priority) {
            this.hook = hook;
            this.source = source;
            this.priority = priority;
        }
    }
}
