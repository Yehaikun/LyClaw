package lyjew.com.lyclaw.mesh.impl;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.mesh.AgentHandle;
import lyjew.com.lyclaw.mesh.AgentInstance;
import lyjew.com.lyclaw.mesh.AgentLifecycleEvent;
import lyjew.com.lyclaw.mesh.AgentLifecycleListener;
import lyjew.com.lyclaw.mesh.AgentLifecycleState;
import lyjew.com.lyclaw.mesh.AgentFactory;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.mesh.AgentMeshListener;
import lyjew.com.lyclaw.mesh.AgentMeshMetrics;
import lyjew.com.lyclaw.mesh.AgentSnapshot;
import lyjew.com.lyclaw.mesh.AgentMessage;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.DelayedResult;
import lyjew.com.lyclaw.mesh.MessageType;
import lyjew.com.lyclaw.mesh.SupervisionStrategy;
import reactor.core.publisher.Flux;

/**
 * Agent Mesh 默认实现 —— InProcess 传输 + ConcurrentHashMap 注册表。
 *
 * <p>同 JVM 内通信，所有消息通过 ConcurrentHashMap + CompletableFuture 路由。
 * 支持同步、异步、流式三种消息模式。</p>
 *
 * <p>架构：
 * <ul>
 *   <li>AgentRegistry：agentId → AgentRef + AgentInstance</li>
 *   <li>CapabilityIndex：capability → Set&lt;agentId&gt;（快速能力查找）</li>
 *   <li>EventBus：CopyOnWriteArrayList 存储监听器</li>
 *   <li>PendingFutures：correlationId → CompletableFuture（请求-响应匹配）</li>
 *   <li>DelayedResults：agentId → correlationId → AgentMessage（延迟结果队列）</li>
 * </ul>
 */
