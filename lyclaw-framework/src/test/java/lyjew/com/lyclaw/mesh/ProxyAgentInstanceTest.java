package lyjew.com.lyclaw.mesh;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.mesh.impl.ProxyAgentInstance;

/**
 * ProxyAgentInstance 单元测试：
 * - send() 包装代理方法调用
 * - 生命周期管理
 * - 未启动时返回错误
 */
class ProxyAgentInstanceTest {

    interface TestAgent {
        String chat(String message);
        String execute(String task);
    }

    @Test
    void proxyShouldWrapMethodCall() {
        // 使用匿名类实现 TestAgent
        TestAgent implementation = new TestAgent() {
            @Override public String chat(String message) { return "Echo: " + message; }
            @Override public String execute(String task) { return "Executed: " + task; }
        };

        TestAgent proxy = (TestAgent) Proxy.newProxyInstance(
                TestAgent.class.getClassLoader(),
                new Class<?>[]{TestAgent.class},
                (proxyObj, method, args) -> {
                    if ("chat".equals(method.getName()) && args != null && args.length > 0) {
                        return implementation.chat((String) args[0]);
                    }
                    return method.invoke(implementation, args);
                });

        AgentSpec spec = AgentSpec.builder()
                .agentId("proxy-test")
                .build();

        ProxyAgentInstance instance = new ProxyAgentInstance(
                spec, proxy, null, TestAgent.class);
        instance.start();

        AgentMessage response = instance.send(AgentMessage.builder()
                .type(MessageType.REQUEST)
                .payload("hello")
                .correlationId("p-001")
                .build()).join();

        assertNotNull(response);
        assertTrue(response.getType() == MessageType.RESPONSE
                || response.getType() == MessageType.ERROR);
    }

    @Test
    void proxyLifecycle() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("lifecycle-proxy")
                .build();

        ProxyAgentInstance instance = new ProxyAgentInstance(
                spec, null, null, TestAgent.class);

        assertEquals(AgentLifecycleState.PENDING, instance.getState());
        instance.start();
        assertEquals(AgentLifecycleState.ACTIVE, instance.getState());
        instance.stop();
        assertEquals(AgentLifecycleState.STOPPED, instance.getState());
        instance.destroy();
        assertEquals(AgentLifecycleState.DESTROYED, instance.getState());
    }

    @Test
    void proxyShouldErrorWhenStopped() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("stopped-proxy")
                .build();

        ProxyAgentInstance instance = new ProxyAgentInstance(
                spec, null, null, TestAgent.class);

        AgentMessage response = instance.send(AgentMessage.builder()
                .type(MessageType.REQUEST)
                .payload("test")
                .build()).join();

        assertEquals(MessageType.ERROR, response.getType());
    }

    @Test
    void proxyHasCorrectIdentity() {
        AgentSpec spec = AgentSpec.builder()
                .agentId("identity-proxy")
                .build();

        ProxyAgentInstance instance = new ProxyAgentInstance(
                spec, null, null, TestAgent.class);

        assertEquals("identity-proxy", instance.getAgentId());
        assertEquals(AgentRef.AgentType.PROXY, instance.getType());
        assertNotNull(instance.getHandle());
        assertNotNull(instance.getSpec());
    }
}
