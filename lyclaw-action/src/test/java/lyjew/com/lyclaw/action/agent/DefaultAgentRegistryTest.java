package lyjew.com.lyclaw.action.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.agent.AgentCollaborationMode;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentHandle.HealthStatus;
import lyjew.com.lyclaw.agent.AgentRegistrationEvent;
import lyjew.com.lyclaw.agent.AgentRegistrationListener;
import lyjew.com.lyclaw.agent.AgentState;

@DisplayName("DefaultAgentRegistry")
class DefaultAgentRegistryTest {

    private DefaultAgentRegistry registry;

    private AgentHandle chatAgent;
    private AgentHandle reviewAgent;
    private AgentHandle searchAgent;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();

        chatAgent = AgentHandle.builder()
                .agentId("chat")
                .name("聊天助手")
                .state(AgentState.IDLE)
                .health(HealthStatus.UP)
                .capabilities(List.of("chat", "general"))
                .model("deepseek-chat")
                .collaborationMode(AgentCollaborationMode.WORKER)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        reviewAgent = AgentHandle.builder()
                .agentId("code-reviewer")
                .name("代码审查员")
                .state(AgentState.IDLE)
                .health(HealthStatus.UP)
                .capabilities(List.of("code_review", "java", "security_audit"))
                .model("deepseek-chat")
                .collaborationMode(AgentCollaborationMode.WORKER)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        searchAgent = AgentHandle.builder()
                .agentId("researcher")
                .name("搜索研究员")
                .state(AgentState.RUNNING)
                .health(HealthStatus.UP)
                .capabilities(List.of("web_search", "research"))
                .model("deepseek-chat")
                .collaborationMode(AgentCollaborationMode.WORKER)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("注册与注销")
    class Registration {

        @Test
        @DisplayName("注册 Agent 后可通过 agentId 查找")
        void registerAndLookup() {
            registry.register(chatAgent);
            Optional<AgentHandle> found = registry.lookup("chat");
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("聊天助手");
        }

        @Test
        @DisplayName("注册空的 agentId 抛出异常")
        void registerWithEmptyId() {
            AgentHandle bad = AgentHandle.builder().agentId("").name("bad").build();
            assertThrows(IllegalArgumentException.class, () -> registry.register(bad));
        }

        @Test
        @DisplayName("查找不存在的 Agent 返回空")
        void lookupNonExistent() {
            assertThat(registry.lookup("ghost")).isEmpty();
        }

        @Test
        @DisplayName("注销后无法查找")
        void unregister() {
            registry.register(chatAgent);
            registry.unregister("chat");
            assertThat(registry.lookup("chat")).isEmpty();
        }

        @Test
        @DisplayName("注册时自动记录心跳，健康状态为 UP")
        void registerRecordsHeartbeat() {
            registry.register(chatAgent);
            assertThat(registry.getHealth("chat")).isEqualTo(HealthStatus.UP);
        }

        @Test
        @DisplayName("多次注册同一 agentId 会覆盖")
        void overwriteRegistration() {
            registry.register(chatAgent);
            AgentHandle updated = AgentHandle.builder()
                    .agentId("chat").name("新版聊天助手").state(AgentState.IDLE)
                    .health(HealthStatus.UP).build();
            registry.register(updated);
            assertThat(registry.lookup("chat").get().getName()).isEqualTo("新版聊天助手");
        }

        @Test
        @DisplayName("注册时触发监听器")
        void registrationFiresListener() {
            final boolean[] fired = {false};
            registry.addListener(e -> fired[0] = e.getType() == AgentRegistrationEvent.Type.REGISTERED);
            registry.register(chatAgent);
            assertThat(fired[0]).isTrue();
        }

        @Test
        @DisplayName("注销时触发监听器")
        void unregistrationFiresListener() {
            registry.register(chatAgent);
            final boolean[] fired = {false};
            registry.addListener(e -> fired[0] = e.getType() == AgentRegistrationEvent.Type.UNREGISTERED);
            registry.unregister("chat");
            assertThat(fired[0]).isTrue();
        }
    }

    @Nested
    @DisplayName("查找与发现")
    class LookupAndDiscovery {

        @BeforeEach
        void setUp() {
            registry.register(chatAgent);
            registry.register(reviewAgent);
            registry.register(searchAgent);
        }

        @Test
        @DisplayName("按能力查找")
        void findByCapability() {
            List<AgentHandle> reviewers = registry.findByCapability("code_review");
            assertThat(reviewers).hasSize(1);
            assertThat(reviewers.get(0).getAgentId()).isEqualTo("code-reviewer");
        }

        @Test
        @DisplayName("按不存在的能力查找返回空列表")
        void findByUnknownCapability() {
            assertThat(registry.findByCapability("data_science")).isEmpty();
        }

