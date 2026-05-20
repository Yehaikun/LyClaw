package lyjew.com.lyclaw.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;

@DisplayName("AgentConfigResolver 3-layer merge")
class ConfigResolutionTest {

    static Agent agentAnnotation(String id, String name, String model, String provider,
                                  String[] fallbacks, String[] skills, boolean fastModeDefault,
                                  String thinkingDefault, String verboseDefault, String reasoningDefault,
                                  String sandbox, String delegationMode, int contextTokens,
                                  int maxSpawnDepth, int maxChildrenPerAgent, String[] allowAgents,
                                  String workspace, String agentDir, String systemPromptOverride,
                                  int bootstrapMaxChars, int bootstrapTotalMaxChars,
                                  String contextInjection, Extension[] extensions) {
        return new Agent() {
            @Override public Class<? extends Annotation> annotationType() { return Agent.class; }
            @Override public String id() { return id; }
            @Override public boolean defaultAgent() { return false; }
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public String version() { return "1.0.0"; }
            @Override public String workspace() { return workspace; }
            @Override public String agentDir() { return agentDir; }
            @Override public String systemPromptOverride() { return systemPromptOverride; }
            @Override public String model() { return model; }
            @Override public String provider() { return provider; }
            @Override public String[] fallbacks() { return fallbacks; }
            @Override public String[] skills() { return skills; }
            @Override public String thinkingDefault() { return thinkingDefault; }
            @Override public String verboseDefault() { return verboseDefault; }
            @Override public String reasoningDefault() { return reasoningDefault; }
            @Override public boolean fastModeDefault() { return fastModeDefault; }
            @Override public int contextTokens() { return contextTokens; }
            @Override public int bootstrapMaxChars() { return bootstrapMaxChars; }
            @Override public int bootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
            @Override public String contextInjection() { return contextInjection; }
            @Override public String delegationMode() { return delegationMode; }
            @Override public String[] allowAgents() { return allowAgents; }
            @Override public int maxSpawnDepth() { return maxSpawnDepth; }
            @Override public int maxChildrenPerAgent() { return maxChildrenPerAgent; }
            @Override public String sandbox() { return sandbox; }
            @Override public Extension[] extensions() { return extensions; }
        };
    }

    static Agent emptyAnn() {
        return agentAnnotation("", "", "", "", new String[0], new String[0], false,
                "", "", "", "", "", 0, 1, 5, new String[0],
                "", "", "", 0, 0, "always", new Extension[0]);
    }

    // === Null annotation ===

