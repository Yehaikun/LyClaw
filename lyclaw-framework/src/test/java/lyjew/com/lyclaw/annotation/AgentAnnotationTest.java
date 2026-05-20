package lyjew.com.lyclaw.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Agent} annotation metadata.
 *
 * <p>Validates retention, target, meta-annotations, default values, custom
 * overrides, and that all declared fields are accessible via reflection.
 */
class AgentAnnotationTest {

    // ── Stub classes annotated with @Agent ───────────────────────────

    @Agent
    static class DefaultAgentClass {
    }

    @Agent(id = "test-agent",
           name = "TestAgent",
           description = "A test agent for unit tests",
           version = "2.0.0",
           model = "gpt-4",
           provider = "openai",
           fallbacks = {"fallback-1", "fallback-2"},
           skills = {"java", "python"},
           defaultAgent = true,
           fastModeDefault = true,
           contextTokens = 8192,
           bootstrapMaxChars = 10000,
           bootstrapTotalMaxChars = 75000,
           contextInjection = "continuation-skip",
           delegationMode = "prefer",
           allowAgents = {"agent-a", "agent-b"},
           maxSpawnDepth = 3,
           maxChildrenPerAgent = 10,
           sandbox = "docker",
           workspace = "/tmp/ws",
           agentDir = "my-agent-dir",
           systemPromptOverride = "You are a helpful assistant.",
           thinkingDefault = "high",
           verboseDefault = "medium",
           reasoningDefault = "xhigh",
           extensions = {
               @Extension(key = "priority", value = "1"),
               @Extension(key = "env", value = "prod")
           })
    static class FullAgentClass {
    }

    // ── Tests ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Annotation metadata")
    class AnnotationMetadataTests {

        @Test
        @DisplayName("@Retention is RUNTIME")
        void retentionIsRuntime() {
            Retention retention = Agent.class.getAnnotation(Retention.class);
            assertThat(retention).isNotNull();
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        @DisplayName("@Target includes TYPE")
        void targetIncludesType() {
            Target target = Agent.class.getAnnotation(Target.class);
            assertThat(target).isNotNull();
            assertThat(target.value()).contains(ElementType.TYPE);
        }
    }

    @Nested
    @DisplayName("Meta-annotations")
    class MetaAnnotationTests {

        @Test
        @DisplayName("meta-annotated with @Component")
        void hasComponentMetaAnnotation() {
            org.springframework.stereotype.Component component =
                    Agent.class.getAnnotation(org.springframework.stereotype.Component.class);
            assertThat(component).isNotNull();
        }

        @Test
        @DisplayName("meta-annotated with @Documented")
        void hasDocumentedMetaAnnotation() {
            Documented documented = Agent.class.getAnnotation(Documented.class);
            assertThat(documented).isNotNull();
        }
    }

    @Nested
    @DisplayName("Default values")
    class DefaultValueTests {

        @Test
        @DisplayName("id default is empty string")
        void idDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.id()).isEmpty();
        }