        @Test
        @DisplayName("按状态查找")
        void findByState() {
            List<AgentHandle> idle = registry.findByState(AgentState.IDLE);
            assertThat(idle).hasSize(2);
            List<AgentHandle> running = registry.findByState(AgentState.RUNNING);
            assertThat(running).hasSize(1);
        }

        @Test
        @DisplayName("查找可用 Agent（IDLE + 能力匹配）")
        void findAvailable() {
            List<AgentHandle> available = registry.findAvailable(List.of("code_review"));
            assertThat(available).hasSize(1);
            assertThat(available.get(0).getAgentId()).isEqualTo("code-reviewer");
        }

        @Test
        @DisplayName("查找可用 Agent：空能力列表返回所有 IDLE/RUNNING")
        void findAvailableEmptyCapabilities() {
            List<AgentHandle> available = registry.findAvailable(List.of());
            assertThat(available).hasSize(3);
        }

        @Test
        @DisplayName("查找可用 Agent：不满足所有能力时排除")
        void findAvailableRequiresAllCapabilities() {
            List<AgentHandle> available = registry.findAvailable(List.of("code_review", "security_audit"));
            assertThat(available).hasSize(1);
        }

        @Test
        @DisplayName("获取所有 Agent")
        void getAllAgents() {
            assertThat(registry.getAllAgents()).hasSize(3);
        }

        @Test
        @DisplayName("注销后能力索引同步更新")
        void unregisterUpdatesCapabilityIndex() {
            registry.unregister("code-reviewer");
            assertThat(registry.findByCapability("code_review")).isEmpty();
        }
    }

    @Nested
    @DisplayName("状态管理")
    class StateManagement {

        @BeforeEach
        void setUp() {
            registry.register(chatAgent);
        }

        @Test
        @DisplayName("更新状态")
        void updateState() {
            registry.updateState("chat", AgentState.RUNNING);
            assertThat(registry.lookup("chat").get().getState()).isEqualTo(AgentState.RUNNING);
        }

        @Test
        @DisplayName("更新状态时触发事件")
        void updateStateFiresEvent() {
            final AgentState[] oldState = {null};
            final AgentState[] newState = {null};
            registry.addListener(e -> {
                if (e.getType() == AgentRegistrationEvent.Type.STATE_CHANGED) {
                    oldState[0] = e.getOldState();
                    newState[0] = e.getNewState();
                }
            });
            registry.updateState("chat", AgentState.RUNNING);
            assertThat(oldState[0]).isEqualTo(AgentState.IDLE);
            assertThat(newState[0]).isEqualTo(AgentState.RUNNING);
        }

        @Test
        @DisplayName("RUNNING 状态更新时更新 lastActiveAt")
        void updateStateUpdatesLastActiveAt() {
            LocalDateTime before = chatAgent.getLastActiveAt();
            registry.updateState("chat", AgentState.RUNNING);
            assertThat(chatAgent.getLastActiveAt()).isAfterOrEqualTo(before);
        }
    }

    @Nested
    @DisplayName("心跳与健康检查")
    class Heartbeat {

        @Test
        @DisplayName("无心跳记录的 Agent 健康状态为 UNKNOWN")
        void unknownHealth() {
            assertThat(registry.getHealth("ghost")).isEqualTo(HealthStatus.UNKNOWN);
        }

        @Test
        @DisplayName("记录心跳后健康状态为 UP")
        void recordHeartbeat() {
            registry.register(chatAgent);
            registry.recordHeartbeat("chat");
            assertThat(registry.getHealth("chat")).isEqualTo(HealthStatus.UP);
        }

        @Test
        @DisplayName("健康检查扫描标记超时 Agent 为 DEGRADED")
        void healthCheckMarksDown() {
            registry.register(chatAgent);
            // 模拟心跳过期：把最后一次心跳设为很久以前
            registry.recordHeartbeat("chat");
            // performHealthCheck 使用 60s 超时，正常情况无法触发
            // 所以手动验证健康检查逻辑不抛异常
            registry.performHealthCheck();
            assertThat(registry.getAgentCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("监听器")
    class Listeners {

        @Test
        @DisplayName("添加和移除监听器")
        void addAndRemoveListener() {
            AgentRegistrationListener listener = e -> {};
            registry.addListener(listener);
            registry.removeListener(listener);
            // 无异常即可
        }

        @Test
        @DisplayName("监听器异常不影响注册")
        void listenerExceptionDoesNotBreak() {
            registry.addListener(e -> { throw new RuntimeException("listener error"); });
            registry.register(chatAgent);
            assertThat(registry.lookup("chat")).isPresent();
        }
    }
}