    @Nested @DisplayName("Null annotation")
    class NullAnnotation {
        @Test @DisplayName("returns system defaults")
        void returnsSystemDefaults() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            ResolvedAgentConfig c = r.resolve(null);
            assertThat(c.getModel()).isEqualTo(AgentSystemDefaults.MODEL);
            assertThat(c.getProvider()).isEqualTo(AgentSystemDefaults.PROVIDER);
            assertThat(c.getThinkingDefault()).isEqualTo(AgentSystemDefaults.THINKING_DEFAULT);
            assertThat(c.getSandbox()).isEqualTo(AgentSystemDefaults.SANDBOX);
            assertThat(c.getAgentId()).isEmpty();
        }
    }

    // === Annotation priority ===

    @Nested @DisplayName("Annotation priority")
    class AnnotationPriority {
        @Test @DisplayName("model from annotation overrides defaults")
        void modelOverrides() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setModel("default-model");
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "gpt-4", "openai", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            ResolvedAgentConfig c = r.resolve(ann);
            assertThat(c.getModel()).isEqualTo("gpt-4");
            assertThat(c.getProvider()).isEqualTo("openai");
        }

        @Test @DisplayName("thinkingDefault from annotation")
        void thinkingFromAnnotation() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "high", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getThinkingDefault()).isEqualTo("high");
        }

        @Test @DisplayName("sandbox from annotation")
        void sandboxFromAnnotation() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "docker", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getSandbox()).isEqualTo("docker");
        }

        @Test @DisplayName("delegationMode from annotation")
        void delegationModeFromAnnotation() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "prefer", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getDelegationMode()).isEqualTo("prefer");
        }

        @Test @DisplayName("bootstrapMaxChars non-default from annotation")
        void bootstrapMaxCharsOverride() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 50000, 150000, "always", new Extension[0]);
            assertThat(r.resolve(ann).getBootstrapMaxChars()).isEqualTo(50000);
        }
    }

    // === Defaults bridge ===

    @Nested @DisplayName("Defaults bridge")
    class DefaultsBridge {
        @Test @DisplayName("yml defaults fill when annotation empty")
        void defaultsFill() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setModel("yml-model");
            def.setProvider("yml-provider");
            def.setThinkingDefault("low");
            def.setSandbox("podman");
            AgentConfigResolver r = new AgentConfigResolver(def);
            ResolvedAgentConfig c = r.resolve(emptyAnn());
            assertThat(c.getModel()).isEqualTo("yml-model");
            assertThat(c.getProvider()).isEqualTo("yml-provider");
            assertThat(c.getThinkingDefault()).isEqualTo("low");
            assertThat(c.getSandbox()).isEqualTo("podman");
        }

        @Test @DisplayName("system default when yml empty")
        void systemDefaultFallback() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            ResolvedAgentConfig c = r.resolve(emptyAnn());
            assertThat(c.getDelegationMode()).isEqualTo(AgentSystemDefaults.DELEGATION_MODE);
            assertThat(c.getContextInjection()).isEqualTo(AgentSystemDefaults.CONTEXT_INJECTION);
        }
    }

    // === List merge ===

    @Nested @DisplayName("List merge")
    class ListMerge {
        @Test @DisplayName("annotation fallbacks override defaults")
        void annotationFallbacksOverride() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setFallbacks(List.of("fb1", "fb2"));
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[]{"a1", "a2"}, new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getFallbacks()).containsExactly("a1", "a2");
        }

        @Test @DisplayName("defaults fallbacks when annotation empty")
        void defaultsFallbacks() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setFallbacks(List.of("fb1"));
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).getFallbacks()).containsExactly("fb1");
        }

        @Test @DisplayName("empty when both empty")
        void emptyWhenBothEmpty() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).getFallbacks()).isEmpty();
        }

        @Test @DisplayName("skills override defaults")
        void skillsOverride() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setSkills(List.of("default-skill"));
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[]{"agent-skill"}, false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getSkills()).containsExactly("agent-skill");
        }

        @Test @DisplayName("allowAgents annotation priority")
        void allowAgentsPriority() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setAllowAgents(List.of("agent1"));
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[]{"agent2", "agent3"},
                    "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getAllowAgents()).containsExactly("agent2", "agent3");
        }
    }

    // === Boolean ===

    @Nested @DisplayName("Boolean")
    class BooleanFields {
        @Test @DisplayName("fastModeDefault true from annotation")
        void fastModeTrue() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], true,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).isFastModeDefault()).isTrue();
        }

        @Test @DisplayName("fastModeDefault from defaults")
        void fastModeFromDefaults() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setFastModeDefault(true);
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).isFastModeDefault()).isTrue();
        }

        @Test @DisplayName("fastModeDefault false by default")
        void fastModeDefaultFalse() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).isFastModeDefault()).isFalse();
        }
    }

    // === Int fields ===

    @Nested @DisplayName("Integer zero check")
    class IntegerFields {
        @Test @DisplayName("contextTokens=0 falls to defaults")
        void contextTokensFallback() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setContextTokens(4096);
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).getContextTokens()).isEqualTo(4096);
        }

        @Test @DisplayName("contextTokens non-zero from annotation")
        void contextTokensAnnotation() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 8192, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getContextTokens()).isEqualTo(8192);
        }

        @Test @DisplayName("maxSpawnDepth and maxChildren from annotation")
        void spawnDepthAndChildren() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 3, 10, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            ResolvedAgentConfig c = r.resolve(ann);
            assertThat(c.getMaxSpawnDepth()).isEqualTo(3);
            assertThat(c.getMaxChildrenPerAgent()).isEqualTo(10);
        }
    }

    // === Agent ID ===

    @Nested @DisplayName("Agent ID")
    class AgentIdTests {
        @Test @DisplayName("id set directly")
        void idDirect() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("custom-id", "Display", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            ResolvedAgentConfig c = r.resolve(ann);
            assertThat(c.getAgentId()).isEqualTo("custom-id");
            assertThat(c.getAgentName()).isEqualTo("Display");
        }

        @Test @DisplayName("id derived from name")
        void idFromName() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "ChatAgent", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getAgentId()).isEqualTo("chatAgent");
        }

        @Test @DisplayName("name falls back to id")
        void nameFallsBack() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("my-agent", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            ResolvedAgentConfig c = r.resolve(ann);
            assertThat(c.getAgentId()).isEqualTo("my-agent");
            assertThat(c.getAgentName()).isEqualTo("my-agent");
        }
    }

    // === Extensions ===

    @Nested @DisplayName("Extensions")
    class ExtensionsTests {
        @Test @DisplayName("extensions to map")
        void extensionsToMap() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Extension e1 = new Extension() {
                @Override public Class<? extends Annotation> annotationType() { return Extension.class; }
                @Override public String key() { return "k1"; }
                @Override public String value() { return "v1"; }
            };
            Extension e2 = new Extension() {
                @Override public Class<? extends Annotation> annotationType() { return Extension.class; }
                @Override public String key() { return "k2"; }
                @Override public String value() { return "v2"; }
            };
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 20000, 150000, "always",
                    new Extension[]{e1, e2});
            assertThat(r.resolve(ann).getExtensions()).containsEntry("k1", "v1").containsEntry("k2", "v2");
        }

        @Test @DisplayName("empty extensions")
        void emptyExtensions() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).getExtensions()).isEmpty();
        }
    }

    // === Workspace ===

    @Nested @DisplayName("Workspace")
    class WorkspaceTests {
        @Test @DisplayName("workspace from annotation")
        void workspaceOverride() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setWorkspace("/default/ws");
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0],
                    "/custom/ws", "", "", 20000, 150000, "always", new Extension[0]);
            assertThat(r.resolve(ann).getWorkspaceDir()).isEqualTo("/custom/ws");
        }

        @Test @DisplayName("agentDir falls back to agentId")
        void agentDirFallback() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            Agent ann = agentAnnotation("my-agent", "", "", "", new String[0], new String[0], false,
                    "", "", "", "", "", 0, 1, 5, new String[0], "", "", "", 0, 0, "always", new Extension[0]);
            assertThat(r.resolve(ann).getAgentDir()).isEqualTo("my-agent");
        }
    }

    // === Immutability ===

    @Nested @DisplayName("Immutability")
    class ImmutabilityTests {
        @Test @DisplayName("fallbacks unmodifiable")
        void fallbacksUnmodifiable() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            def.setFallbacks(List.of("fb1"));
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).getFallbacks()).isUnmodifiable();
        }

        @Test @DisplayName("extensions unmodifiable")
        void extensionsUnmodifiable() {
            AgentDefaultsConfig def = new AgentDefaultsConfig();
            AgentConfigResolver r = new AgentConfigResolver(def);
            assertThat(r.resolve(emptyAnn()).getExtensions()).isUnmodifiable();
        }
    }
}
