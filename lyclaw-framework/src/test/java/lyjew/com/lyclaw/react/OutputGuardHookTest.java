package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * OutputGuardHook 输出安检测试：验证有害内容检测和幻觉标记。
 */
@DisplayName("OutputGuardHook 输出安检测试")
class OutputGuardHookTest {

    private OutputGuardHook hook;
    private AgentContext ctx;

    @BeforeEach
    void setUp() {
        hook = new OutputGuardHook();
        ctx = new AgentContext("s1", "hello", "sys", null, null, null);
    }

    @Nested
    @DisplayName("有害内容拦截")
    class BlockedContentTests {

        @Test
        @DisplayName("响应包含 password: 赋值 → 拦截")
        void shouldBlockPasswordAssignment() {
            String response = "The password: admin123 for the database";
            String result = hook.afterModel(response, ctx);
            assertThat(result).contains("filtered");
            assertThat(result).doesNotContain("admin123");
        }

        @Test
        @DisplayName("响应包含 api_key: 赋值 → 拦截")
        void shouldBlockApiKeyAssignment() {
            String response = "Here is your api_key: sk-abc123def456 for authentication";
            String result = hook.afterModel(response, ctx);
            assertThat(result).contains("filtered");
        }

        @Test
        @DisplayName("响应包含 secret= 赋值 → 拦截")
        void shouldBlockSecretToken() {
            String response = "Use this secret=my-token-value to access the API";
            String result = hook.afterModel(response, ctx);
            assertThat(result).contains("filtered");
        }

        @Test
        @DisplayName("响应包含 script 标签 → 拦截")
        void shouldBlockScriptTag() {
            String response = "You can inject with <script>alert('xss')</script>";
            String result = hook.afterModel(response, ctx);
            assertThat(result).contains("filtered");
        }

        @Test
        @DisplayName("响应包含 DROP TABLE → 拦截")
        void shouldBlockDropTable() {
            String response = "To fix this, run: DROP TABLE users; and recreate";
            String result = hook.afterModel(response, ctx);
            assertThat(result).contains("filtered");
        }

        @Test
        @DisplayName("响应包含 DELETE FROM → 拦截")
        void shouldBlockDeleteFrom() {
            String response = "First execute: DELETE FROM sessions WHERE expired = true";
            String result = hook.afterModel(response, ctx);
            assertThat(result).contains("filtered");
        }

        @Test
        @DisplayName("大小写不敏感匹配")
        void shouldMatchCaseInsensitive() {
            String response = "The Password=Secret123 should not be shared";
            String result = hook.afterModel(response, ctx);
            assertThat(result).contains("filtered");
        }
    }

    @Nested
    @DisplayName("幻觉标记检测")
    class HallucinationDetectionTests {

        @Test
        @DisplayName("I am not sure → 标记幻觉但不拦截")
        void shouldFlagNotSure() {
            String response = "I am not sure about the answer to this question.";
            String result = hook.afterModel(response, ctx);

            assertThat(result).isEqualTo(response);
            assertThat(ctx.<Boolean>getAttribute("hallucination_detected")).isTrue();
        }

        @Test
        @DisplayName("I don't know → 标记幻觉")
        void shouldFlagDontKnow() {
            String response = "I don't know the exact value, but I can estimate.";
            String result = hook.afterModel(response, ctx);

            assertThat(ctx.<Boolean>getAttribute("hallucination_detected")).isTrue();
        }

        @Test
        @DisplayName("cannot provide → 标记幻觉")
        void shouldFlagCannotProvide() {
            String response = "I cannot provide that information.";
            String result = hook.afterModel(response, ctx);

            assertThat(ctx.<Boolean>getAttribute("hallucination_detected")).isTrue();
        }

        @Test
        @DisplayName("no information → 标记幻觉")
        void shouldFlagNoInformation() {
            String response = "There is no information available on this topic.";
            String result = hook.afterModel(response, ctx);

            assertThat(ctx.<Boolean>getAttribute("hallucination_detected")).isTrue();
        }
    }

    @Nested
    @DisplayName("正常响应放行")
    class NormalResponseTests {

        @Test
        @DisplayName("正常文本响应 → 放行")
        void shouldPassNormalResponse() {
            String response = "The weather in Beijing is sunny with a temperature of 25°C.";
            String result = hook.afterModel(response, ctx);

            assertThat(result).isEqualTo(response);
            assertThat(ctx.<Boolean>getAttribute("hallucination_detected")).isNull();
        }

        @Test
        @DisplayName("空响应 → 不抛异常")
        void shouldHandleEmptyResponse() {
            assertThat(hook.afterModel("", ctx)).isEmpty();
        }

        @Test
        @DisplayName("null 响应 → 不抛异常")
        void shouldHandleNullResponse() {
            assertThat(hook.afterModel(null, ctx)).isNull();
        }

        @Test
        @DisplayName("包含 password 但不是赋值语句 → 放行")
        void shouldNotBlockPasswordMentionWithoutAssignment() {
            String response = "Please use your password to login to the system.";
            String result = hook.afterModel(response, ctx);

            assertThat(result).isEqualTo(response);
        }
    }
}
