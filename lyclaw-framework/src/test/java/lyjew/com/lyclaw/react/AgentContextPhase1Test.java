package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.config.ResolvedAgentConfig;

/**
 * AgentContext Phase 1 新增字段和方法的测试。
 */
@DisplayName("AgentContext Phase 1 测试")
class AgentContextPhase1Test {

    // ========== 构造器测试 ==========

    @Nested
    @DisplayName("构造器")
    class Constructor {

        @Test
        @DisplayName("8 参数构造器应设置 agentId 和 agentName")
        void eightParamConstructorSetsAgentIdAndAgentName() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null,
                    "agent-001", "MyAgent");

            assertThat(ctx.getAgentId()).isEqualTo("agent-001");
            assertThat(ctx.getAgentName()).isEqualTo("MyAgent");
        }

        @Test
        @DisplayName("旧 6 参数构造器应使 agentId 为 null（向后兼容）")
        void oldConstructorLeavesAgentIdNull() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getAgentId()).isNull();
            assertThat(ctx.getAgentName()).isNull();
        }
    }

    // ========== 工作区/Agent 目录测试 ==========

    @Nested
    @DisplayName("workspaceDir 和 agentDir")
    class WorkspaceAndAgentDir {

        @Test
        @DisplayName("应能设置和获取 workspaceDir 与 agentDir")
        void shouldSetAndGetWorkspaceAndAgentDir() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setWorkspaceDir("/home/user/workspace");
            ctx.setAgentDir("/home/user/workspace/agents/my-agent");

            assertThat(ctx.getWorkspaceDir()).isEqualTo("/home/user/workspace");
            assertThat(ctx.getAgentDir()).isEqualTo("/home/user/workspace/agents/my-agent");
        }

        @Test
        @DisplayName("旧构造器创建的上下文中 workspaceDir 和 agentDir 初始为 null")
        void workspaceAndAgentDirInitiallyNull() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getWorkspaceDir()).isNull();
            assertThat(ctx.getAgentDir()).isNull();
        }
    }

    // ========== ResolvedAgentConfig 测试 ==========

    @Nested
    @DisplayName("resolvedConfig")
    class ResolvedConfig {

        @Test
        @DisplayName("应能设置和获取 resolvedConfig")
        void shouldSetAndGetResolvedConfig() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ResolvedAgentConfig config = ResolvedAgentConfig.builder()
                    .agentId("agent-config-1")
                    .agentName("ConfigAgent")
                    .model("gpt-4")
                    .build();

            ctx.setResolvedConfig(config);

            assertThat(ctx.getResolvedConfig()).isNotNull();
            assertThat(ctx.getResolvedConfig().getAgentId()).isEqualTo("agent-config-1");
            assertThat(ctx.getResolvedConfig().getAgentName()).isEqualTo("ConfigAgent");
            assertThat(ctx.getResolvedConfig().getModel()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("旧构造器创建的上下文中 resolvedConfig 初始为 null")
        void resolvedConfigInitiallyNull() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getResolvedConfig()).isNull();
        }
    }

    // ========== 思考/详细度/推理级别测试 ==========

    @Nested
    @DisplayName("thinkingLevel / verboseLevel / reasoningLevel")
    class ThinkingVerboseReasoning {

        @Test
        @DisplayName("应能设置和获取 thinkingLevel")
        void shouldSetAndGetThinkingLevel() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setThinkingLevel("high");
            assertThat(ctx.getThinkingLevel()).isEqualTo("high");

            ctx.setThinkingLevel("low");
            assertThat(ctx.getThinkingLevel()).isEqualTo("low");
        }

        @Test
        @DisplayName("应能设置和获取 verboseLevel")
        void shouldSetAndGetVerboseLevel() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setVerboseLevel("detailed");
            assertThat(ctx.getVerboseLevel()).isEqualTo("detailed");

            ctx.setVerboseLevel("minimal");
            assertThat(ctx.getVerboseLevel()).isEqualTo("minimal");
        }

        @Test
        @DisplayName("应能设置和获取 reasoningLevel")
        void shouldSetAndGetReasoningLevel() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setReasoningLevel("deep");
            assertThat(ctx.getReasoningLevel()).isEqualTo("deep");

            ctx.setReasoningLevel("shallow");
            assertThat(ctx.getReasoningLevel()).isEqualTo("shallow");
        }

        @Test
        @DisplayName("旧构造器创建的上下文中三个级别初始为 null")
        void levelsInitiallyNull() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getThinkingLevel()).isNull();
            assertThat(ctx.getVerboseLevel()).isNull();
            assertThat(ctx.getReasoningLevel()).isNull();
        }
    }

    // ========== runtimeType 测试 ==========

    @Nested
    @DisplayName("runtimeType")
    class RuntimeType {

        @Test
        @DisplayName("旧构造器创建的上下文中默认 runtimeType 为 EMBEDDED")
        void defaultRuntimeTypeIsEmbedded() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.EMBEDDED);
        }

        @Test
        @DisplayName("8 参数构造器创建的上下文中默认 runtimeType 也为 EMBEDDED")
        void eightParamConstructorDefaultRuntimeTypeIsEmbedded() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null,
                    "agent-001", "MyAgent");

            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.EMBEDDED);
        }

        @Test
        @DisplayName("应能将 runtimeType 设置为 ACP")
        void shouldSetRuntimeTypeToAcp() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setRuntimeType(AgentRuntimeType.ACP);

            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.ACP);
        }

        @Test
        @DisplayName("runtimeType 能在 EMBEDDED 和 ACP 之间来回切换")
        void shouldSwitchRuntimeTypeBackAndForth() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setRuntimeType(AgentRuntimeType.ACP);
            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.ACP);

            ctx.setRuntimeType(AgentRuntimeType.EMBEDDED);
            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.EMBEDDED);
        }
    }

    // ========== runMetadata 测试 ==========

    @Nested
    @DisplayName("runMetadata")
    class RunMetadata {

        @Test
        @DisplayName("应能通过 setRunMetadata 写入并通过 getRunMetadata(key) 读取")
        void shouldSetAndGetRunMetadata() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setRunMetadata("runId", "run-123");
            ctx.setRunMetadata("jobId", "job-456");
            ctx.setRunMetadata("trigger", "manual");

            assertThat((String) ctx.getRunMetadata("runId")).isEqualTo("run-123");
            assertThat((String) ctx.getRunMetadata("jobId")).isEqualTo("job-456");
            assertThat((String) ctx.getRunMetadata("trigger")).isEqualTo("manual");
        }

        @Test
        @DisplayName("getRunMetadata(key) 对不存在的 key 返回 null")
        void getRunMetadataReturnsNullForMissingKey() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getRunMetadata("nonexistent")).isNull();
        }

        @Test
        @DisplayName("getRunMetadata() 返回的 map 不可修改")
        void getRunMetadataReturnsUnmodifiableMap() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
            ctx.setRunMetadata("key", "value");

            Map<String, Object> metadata = ctx.getRunMetadata();

            assertThatThrownBy(() -> metadata.put("newKey", "newValue"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getRunMetadata() 应返回已设置的所有条目")
        void getRunMetadataReturnsAllEntries() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
            ctx.setRunMetadata("a", 1);
            ctx.setRunMetadata("b", "two");
            ctx.setRunMetadata("c", true);

            Map<String, Object> metadata = ctx.getRunMetadata();

            assertThat(metadata).hasSize(3);
            assertThat(metadata).containsEntry("a", 1);
            assertThat(metadata).containsEntry("b", "two");
            assertThat(metadata).containsEntry("c", true);
        }

        @Test
        @DisplayName("重复写入同一 key 应覆盖旧值")
        void setRunMetadataOverwritesExistingKey() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.setRunMetadata("key", "oldValue");
            ctx.setRunMetadata("key", "newValue");

            assertThat((String) ctx.getRunMetadata("key")).isEqualTo("newValue");
        }
    }

    // ========== 活跃子 Agent 测试 ==========

    @Nested
    @DisplayName("activeSubagentIds")
    class ActiveSubagentIds {

        @Test
        @DisplayName("addActiveSubagent 应添加子 Agent 并返回 true")
        void addActiveSubagentShouldAddAndReturnTrue() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            boolean added = ctx.addActiveSubagent("sub-agent-1");

            assertThat(added).isTrue();
            assertThat(ctx.getActiveSubagentCount()).isEqualTo(1);
            assertThat(ctx.getActiveSubagentIds()).contains("sub-agent-1");
        }

        @Test
        @DisplayName("removeActiveSubagent 应移除已添加的子 Agent")
        void removeActiveSubagentShouldRemoveExisting() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.addActiveSubagent("sub-agent-1");
            ctx.addActiveSubagent("sub-agent-2");
            boolean removed = ctx.removeActiveSubagent("sub-agent-1");

            assertThat(removed).isTrue();
            assertThat(ctx.getActiveSubagentIds()).doesNotContain("sub-agent-1");
            assertThat(ctx.getActiveSubagentIds()).contains("sub-agent-2");
            assertThat(ctx.getActiveSubagentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("移除不存在的子 Agent 应返回 false")
        void removeNonexistentSubagentShouldReturnFalse() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            boolean removed = ctx.removeActiveSubagent("nonexistent");

            assertThat(removed).isFalse();
        }

        @Test
        @DisplayName("getActiveSubagentIds 返回的 set 不可修改")
        void getActiveSubagentIdsReturnsUnmodifiableSet() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
            ctx.addActiveSubagent("sub-agent-1");

            java.util.Set<String> ids = ctx.getActiveSubagentIds();

            assertThatThrownBy(() -> ids.add("sub-agent-2"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getActiveSubagentCount 应返回正确的子 Agent 数量")
        void getActiveSubagentCountShouldReturnCorrectCount() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getActiveSubagentCount()).isEqualTo(0);

            ctx.addActiveSubagent("sub-1");
            ctx.addActiveSubagent("sub-2");
            ctx.addActiveSubagent("sub-3");

            assertThat(ctx.getActiveSubagentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("重复添加同一子 Agent ID 应返回 false 且不增加计数")
        void addingDuplicateSubagentShouldReturnFalse() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.addActiveSubagent("sub-1");
            boolean addedAgain = ctx.addActiveSubagent("sub-1");

            assertThat(addedAgain).isFalse();
            assertThat(ctx.getActiveSubagentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("旧构造器创建的上下文中活跃子 Agent 初始为空")
        void activeSubagentsInitiallyEmpty() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getActiveSubagentCount()).isEqualTo(0);
            assertThat(ctx.getActiveSubagentIds()).isEmpty();
        }
    }

    // ========== 快照/恢复测试 ==========

    @Nested
    @DisplayName("快照与恢复")
    class SnapshotAndRestore {

        @Test
        @DisplayName("toSnapshot 应包含 Phase 1 新增字段")
        void toSnapshotIncludesPhase1Fields() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null,
                    "agent-snap", "SnapshotAgent");
            ctx.setWorkspaceDir("/ws");
            ctx.setAgentDir("/ws/agents/snap");
            ctx.setThinkingLevel("high");
            ctx.setVerboseLevel("detailed");
            ctx.setReasoningLevel("deep");
            ctx.setRuntimeType(AgentRuntimeType.ACP);

            Map<String, Object> snapshot = ctx.toSnapshot();

            assertThat(snapshot).containsEntry("agentId", "agent-snap");
            assertThat(snapshot).containsEntry("agentName", "SnapshotAgent");
            assertThat(snapshot).containsEntry("workspaceDir", "/ws");
            assertThat(snapshot).containsEntry("agentDir", "/ws/agents/snap");
            assertThat(snapshot).containsEntry("thinkingLevel", "high");
            assertThat(snapshot).containsEntry("verboseLevel", "detailed");
            assertThat(snapshot).containsEntry("reasoningLevel", "deep");
            assertThat(snapshot).containsEntry("runtimeType", "ACP");
        }

        @Test
        @DisplayName("toSnapshot 对未设置的 Phase 1 字段写入 null")
        void toSnapshotWritesNullForUnsetPhase1Fields() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            Map<String, Object> snapshot = ctx.toSnapshot();

            assertThat(snapshot).containsEntry("agentId", null);
            assertThat(snapshot).containsEntry("agentName", null);
            assertThat(snapshot).containsEntry("workspaceDir", null);
            assertThat(snapshot).containsEntry("agentDir", null);
            assertThat(snapshot).containsEntry("thinkingLevel", null);
            assertThat(snapshot).containsEntry("verboseLevel", null);
            assertThat(snapshot).containsEntry("reasoningLevel", null);
        }

        @Test
        @DisplayName("restoreFromSnapshot 应恢复 Phase 1 字段")
        void restoreFromSnapshotRestoresPhase1Fields() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            Map<String, Object> snapshot = Map.ofEntries(
                    Map.entry("agentId", "restored-agent"),
                    Map.entry("agentName", "RestoredAgent"),
                    Map.entry("workspaceDir", "/restored/ws"),
                    Map.entry("agentDir", "/restored/ws/agents/r"),
                    Map.entry("thinkingLevel", "low"),
                    Map.entry("verboseLevel", "minimal"),
                    Map.entry("reasoningLevel", "shallow"),
                    Map.entry("runtimeType", "EMBEDDED")
            );

            ctx.restoreFromSnapshot(snapshot);

            assertThat(ctx.getAgentId()).isEqualTo("restored-agent");
            assertThat(ctx.getAgentName()).isEqualTo("RestoredAgent");
            assertThat(ctx.getWorkspaceDir()).isEqualTo("/restored/ws");
            assertThat(ctx.getAgentDir()).isEqualTo("/restored/ws/agents/r");
            assertThat(ctx.getThinkingLevel()).isEqualTo("low");
            assertThat(ctx.getVerboseLevel()).isEqualTo("minimal");
            assertThat(ctx.getReasoningLevel()).isEqualTo("shallow");
            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.EMBEDDED);
        }

        @Test
        @DisplayName("restoreFromSnapshot 对包含部分 Phase 1 字段的快照只恢复存在字段")
        void restoreFromSnapshotPartialFields() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);
            ctx.setThinkingLevel("high");
            ctx.setVerboseLevel("detailed");

            Map<String, Object> snapshot = Map.ofEntries(
                    Map.entry("agentId", "partial-agent"),
                    Map.entry("workspaceDir", "/partial/ws")
            );

            ctx.restoreFromSnapshot(snapshot);

            assertThat(ctx.getAgentId()).isEqualTo("partial-agent");
            assertThat(ctx.getAgentName()).isNull();  // 未在快照中，保持 null
            assertThat(ctx.getWorkspaceDir()).isEqualTo("/partial/ws");
            assertThat(ctx.getAgentDir()).isNull();
            // thinkingLevel 和 verboseLevel 不在快照中，保持原值
            assertThat(ctx.getThinkingLevel()).isEqualTo("high");
            assertThat(ctx.getVerboseLevel()).isEqualTo("detailed");
        }

        @Test
        @DisplayName("restoreFromSnapshot 传入 null 不应抛出异常")
        void restoreFromSnapshotHandlesNull() {
            AgentContext ctx = new AgentContext("s1", "hello", "sys", null, null, null);

            ctx.restoreFromSnapshot(null);

            // 不应有任何改变
            assertThat(ctx.getAgentId()).isNull();
        }
    }

    // ========== 便捷工厂测试 ==========

    @Nested
    @DisplayName("便捷工厂")
    class FactoryMethods {

        @Test
        @DisplayName("sessionScoped 工厂应设置 SESSION 生命周期")
        void sessionScopedFactorySetsSessionLifecycle() {
            AgentContext ctx = AgentContext.sessionScoped("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getLifecycle()).isEqualTo(AgentContext.Lifecycle.SESSION);
            assertThat(ctx.getSessionId()).isEqualTo("s1");
            assertThat(ctx.getUserMessage()).isEqualTo("hello");
        }

        @Test
        @DisplayName("persistentScoped 工厂应设置 PERSISTENT 生命周期")
        void persistentScopedFactorySetsPersistentLifecycle() {
            AgentContext ctx = AgentContext.persistentScoped("s1", "hello", "sys", null, null, null);

            assertThat(ctx.getLifecycle()).isEqualTo(AgentContext.Lifecycle.PERSISTENT);
            assertThat(ctx.getSessionId()).isEqualTo("s1");
            assertThat(ctx.getUserMessage()).isEqualTo("hello");
        }
    }
}
