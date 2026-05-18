package lyjew.com.lyclaw.autoconfigure.config;

import lyjew.com.lyclaw.agent.AgentMessage;
import lyjew.com.lyclaw.agent.communication.AgentCommProtocol;
import lyjew.com.lyclaw.agent.communication.AgentDefinition;
import lyjew.com.lyclaw.annotation.Agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 同 JVM 内 Agent 间通信协议的默认实现。
 *
 * <p>通过直接内存调用在同一个 JVM 内的 Agent 之间传递消息，
 * 适用于单体部署场景。无网络开销，延迟极低。
 *
 * <p>支持 A2A 协议发现——扫描 Spring 容器中的 @Agent Bean。
 * 用户可替换为 RabbitMQ、Kafka、gRPC 等分布式实现。
 */
public class InJvmAgentCommProtocol implements AgentCommProtocol, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(InJvmAgentCommProtocol.class);

    private final Map<String, Consumer<AgentMessage>> receivers = new ConcurrentHashMap<>();
    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Flux<AgentMessage> send(String targetAgentName, AgentMessage message) {
        Consumer<AgentMessage> receiver = receivers.get(targetAgentName);
        if (receiver == null) {
            log.warn("Agent {} 未注册接收器，消息丢弃: {}", targetAgentName, message.getType());
            return Flux.empty();
        }
        try {
            receiver.accept(message);
            AgentMessage ack = new AgentMessage(
                    targetAgentName,
                    message.getFrom(),
                    "response",
                    "ack",
                    Instant.now());
            return Flux.just(ack);
        } catch (Exception e) {
            log.error("Agent {} 消息处理失败: {}", targetAgentName, e.getMessage());
            return Flux.error(e);
        }
    }

    @Override
    public void registerReceiver(String agentName, Consumer<AgentMessage> receiver) {
        receivers.put(agentName, receiver);
        log.info("Agent {} 注册接收器完成", agentName);
    }

    @Override
    public boolean supportsA2A() {
        return applicationContext != null;
    }

    @Override
    public List<AgentDefinition> discoverAgents() {
        if (applicationContext == null) return List.of();
        List<AgentDefinition> agents = new ArrayList<>();

        Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(Agent.class);
        for (Map.Entry<String, Object> entry : agentBeans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> clz = bean.getClass();
            if (clz.getName().contains("$$")) clz = clz.getSuperclass();

            Agent ann = clz.getAnnotation(Agent.class);
            if (ann == null) continue;

            String name = ann.name().isEmpty() ? clz.getSimpleName() : ann.name();
            agents.add(new AgentDefinition(name, ann.description(),
                    "a2a", "in-jvm://" + name));
        }

        log.debug("A2A 发现 {} 个 Agent", agents.size());
        return agents;
    }
}
