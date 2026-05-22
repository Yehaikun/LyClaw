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
        log.info("📋 [HookRegistry] 注册钩子: {} (source={} priority={})",
                hook.getClass().getSimpleName(), source, priority);
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
        List<AgentHook> hooks = getHooks("beforeRequest");
        if (hooks.isEmpty()) { log.debug("🪝 [beforeRequest] 无注册钩子，跳过"); return; }
        log.info("🪝 [beforeRequest] 派发 {} 个钩子 (按order降序)", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.beforeRequest(ctx); } catch (Exception e) { log.warn("Hook beforeRequest failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
    }

    public List<Message> dispatchBeforeModel(List<Message> messages, AgentContext ctx) {
        List<AgentHook> hooks = getHooks("beforeModel");
        if (hooks.isEmpty()) { log.debug("🪝 [beforeModel] 无注册钩子，跳过"); return messages; }
        log.info("🪝 [beforeModel] 派发 {} 个钩子 | 当前消息数={}", hooks.size(), messages.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { messages = hook.beforeModel(messages, ctx); } catch (Exception e) { log.warn("Hook beforeModel failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
        log.info("  └─ beforeModel完成 | 处理后消息数={}", messages.size());
        return messages;
    }

    public String dispatchAfterModel(String response, AgentContext ctx) {
        List<AgentHook> hooks = getHooks("afterModel");
        if (hooks.isEmpty()) { log.debug("🪝 [afterModel] 无注册钩子，跳过"); return response; }
        log.info("🪝 [afterModel] 派发 {} 个钩子 | 响应长度={}", hooks.size(), response != null ? response.length() : 0);
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { response = hook.afterModel(response, ctx); } catch (Exception e) { log.warn("Hook afterModel failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
        log.info("  └─ afterModel完成 | 处理后响应长度={}", response != null ? response.length() : 0);
        return response;
    }

    public ToolCall dispatchWrapToolCall(ToolCall toolCall, AgentContext ctx) {
        List<AgentHook> hooks = getHooks("wrapToolCall");
        if (hooks.isEmpty()) { log.debug("🪝 [wrapToolCall] 无注册钩子，跳过"); return toolCall; }
        log.info("🪝 [wrapToolCall] 派发 {} 个钩子 | toolName={}", hooks.size(), toolCall != null ? toolCall.getName() : "null");
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { toolCall = hook.wrapToolCall(toolCall, ctx); } catch (Exception e) { log.warn("Hook wrapToolCall failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
        return toolCall;
    }

    public ToolExecutor dispatchWrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        List<AgentHook> hooks = getHooks("wrapToolExecutor");
        if (hooks.isEmpty()) { log.debug("🪝 [wrapToolExecutor] 无注册钩子，跳过"); return inner; }
        log.info("🪝 [wrapToolExecutor] 派发 {} 个钩子", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { inner = hook.wrapToolExecutor(inner, ctx); } catch (Exception e) { log.warn("Hook wrapToolExecutor failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
        return inner;
    }

    public String dispatchAfterResult(String result, AgentContext ctx) {
        List<AgentHook> hooks = getHooks("afterResult");
        if (hooks.isEmpty()) { log.debug("🪝 [afterResult] 无注册钩子，跳过"); return result; }
        log.info("🪝 [afterResult] 派发 {} 个钩子 | 结果长度={}", hooks.size(), result != null ? result.length() : 0);
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { result = hook.afterResult(result, ctx); } catch (Exception e) { log.warn("Hook afterResult failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
        log.info("  └─ afterResult完成 | 处理后结果长度={}", result != null ? result.length() : 0);
        return result;
    }

    public void dispatchBeforeModelResolve(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("beforeModelResolve");
        if (hooks.isEmpty()) { log.debug("🪝 [beforeModelResolve] 无注册钩子，跳过"); return; }
        log.info("🪝 [beforeModelResolve] 派发 {} 个钩子", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.beforeModelResolve(ctx); } catch (Exception e) { log.warn("Hook beforeModelResolve failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
    }

    public void dispatchModelCallStarted(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("modelCallStarted");
        if (hooks.isEmpty()) { log.debug("🪝 [modelCallStarted] 无注册钩子，跳过"); return; }
        log.info("🪝 [modelCallStarted] 派发 {} 个钩子", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.modelCallStarted(ctx); } catch (Exception e) { log.warn("Hook modelCallStarted failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
    }

    public void dispatchModelCallEnded(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("modelCallEnded");
        if (hooks.isEmpty()) { log.debug("🪝 [modelCallEnded] 无注册钩子，跳过"); return; }
        log.info("🪝 [modelCallEnded] 派发 {} 个钩子", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.modelCallEnded(ctx); } catch (Exception e) { log.warn("Hook modelCallEnded failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
    }

    public void dispatchBeforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {
        List<AgentHook> hooks = getHooks("beforeToolCall");
        if (hooks.isEmpty()) { log.debug("🪝 [beforeToolCall] 无注册钩子，跳过"); return; }
        log.info("🪝 [beforeToolCall] 派发 {} 个钩子 | toolName={} toolCallId={}", hooks.size(), toolName, toolCallId);
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.beforeToolCall(toolName, toolCallId, args, ctx); } catch (Exception e) { log.warn("Hook beforeToolCall failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
    }

    public void dispatchAfterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {
        List<AgentHook> hooks = getHooks("afterToolCall");
        if (hooks.isEmpty()) { log.debug("🪝 [afterToolCall] 无注册钩子，跳过"); return; }
        log.info("🪝 [afterToolCall] 派发 {} 个钩子 | toolName={} toolCallId={} resultLen={}",
                hooks.size(), toolName, toolCallId, result != null ? result.length() : 0);
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.afterToolCall(toolName, toolCallId, result, ctx); } catch (Exception e) { log.warn("Hook afterToolCall failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
    }

    public AgentFinalizeResult dispatchBeforeAgentFinalize(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("beforeAgentFinalize");
        if (hooks.isEmpty()) { log.debug("🪝 [beforeAgentFinalize] 无注册钩子，跳过"); return AgentFinalizeResult.continue_(); }
        log.info("🪝 [beforeAgentFinalize] 派发 {} 个钩子 (可中断)", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try {
                AgentFinalizeResult r = hook.beforeAgentFinalize(ctx);
                if (r != null && !r.isContinue()) {
                    log.info("  └─ ⛔ 钩子 {} 中断了finalize流程", hook.getClass().getSimpleName());
                    return r;
                }
            } catch (Exception e) { log.warn("Hook beforeAgentFinalize failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
        return AgentFinalizeResult.continue_();
    }

    public void dispatchAgentEnd(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("agentEnd");
        if (hooks.isEmpty()) { log.debug("🪝 [agentEnd] 无注册钩子，跳过"); return; }
        log.info("🪝 [agentEnd] 派发 {} 个钩子", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.agentEnd(ctx); } catch (Exception e) { log.warn("Hook agentEnd failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
        }
    }

    public void dispatchBeforeAgentRun(AgentContext ctx) {
        List<AgentHook> hooks = getHooks("beforeAgentRun");
        if (hooks.isEmpty()) { log.debug("🪝 [beforeAgentRun] 无注册钩子，跳过"); return; }
        log.info("🪝 [beforeAgentRun] 派发 {} 个钩子", hooks.size());
        for (AgentHook hook : hooks) {
            log.info("  ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
            try { hook.beforeAgentRun(ctx); } catch (Exception e) { log.warn("Hook beforeAgentRun failed: {} | {}", hook.getClass().getSimpleName(), e.getMessage(), e); }
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