public class DefaultAgentMesh implements AgentMesh {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentMesh.class);

    // ── 注册表 ──
    private final ConcurrentHashMap<String, AgentRef> refs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentInstance> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentHandle> handles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentSpec> specs = new ConcurrentHashMap<>();

    // ── 能力索引 ──
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> capabilityIndex = new ConcurrentHashMap<>();

    // ── 事件总线 ──
    private final CopyOnWriteArrayList<AgentMeshListener> listeners = new CopyOnWriteArrayList<>();

    // ── 待处理 Future（correlationId → CompletableFuture） ──
    private final ConcurrentHashMap<String, CompletableFuture<AgentMessage>> pendingFutures = new ConcurrentHashMap<>();

    // ── 流式订阅（correlationId → Consumer） ──
    private final ConcurrentHashMap<String, Consumer<AgentMessage>> streamSubscribers = new ConcurrentHashMap<>();

    // ── 延迟结果队列（agentId → correlationId → DelayedResult） ──
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, DelayedResult>> delayedResults = new ConcurrentHashMap<>();

    // ── 生命周期监听器 ──
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<AgentLifecycleListener>> lifecycleListeners = new ConcurrentHashMap<>();

    // ── Agent 工厂 ──
    private final AgentFactory agentFactory;

    // ── 指标收集 ──
    private final AgentMeshMetrics metrics;

    public DefaultAgentMesh() {
        this.agentFactory = new DefaultAgentFactory(this);
        this.metrics = new DefaultAgentMeshMetrics();
    }

    public DefaultAgentMesh(AgentFactory agentFactory) {
        this.agentFactory = agentFactory != null ? agentFactory : new DefaultAgentFactory(this);
        this.metrics = new DefaultAgentMeshMetrics();
    }

    public DefaultAgentMesh(AgentFactory agentFactory, AgentMeshMetrics metrics) {
        this.agentFactory = agentFactory != null ? agentFactory : new DefaultAgentFactory(this);
        this.metrics = metrics != null ? metrics : new DefaultAgentMeshMetrics();
    }

    // ════════════════════════════════════════════════════════════
    // Agent 注册
    // ════════════════════════════════════════════════════════════

    @Override
    public AgentRef register(AgentSpec spec) {
        if (refs.containsKey(spec.getAgentId())) {
            throw new IllegalStateException("Agent already registered: " + spec.getAgentId());
        }

        // 1. 创建 AgentRef
        AgentRef ref = spec.toRef();
        refs.put(ref.getAgentId(), ref);

        // 2. 创建 AgentInstance
        AgentInstance instance = agentFactory.create(spec);
        instances.put(ref.getAgentId(), instance);

        // 3. 创建 AgentHandle
        AgentHandle handle = new AgentHandle();
        handle.setState(AgentLifecycleState.PENDING);
        handles.put(ref.getAgentId(), handle);

        // 4. 存储 Spec
        specs.put(ref.getAgentId(), spec);

        // 5. 注册能力索引
        indexCapabilities(ref);

        // 6. 启动
        instance.start();
        updateState(ref.getAgentId(), AgentLifecycleState.ACTIVE, "registered");

        log.info("Agent registered: {} [{}] caps={}", ref.getAgentId(), ref.getType(), ref.getCapabilities());
        notifyListeners(AgentMeshListener.MeshEventType.AGENT_REGISTERED,
                ref.getAgentId(), "Agent registered: " + ref.getAgentId());
        return ref;
    }

    @Override
    public AgentRef addInstance(AgentInstance instance) {
        String agentId = instance.getAgentId();
        if (refs.containsKey(agentId)) {
            log.warn("Agent already registered, updating: {}", agentId);
        }
        AgentRef ref = instance.getSpec().toRef();
        refs.put(agentId, ref);
        instances.put(agentId, instance);
        AgentHandle handle = instance.getHandle();
        handles.put(agentId, handle);
        indexCapabilities(ref);
        notifyListeners(AgentMeshListener.MeshEventType.AGENT_REGISTERED,
                agentId, "Agent instance added: " + agentId);
        return ref;
    }

    @Override
    public void unregister(String agentId) {
        AgentInstance instance = instances.remove(agentId);
        if (instance != null) {
            instance.destroy();
        }
        AgentRef ref = refs.remove(agentId);
        if (ref != null) {
            deindexCapabilities(ref);
        }
        handles.remove(agentId);
        lifecycleListeners.remove(agentId);
        notifyListeners(AgentMeshListener.MeshEventType.AGENT_UNREGISTERED,
                agentId, "Agent unregistered: " + agentId);
        log.info("Agent unregistered: {}", agentId);
    }

    // ════════════════════════════════════════════════════════════
    // Agent 查找
    // ════════════════════════════════════════════════════════════

    @Override
    public Optional<AgentRef> lookup(String agentId) {
        return Optional.ofNullable(refs.get(agentId));
    }

    @Override
    public Optional<AgentInstance> getInstance(String agentId) {
        return Optional.ofNullable(instances.get(agentId));
    }

    @Override
    public List<AgentRef> findByCapability(String capability) {
        CopyOnWriteArrayList<String> ids = capabilityIndex.get(capability);
        if (ids == null) return List.of();
        return ids.stream()
                .map(refs::get)
                .filter(r -> r != null)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentRef> getAllAgents() {
        return List.copyOf(refs.values());
    }

    // ════════════════════════════════════════════════════════════
    // 消息传递
    // ════════════════════════════════════════════════════════════

    @Override
    public CompletableFuture<AgentMessage> send(AgentMessage message) {
        if (message.isExpired()) {
            return CompletableFuture.completedFuture(
                    AgentMessage.errorTo(message, "Message expired"));
        }

        // 1. 路由：找到目标 Agent
        AgentInstance target = resolveTarget(message);
        if (target == null) {
            return CompletableFuture.completedFuture(
                    AgentMessage.errorTo(message, "No agent found for: "
                            + (message.getTo() != null ? message.getTo() : message.getCapability())));
        }

        // 2. 填充消息路由信息
        AgentMessage routed = enrichMessage(message, target.getAgentId());

        // 3. 注册待处理 Future（用于响应匹配）
        CompletableFuture<AgentMessage> future = new CompletableFuture<>();
        if (routed.getCorrelationId() != null) {
            pendingFutures.put(routed.getCorrelationId(), future);
            // 超时自动完成
            if (routed.getTtlMs() > 0) {
                CompletableFuture.delayedExecutor(routed.getTtlMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            CompletableFuture<AgentMessage> f = pendingFutures.remove(routed.getCorrelationId());
                            if (f != null && !f.isDone()) {
                                f.complete(AgentMessage.errorTo(routed, "Timeout after " + routed.getTtlMs() + "ms"));
                            }
                        });
            }
        }

        // 4. 发送到目标 Agent
        log.debug("Mesh send: {} → {} (cid={})", routed.getFrom(), routed.getTo(), routed.getCorrelationId());
        notifyListeners(AgentMeshListener.MeshEventType.MESSAGE_SENT,
                target.getAgentId(), "Message sent: " + routed.getType());

        AgentHandle handle = handles.get(target.getAgentId());
        if (handle != null) {
            handle.incrementRequestCount();
            handle.setLastActiveTime(System.currentTimeMillis());
        }

        target.send(routed)
                .thenAccept(response -> {
                    handleResponse(target.getAgentId(), routed, response);
                    if (handle != null) handle.decrementRequestCount();
                })
                .exceptionally(error -> {
                    if (handle != null) handle.decrementRequestCount();
                    CompletableFuture<AgentMessage> f = pendingFutures.remove(routed.getCorrelationId());
                    if (f != null && !f.isDone()) {
                        f.complete(AgentMessage.errorTo(routed, "Agent error: " + error.getMessage()));
                    }
                    return null;
                });

        return future;
    }

    @Override
    public Flux<AgentMessage> sendStream(AgentMessage message) {
        AgentInstance target = resolveTarget(message);
        if (target == null) {
            return Flux.just(AgentMessage.errorTo(message, "No agent found"));
        }
        AgentMessage routed = enrichMessage(message, target.getAgentId());

        return Flux.defer(() -> {
            Flux<AgentMessage> stream = target.sendStream(routed);

            // 注册流式订阅者（用于延迟结果匹配）
            if (routed.getCorrelationId() != null) {
                streamSubscribers.put(routed.getCorrelationId(), msg -> {});
            }

            return stream
                    .doOnNext(response -> {
                        // RESPONSE 或 ERROR 标记流结束
                        if (response.getType() == MessageType.RESPONSE
                                || response.getType() == MessageType.ERROR) {
                            streamSubscribers.remove(routed.getCorrelationId());
                        }
                    })
                    .doFinally(sig -> streamSubscribers.remove(routed.getCorrelationId()));
        });
    }

    @Override
    public void publish(AgentMessage event) {
        for (AgentInstance instance : instances.values()) {
            try {
                instance.send(event);
            } catch (Exception e) {
                log.warn("Failed to publish event to {}: {}", instance.getAgentId(), e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 延迟结果
    // ════════════════════════════════════════════════════════════

    @Override
    public Optional<DelayedResult> pollDelayedResult(String agentId, String correlationId) {
        ConcurrentHashMap<String, DelayedResult> queue = delayedResults.get(agentId);
        if (queue == null) return Optional.empty();
        DelayedResult result = queue.remove(correlationId);
        if (result != null && result.isExpired()) return Optional.empty();
        return Optional.ofNullable(result);
    }

    @Override
    public void storeDelayedResult(String agentId, String correlationId, AgentMessage result) {
        delayedResults.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>())
                .put(correlationId, new DelayedResult(agentId, correlationId, result, 300_000));
    }

    // ════════════════════════════════════════════════════════════
    // 生命周期管理
    // ════════════════════════════════════════════════════════════

    @Override
    public void startAgent(String agentId) {
        getInstance(agentId).ifPresent(instance -> {
            instance.start();
            updateState(agentId, AgentLifecycleState.ACTIVE, "started");
        });
    }

    @Override
    public void stopAgent(String agentId) {
        getInstance(agentId).ifPresent(instance -> {
            updateState(agentId, AgentLifecycleState.STOPPING, "stopping");
            instance.stop();
            updateState(agentId, AgentLifecycleState.STOPPED, "stopped");
        });
    }

    @Override
    public void updateState(String agentId, AgentLifecycleState newState) {
        updateState(agentId, newState, null);
    }

    private void updateState(String agentId, AgentLifecycleState newState, String reason) {
        AgentHandle handle = handles.get(agentId);
        if (handle == null) return;
        AgentLifecycleState oldState = handle.getState();
        handle.setState(newState);

        // 发布生命周期事件
        AgentLifecycleEvent event = AgentLifecycleEvent.of(agentId, oldState, newState, reason);
        CopyOnWriteArrayList<AgentLifecycleListener> agentListeners = lifecycleListeners.get(agentId);
        if (agentListeners != null) {
            for (AgentLifecycleListener listener : agentListeners) {
                try { listener.onLifecycleEvent(event); } catch (Exception e) { log.warn("Lifecycle listener error", e); }
            }
        }
        notifyListeners(AgentMeshListener.MeshEventType.AGENT_LIFECYCLE_CHANGED,
                agentId, oldState + " → " + newState);

        // Supervision: 当 Agent 进入 FAILED 状态时，根据策略自动恢复
        if (newState == AgentLifecycleState.FAILED) {
            AgentSpec spec = specs.get(agentId);
            if (spec != null) {
                applySupervision(spec, agentId, reason);
            }
        }
    }

    /**
     * 应用错误恢复策略。
     */
    private void applySupervision(AgentSpec spec, String agentId, String reason) {
        SupervisionStrategy strategy = spec.getSupervisionStrategy() != null
                ? spec.getSupervisionStrategy() : SupervisionStrategy.RESTART;
        log.warn("[Supervision] Agent {} failed: {} → strategy={}", agentId, reason, strategy);
        switch (strategy) {
            case RESTART -> {
                int retries = spec.getMaxRetries() > 0 ? spec.getMaxRetries() : 3;
                AgentHandle handle = handles.get(agentId);
                int currentRetries = 0;
                if (handle != null && handle.getLastError() != null) {
                    // 从错误计数估算重试次数
                    currentRetries = handle.getTotalErrors();
                }
                if (currentRetries < retries) {
                    log.info("[Supervision] Restarting agent: {} (retry {}/{})", agentId, currentRetries + 1, retries);
                    startAgent(agentId);
                } else {
                    log.warn("[Supervision] Max retries ({}) reached for agent: {}", retries, agentId);
                }
            }
            case ESCALATE -> {
                notifyListeners(AgentMeshListener.MeshEventType.MESSAGE_ERROR,
                        agentId, "Agent failed, escalation required: " + reason);
            }
            case IGNORE -> {
                log.info("[Supervision] Ignoring agent failure: {}", agentId);
            }
            case STOP -> {
                stopAgent(agentId);
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 事件监听
    // ════════════════════════════════════════════════════════════

    @Override
    public void addListener(AgentMeshListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(AgentMeshListener listener) {
        listeners.remove(listener);
    }

    // ════════════════════════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════
    // 可观测性
    // ════════════════════════════════════════════════════════════

    /** 获取指标收集器 */
    public AgentMeshMetrics getMetrics() { return metrics; }

    /** 获取指定 Agent 的快照 */
    public AgentSnapshot snapshot(String agentId) {
        AgentInstance instance = instances.get(agentId);
        if (instance == null) return null;
        return AgentSnapshot.from(instance);
    }

    /** 获取所有 Agent 的快照 */
    public java.util.List<AgentSnapshot> snapshotAll() {
        return instances.values().stream()
                .map(AgentSnapshot::from)
                .toList();
    }

    private void recordMetrics(String agentId, boolean success, long durationMs) {
        if (metrics != null) {
            metrics.recordCall(agentId, success, durationMs);
            AgentHandle handle = handles.get(agentId);
            if (handle != null) {
                metrics.recordActiveRequests(agentId, handle.getActiveRequestCount());
            }
        }
    }

    private AgentInstance resolveTarget(AgentMessage message) {
        // 按 to 字段直接路由
        if (message.getTo() != null && !message.getTo().isEmpty()) {
            if ("*".equals(message.getTo())) {
                return null; // 广播不返回单个实例
            }
            AgentInstance instance = instances.get(message.getTo());
            if (instance != null) return instance;
            // 尝试按 group 查找
            if (message.getTo().startsWith("group:")) {
                String groupKey = message.getTo().substring(6);
                return instances.values().stream()
                        .filter(i -> i.getSpec().getConfig() != null
                                && groupKey.equals(i.getSpec().getConfig().get("group")))
                        .findFirst().orElse(null);
            }
            return null;
        }

        // 按 capability 字段路由（选第一个）
        if (message.getCapability() != null && !message.getCapability().isEmpty()) {
            CopyOnWriteArrayList<String> ids = capabilityIndex.get(message.getCapability());
            if (ids != null && !ids.isEmpty()) {
                String agentId = ids.get(0);
                return instances.get(agentId);
            }
        }
        return null;
    }

    private AgentMessage enrichMessage(AgentMessage original, String targetAgentId) {
        AgentMessage.Builder builder = AgentMessage.builder()
                .type(original.getType())
                .to(targetAgentId)
                .from(original.getFrom())
                .capability(original.getCapability())
                .correlationId(original.getCorrelationId())
                .traceId(original.getTraceId())
                .parentSpanId(original.getParentSpanId())
                .payload(original.getPayload())
                .metadata(original.getMetadata())
                .ttlMs(original.getTtlMs())
                .priority(original.getPriority())
                .streamSeq(original.getStreamSeq())
                .streamEnd(original.getStreamEnd());

        // 如果 from 为空，设置为 mesh
        if (original.getFrom() == null) {
            builder.from("mesh");
        }
        return builder.build();
    }

    private void handleResponse(String agentId, AgentMessage request, AgentMessage response) {
        String correlationId = request.getCorrelationId();

        // 1. 尝试匹配 pending Future
        if (correlationId != null) {
            CompletableFuture<AgentMessage> future = pendingFutures.remove(correlationId);
            if (future != null && !future.isDone()) {
                future.complete(response);
                notifyListeners(AgentMeshListener.MeshEventType.MESSAGE_DELIVERED,
                        agentId, "Response delivered for cid=" + correlationId);
                return;
            }
        }

        // 2. 尝试匹配 stream subscriber
        if (correlationId != null && streamSubscribers.containsKey(correlationId)) {
            // stream 已经通过 Flux 推送，这里只是标记完成
            streamSubscribers.remove(correlationId);
            return;
        }

        // 3. 放入延迟结果队列
        if (correlationId != null && request.getFrom() != null) {
            storeDelayedResult(request.getFrom(), correlationId, response);
            log.debug("Stored delayed result: agentId={} cid={}", request.getFrom(), correlationId);
        }
    }

    private void indexCapabilities(AgentRef ref) {
        for (String cap : ref.getCapabilities()) {
            capabilityIndex.computeIfAbsent(cap, k -> new CopyOnWriteArrayList<>()).add(ref.getAgentId());
        }
    }

    private void deindexCapabilities(AgentRef ref) {
        for (String cap : ref.getCapabilities()) {
            CopyOnWriteArrayList<String> ids = capabilityIndex.get(cap);
            if (ids != null) {
                ids.remove(ref.getAgentId());
                if (ids.isEmpty()) {
                    capabilityIndex.remove(cap);
                }
            }
        }
    }

    private void notifyListeners(AgentMeshListener.MeshEventType type, String agentId, String message) {
        AgentMeshListener.MeshEvent event = AgentMeshListener.MeshEvent.of(type, agentId, message);
        for (AgentMeshListener listener : listeners) {
            try { listener.onMeshEvent(event); } catch (Exception e) { log.warn("Mesh listener error", e); }
        }
    }
}
