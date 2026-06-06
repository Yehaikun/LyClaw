package lyjew.com.lyclaw.mesh.impl;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.mesh.AgentCallHistory;
import lyjew.com.lyclaw.mesh.AgentHandle;
import lyjew.com.lyclaw.mesh.AgentInstance;
import lyjew.com.lyclaw.mesh.AgentLifecycleListener;
import lyjew.com.lyclaw.mesh.AgentLifecycleState;
import lyjew.com.lyclaw.mesh.AgentMessage;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.mesh.MessageType;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import reactor.core.publisher.Flux;

/**
 * 代理 Agent 实例 —— 包装现有 {@code @Agent} 接口的 JDK 代理，向后兼容。
 *
 * <p>使通过 {@link AgentProxyFactory} 创建的旧 {@code @Agent} 接口代理
 * 能够接入 Agent Mesh，不破坏现有代码。</p>
 *
 * <p>适配器模式：将方法调用适配为消息驱动。</p>
 */
public class ProxyAgentInstance implements AgentInstance {

    private static final Logger log = LoggerFactory.getLogger(ProxyAgentInstance.class);

    private final AgentSpec spec;
    private final AgentHandle handle;
    private final AgentCallHistory callHistory;
    private final Object proxy;           // JDK 代理实例
    private final AgentProxyFactory proxyFactory; // 用于重建代理
    private final Class<?> agentInterface;

    private volatile boolean running;

    public ProxyAgentInstance(AgentSpec spec, Object proxy,
                               AgentProxyFactory proxyFactory,
                               Class<?> agentInterface) {
        this.spec = spec;
        this.handle = new AgentHandle();
        this.callHistory = new AgentCallHistory(spec.getAgentId());
        this.proxy = proxy;
        this.proxyFactory = proxyFactory;
        this.agentInterface = agentInterface;
        this.handle.setState(AgentLifecycleState.PENDING);
    }

    @Override
    public String getAgentId() { return spec.getAgentId(); }

    @Override
    public AgentRef.AgentType getType() { return AgentRef.AgentType.PROXY; }

    @Override
    public AgentSpec getSpec() { return spec; }

    @Override
    public AgentHandle getHandle() { return handle; }

    @Override
    public AgentCallHistory getCallHistory() { return callHistory; }

    @Override
    public CompletableFuture<AgentMessage> send(AgentMessage message) {
        if (!running) {
            return CompletableFuture.completedFuture(
                    AgentMessage.errorTo(message, "Agent not running: " + getAgentId()));
        }

        handle.incrementTotalRequests();
        handle.setLastActiveTime(System.currentTimeMillis());

        try {
            // 将 AgentMessage 转换为方法调用
            String payload = message.getPayload() != null ? message.getPayload() : "";
            String result = invokeProxyMethod(payload);

            AgentMessage response = AgentMessage.responseTo(message, result);
            if (message.getCorrelationId() != null) {
                callHistory.completeCall(message.getCorrelationId(), response);
            }
            return CompletableFuture.completedFuture(response);

        } catch (Exception e) {
            log.error("ProxyAgent {} invocation failed: {}", getAgentId(), e.getMessage());
            handle.incrementErrors();
            return CompletableFuture.completedFuture(
                    AgentMessage.errorTo(message, "ProxyAgent error: " + e.getMessage()));
        }
    }

    @Override
    public Flux<AgentMessage> sendStream(AgentMessage message) {
        if (!running) {
            return Flux.just(AgentMessage.errorTo(message, "Agent not running"));
        }

        try {
            String payload = message.getPayload() != null ? message.getPayload() : "";
            String result = invokeProxyMethod(payload);

            return Flux.just(
                    AgentMessage.builder()
                            .type(MessageType.STREAM)
                            .payload(result)
                            .build(),
                    AgentMessage.builder()
                            .type(MessageType.RESPONSE)
                            .correlationId(message.getCorrelationId())
                            .payload(result)
                            .streamEnd(true)
                            .build()
            );
        } catch (Exception e) {
            return Flux.just(AgentMessage.errorTo(message, e.getMessage()));
        }
    }

    @Override
    public void start() {
        this.running = true;
        handle.setState(AgentLifecycleState.ACTIVE);
        log.info("ProxyAgent started: {} (interface={})", getAgentId(), agentInterface.getSimpleName());
    }

    @Override
    public void stop() {
        this.running = false;
        handle.setState(AgentLifecycleState.STOPPED);
    }

    @Override
    public void destroy() {
        this.running = false;
        handle.setState(AgentLifecycleState.DESTROYED);
    }

    @Override
    public AgentLifecycleState getState() { return handle.getState(); }

    @Override
    public void addLifecycleListener(AgentLifecycleListener listener) {}

    /**
     * 调用 JDK 代理的方法。
     * 默认调用代理实例的 chat/execute 方法。
     */
    protected String invokeProxyMethod(String payload) throws Exception {
        try {
            java.lang.reflect.Method chatMethod = agentInterface.getMethod("chat", String.class);
            Object result = chatMethod.invoke(proxy, payload);
            return result != null ? result.toString() : "";
        } catch (NoSuchMethodException e) {
            // 尝试第一个参数为 String 的方法
            for (java.lang.reflect.Method m : agentInterface.getMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class
                        && m.getReturnType() != void.class) {
                    Object result = m.invoke(proxy, payload);
                    return result != null ? result.toString() : "";
                }
            }
            throw new RuntimeException("No suitable method found on @Agent interface "
                    + agentInterface.getSimpleName());
        }
    }
}