        @Test
        @DisplayName("defaultAgent default is false")
        void defaultAgentDefaultIsFalse() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.defaultAgent()).isFalse();
        }

        @Test
        @DisplayName("name default is empty string")
        void nameDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.name()).isEmpty();
        }

        @Test
        @DisplayName("description default is empty string")
        void descriptionDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.description()).isEmpty();
        }

        @Test
        @DisplayName("version default is 1.0.0")
        void versionDefault() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.version()).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("workspace default is empty string")
        void workspaceDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.workspace()).isEmpty();
        }

        @Test
        @DisplayName("agentDir default is empty string")
        void agentDirDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.agentDir()).isEmpty();
        }

        @Test
        @DisplayName("systemPromptOverride default is empty string")
        void systemPromptOverrideDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.systemPromptOverride()).isEmpty();
        }

        @Test
        @DisplayName("model default is empty string")
        void modelDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.model()).isEmpty();
        }

        @Test
        @DisplayName("provider default is empty string")
        void providerDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.provider()).isEmpty();
        }

        @Test
        @DisplayName("fallbacks default is empty array")
        void fallbacksDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.fallbacks()).isEmpty();
        }

        @Test
        @DisplayName("skills default is empty array")
        void skillsDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.skills()).isEmpty();
        }

        @Test
        @DisplayName("thinkingDefault default is empty string")
        void thinkingDefaultDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.thinkingDefault()).isEmpty();
        }

        @Test
        @DisplayName("verboseDefault default is empty string")
        void verboseDefaultDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.verboseDefault()).isEmpty();
        }

        @Test
        @DisplayName("reasoningDefault default is empty string")
        void reasoningDefaultDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.reasoningDefault()).isEmpty();
        }

        @Test
        @DisplayName("fastModeDefault default is false")
        void fastModeDefaultDefaultIsFalse() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.fastModeDefault()).isFalse();
        }

        @Test
        @DisplayName("contextTokens default is 0")
        void contextTokensDefaultIsZero() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.contextTokens()).isZero();
        }

        @Test
        @DisplayName("bootstrapMaxChars default is 0 (not set)")
        void bootstrapMaxCharsDefault() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.bootstrapMaxChars()).isEqualTo(0);
        }

        @Test
        @DisplayName("bootstrapTotalMaxChars default is 0 (not set)")
        void bootstrapTotalMaxCharsDefault() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.bootstrapTotalMaxChars()).isEqualTo(0);
        }

        @Test
        @DisplayName("contextInjection default is always")
        void contextInjectionDefault() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.contextInjection()).isEqualTo("always");
        }

        @Test
        @DisplayName("delegationMode default is suggest")
        void delegationModeDefault() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.delegationMode()).isEqualTo("suggest");
        }

        @Test
        @DisplayName("allowAgents default is empty array")
        void allowAgentsDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.allowAgents()).isEmpty();
        }

        @Test
        @DisplayName("maxSpawnDepth default is 0 (not set)")
        void maxSpawnDepthDefault() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.maxSpawnDepth()).isEqualTo(0);
        }

        @Test
        @DisplayName("maxChildrenPerAgent default is 0 (not set)")
        void maxChildrenPerAgentDefault() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.maxChildrenPerAgent()).isEqualTo(0);
        }

        @Test
        @DisplayName("sandbox default is empty string")
        void sandboxDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.sandbox()).isEmpty();
        }

        @Test
        @DisplayName("extensions default is empty array")
        void extensionsDefaultEmpty() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent.extensions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Custom value overrides")
    class CustomValueTests {

        private final Agent agent = FullAgentClass.class.getAnnotation(Agent.class);

        @Test
        @DisplayName("custom id overrides default")
        void customId() {
            assertThat(agent.id()).isEqualTo("test-agent");
        }

        @Test
        @DisplayName("custom name overrides default")
        void customName() {
            assertThat(agent.name()).isEqualTo("TestAgent");
        }

        @Test
        @DisplayName("custom description overrides default")
        void customDescription() {
            assertThat(agent.description()).isEqualTo("A test agent for unit tests");
        }

        @Test
        @DisplayName("custom version overrides default")
        void customVersion() {
            assertThat(agent.version()).isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("custom workspace overrides default")
        void customWorkspace() {
            assertThat(agent.workspace()).isEqualTo("/tmp/ws");
        }

        @Test
        @DisplayName("custom agentDir overrides default")
        void customAgentDir() {
            assertThat(agent.agentDir()).isEqualTo("my-agent-dir");
        }

        @Test
        @DisplayName("custom systemPromptOverride overrides default")
        void customSystemPromptOverride() {
            assertThat(agent.systemPromptOverride()).isEqualTo("You are a helpful assistant.");
        }

        @Test
        @DisplayName("custom model overrides default")
        void customModel() {
            assertThat(agent.model()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("custom provider overrides default")
        void customProvider() {
            assertThat(agent.provider()).isEqualTo("openai");
        }

        @Test
        @DisplayName("custom fallbacks array")
        void customFallbacks() {
            assertThat(agent.fallbacks()).containsExactly("fallback-1", "fallback-2");
        }

        @Test
        @DisplayName("custom skills array")
        void customSkills() {
            assertThat(agent.skills()).containsExactly("java", "python");
        }

        @Test
        @DisplayName("custom thinkingDefault overrides default")
        void customThinkingDefault() {
            assertThat(agent.thinkingDefault()).isEqualTo("high");
        }

        @Test
        @DisplayName("custom verboseDefault overrides default")
        void customVerboseDefault() {
            assertThat(agent.verboseDefault()).isEqualTo("medium");
        }

        @Test
        @DisplayName("custom reasoningDefault overrides default")
        void customReasoningDefault() {
            assertThat(agent.reasoningDefault()).isEqualTo("xhigh");
        }

        @Test
        @DisplayName("custom fastModeDefault sets true")
        void customFastModeDefault() {
            assertThat(agent.fastModeDefault()).isTrue();
        }

        @Test
        @DisplayName("custom contextTokens overrides default")
        void customContextTokens() {
            assertThat(agent.contextTokens()).isEqualTo(8192);
        }

        @Test
        @DisplayName("custom bootstrapMaxChars overrides default")
        void customBootstrapMaxChars() {
            assertThat(agent.bootstrapMaxChars()).isEqualTo(10000);
        }

        @Test
        @DisplayName("custom bootstrapTotalMaxChars overrides default")
        void customBootstrapTotalMaxChars() {
            assertThat(agent.bootstrapTotalMaxChars()).isEqualTo(75000);
        }

        @Test
        @DisplayName("custom contextInjection overrides default")
        void customContextInjection() {
            assertThat(agent.contextInjection()).isEqualTo("continuation-skip");
        }

        @Test
        @DisplayName("custom delegationMode overrides default")
        void customDelegationMode() {
            assertThat(agent.delegationMode()).isEqualTo("prefer");
        }

        @Test
        @DisplayName("custom allowAgents array")
        void customAllowAgents() {
            assertThat(agent.allowAgents()).containsExactly("agent-a", "agent-b");
        }

        @Test
        @DisplayName("custom maxSpawnDepth overrides default")
        void customMaxSpawnDepth() {
            assertThat(agent.maxSpawnDepth()).isEqualTo(3);
        }

        @Test
        @DisplayName("custom maxChildrenPerAgent overrides default")
        void customMaxChildrenPerAgent() {
            assertThat(agent.maxChildrenPerAgent()).isEqualTo(10);
        }

        @Test
        @DisplayName("custom sandbox overrides default")
        void customSandbox() {
            assertThat(agent.sandbox()).isEqualTo("docker");
        }

        @Test
        @DisplayName("custom extensions array")
        void customExtensions() {
            Extension[] extensions = agent.extensions();
            assertThat(extensions).hasSize(2);

            assertThat(extensions[0].key()).isEqualTo("priority");
            assertThat(extensions[0].value()).isEqualTo("1");

            assertThat(extensions[1].key()).isEqualTo("env");
            assertThat(extensions[1].value()).isEqualTo("prod");
        }

        @Test
        @DisplayName("custom defaultAgent sets true")
        void customDefaultAgent() {
            assertThat(agent.defaultAgent()).isTrue();
        }
    }

    @Nested
    @DisplayName("Full field accessibility")
    class FieldAccessibilityTests {

        @Test
        @DisplayName("all 26 declared methods are accessible via reflection")
        void allDeclaredMethodsAccessible() {
            Method[] methods = Agent.class.getDeclaredMethods();
            // Account for potential compiler-generated $default methods or
            // switch-table helpers by counting only the annotation attribute
            // methods (those without parameters and with declared defaults).
            long attributeCount = 0;
            for (Method method : methods) {
                // Annotation attribute methods have no parameters and a default value.
                if (method.getParameterCount() == 0
                        && method.getDefaultValue() != null) {
                    attributeCount++;
                    assertThatNoException()
                            .as("Method %s should be invocable", method.getName())
                            .isThrownBy(() -> method.invoke(
                                    DefaultAgentClass.class.getAnnotation(Agent.class)));
                }
            }
            // The @Agent annotation declares 26 fields.
            assertThat(attributeCount).isEqualTo(26);
        }

        @Test
        @DisplayName("every field name matches expected set")
        void fieldNamesAreCorrect() {
            String[] expected = {
                    "id", "defaultAgent", "name", "description", "version",
                    "workspace", "agentDir", "systemPromptOverride",
                    "model", "provider", "fallbacks", "skills",
                    "thinkingDefault", "verboseDefault", "reasoningDefault",
                    "fastModeDefault", "contextTokens", "bootstrapMaxChars",
                    "bootstrapTotalMaxChars", "contextInjection",
                    "delegationMode", "allowAgents", "maxSpawnDepth",
                    "maxChildrenPerAgent", "sandbox", "extensions",
            };

            Method[] methods = Agent.class.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getParameterCount() == 0
                        && method.getDefaultValue() != null) {
                    assertThat(method.getName())
                            .as("Unexpected attribute method: %s", method.getName())
                            .isIn((Object[]) expected);
                }
            }
        }

        @Test
        @DisplayName("no attribute method throws when invoked on default-annotation class")
        void noDefaultInvocationThrows() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            Method[] methods = Agent.class.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getParameterCount() == 0
                        && method.getDefaultValue() != null) {
                    assertThatNoException()
                            .as("Invoking %s on default annotation should not throw",
                                    method.getName())
                            .isThrownBy(() -> method.invoke(agent));
                }
            }
        }

        @Test
        @DisplayName("no attribute method throws when invoked on full-annotation class")
        void noFullInvocationThrows() {
            Agent agent = FullAgentClass.class.getAnnotation(Agent.class);
            Method[] methods = Agent.class.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getParameterCount() == 0
                        && method.getDefaultValue() != null) {
                    assertThatNoException()
                            .as("Invoking %s on full annotation should not throw",
                                    method.getName())
                            .isThrownBy(() -> method.invoke(agent));
                }
            }
        }
    }

    @Nested
    @DisplayName("Inherited annotation presence")
    class InheritedAnnotationTests {

        @Test
        @DisplayName("annotation is present on @Agent stub class")
        void annotationPresentOnStub() {
            Agent agent = DefaultAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent).isNotNull();
        }

        @Test
        @DisplayName("full annotation is present on full stub class")
        void fullAnnotationPresent() {
            Agent agent = FullAgentClass.class.getAnnotation(Agent.class);
            assertThat(agent).isNotNull();
        }

        @Test
        @DisplayName("annotation is Annotation type")
        void agentIsAnnotation() {
            assertThat(Agent.class.isAnnotation()).isTrue();
        }

        @Test
        @DisplayName("stub class is not an annotation itself")
        void stubClassIsNotAnnotation() {
            assertThat(DefaultAgentClass.class.isAnnotation()).isFalse();
        }
    }
}
