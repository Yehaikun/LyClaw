package lyjew.com.lyclaw.reflect.impl.hook;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.reflect.topology.ExecutionResult;
import lyjew.com.lyclaw.reflect.topology.TopologyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记忆钩子注册中心 — 管理所有 MemoryHook 实例并统一调度事件。
 *
 * <p>线程安全：使用 CopyOnWriteArrayList 保证注册/注销与事件分发之间的并发安全。
 * 每个钩子的异常被单独捕获，一个钩子失败不会阻止后续钩子执行。
 */
public class MemoryHookRegistry {

    private static final Logger log = LoggerFactory.getLogger(MemoryHookRegistry.class);

    private final List<MemoryHook> hooks = new CopyOnWriteArrayList<>();

    /** 注册钩子（去重） */
    public void register(MemoryHook hook) {
        if (!hooks.contains(hook)) {
            hooks.add(hook);
            log.debug("Hook registered: {}", hook.getClass().getSimpleName());
        }
    }

    /** 注销钩子 */
    public void unregister(MemoryHook hook) {
        hooks.remove(hook);
    }

    /** 获取已注册钩子数 */
    public int size() { return hooks.size(); }

    /** 清空所有钩子（仅用于测试） */
    public void clear() { hooks.clear(); }

    // ── 事件分发 ──

    public void dispatchTopologyStart(ReflectionContext ctx, String topologyName) {
        for (MemoryHook hook : hooks) {
            try { hook.onTopologyStart(ctx, topologyName); }
            catch (Exception e) { log.warn("Hook.onTopologyStart failed: {}", e.getMessage()); }
        }
    }

    public void dispatchTopologyEnd(ReflectionContext ctx, ExecutionResult result) {
        for (MemoryHook hook : hooks) {
            try { hook.onTopologyEnd(ctx, result); }
            catch (Exception e) { log.warn("Hook.onTopologyEnd failed: {}", e.getMessage()); }
        }
    }

    public void dispatchActorBefore(ReflectionContext ctx, String actorName) {
        for (MemoryHook hook : hooks) {
            try { hook.onActorBefore(ctx, actorName); }
            catch (Exception e) { log.warn("Hook.onActorBefore failed: {}", e.getMessage()); }
        }
    }

    public void dispatchActorAfter(ReflectionContext ctx, String actorName, String output) {
        for (MemoryHook hook : hooks) {
            try { hook.onActorAfter(ctx, actorName, output); }
            catch (Exception e) { log.warn("Hook.onActorAfter failed: {}", e.getMessage()); }
        }
    }

    public void dispatchEvaluatorAfter(ReflectionContext ctx, Evaluation evaluation) {
        for (MemoryHook hook : hooks) {
            try { hook.onEvaluatorAfter(ctx, evaluation); }
            catch (Exception e) { log.warn("Hook.onEvaluatorAfter failed: {}", e.getMessage()); }
        }
    }

    public void dispatchRouterAfter(ReflectionContext ctx, RouteDecision decision, int iteration) {
        for (MemoryHook hook : hooks) {
            try { hook.onRouterAfter(ctx, decision, iteration); }
            catch (Exception e) { log.warn("Hook.onRouterAfter failed: {}", e.getMessage()); }
        }
    }

    public void dispatchReflectorAfter(ReflectionContext ctx, String reflection) {
        for (MemoryHook hook : hooks) {
            try { hook.onReflectorAfter(ctx, reflection); }
            catch (Exception e) { log.warn("Hook.onReflectorAfter failed: {}", e.getMessage()); }
        }
    }

    public void dispatchTopologyEvent(TopologyEvent event) {
        for (MemoryHook hook : hooks) {
            try { hook.onTopologyEvent(event); }
            catch (Exception e) { log.warn("Hook.onTopologyEvent failed: {}", e.getMessage()); }
        }
    }
}
