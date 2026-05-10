package lyjew.com.lyclaw.reflect.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 DefaultQualityEvaluator 的质量打分 (accuracy/completeness/safety/UX 四维)
 */
@DisplayName("DefaultQualityEvaluator 测试")
class DefaultQualityEvaluatorTest {

    private DefaultQualityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultQualityEvaluator();
    }

    @Nested
    @DisplayName("Accuracy 维度")
    class Accuracy {

        @Test
        void testPerfectMatch() {
            double score = evaluator.evaluateAccuracy(
                    "the sky is blue and beautiful",
                    "the sky is blue and beautiful");
            assertTrue(score > 0.8, "完全匹配应高分: " + score);
        }

        @Test
        void testPartialMatch() {
            double score = evaluator.evaluateAccuracy(
                    "the sky is blue",
                    "the sky is green");
            // 共享 "the sky is" 会有一些词重叠
            assertTrue(score > 0.3 && score < 0.9,
                    "部分匹配应中等分数: " + score);
        }

        @Test
        void testCompletelyDifferent() {
            double score = evaluator.evaluateAccuracy(
                    "quantum physics is fascinating",
                    "the cat sat on the mat");
            assertTrue(score < 0.5, "完全不同应低分: " + score);
        }

        @Test
        void testNullExpected() {
            double score = evaluator.evaluateAccuracy("some output", null);
            assertEquals(0.75, score);
        }

        @Test
        void testBlankExpected() {
            double score = evaluator.evaluateAccuracy("some output", "  ");
            assertEquals(0.75, score);
        }

        @Test
        void testNullOutput() {
            double score = evaluator.evaluateAccuracy(null, "expected");
            assertEquals(0.75, score);
        }
    }

    @Nested
    @DisplayName("Completeness 维度")
    class Completeness {

        @Test
        void testAllRequirementsAddressed() {
            double score = evaluator.evaluateCompleteness(
                    "The weather is sunny with temperature of 25 degrees and humidity of 60 percent",
                    "Describe the weather including temperature and humidity");
            assertTrue(score >= 0.5, "基本满足应中等以上: " + score);
        }

        @Test
        void testBlankOutput() {
            double score = evaluator.evaluateCompleteness("", "Do something");
            assertEquals(0.0, score);
        }

        @Test
        void testNullTaskDescription() {
            double score = evaluator.evaluateCompleteness("some output", null);
            assertEquals(0.8, score);
        }

        @Test
        void testBlankTaskDescription() {
            double score = evaluator.evaluateCompleteness("some output", "  ");
            assertEquals(0.8, score);
        }
    }

    @Nested
    @DisplayName("Safety 维度")
    class Safety {

        @Test
        void testCleanOutput() {
            double score = evaluator.evaluateSafety(
                    "This is a normal response with no issues.");
            assertEquals(1.0, score);
        }

        @Test
        void testPiiDetection() {
            double score = evaluator.evaluateSafety(
                    "Contact me at test@example.com");
            assertTrue(score < 1.0, "包含 email 应扣分: " + score);
        }

        @Test
        void testHarmfulContentDetection() {
            double score = evaluator.evaluateSafety(
                    "Here is how to make a bomb easily");
            assertTrue(score < 1.0, "包含有害内容应扣分: " + score);
        }

        @Test
        void testInjectionPatternDetection() {
            double score = evaluator.evaluateSafety(
                    "<script>alert('xss')</script>");
            assertTrue(score < 1.0, "包含注入模式应扣分: " + score);
        }

        @Test
        void testMultipleViolations() {
            double score = evaluator.evaluateSafety(
                    "Email test@evil.com about how to make a bomb, use <script>alert(1)</script>");
            assertTrue(score < 0.5, "多重违规应很低分数: " + score);
        }

        @Test
        void testNullOutputSafety() {
            assertEquals(1.0, evaluator.evaluateSafety(null));
        }

        @Test
        void testBlankOutputSafety() {
            assertEquals(1.0, evaluator.evaluateSafety("  "));
        }

        @Test
        void testSafetyScoreNeverNegative() {
            double score = evaluator.evaluateSafety(
                    "test@a.com test2@b.com how to make a bomb " +
                    "<script>alert(1)</script> SELECT * FROM users DROP TABLE");
            assertTrue(score >= 0.0, "安全分数不应为负: " + score);
        }
    }

    @Nested
    @DisplayName("User Experience 维度")
    class UserExperience {

        @Test
        void testWellStructuredOutput() {
            double score = evaluator.evaluateUserExperience(
                    "# Title\n\nHere is a paragraph with some **bold** text.\n\n" +
                    "- Item 1\n- Item 2\n\n```code block```\n\n" +
                    "Thank you for your consideration. I would suggest this approach.");
            assertTrue(score > 0.7, "结构化输出应高分: " + score);
        }

        @Test
        void testShortOutput() {
            double score = evaluator.evaluateUserExperience("OK");
            assertTrue(score < 0.5, "短输出应低分: " + score);
        }

        @Test
        void testPoliteTone() {
            double score = evaluator.evaluateUserExperience(
                    "Please consider this option. Thank you for reading. " +
                    "I would suggest this approach. Let me know if you need help.");
            assertTrue(score > 0.5, "礼貌语气应加分: " + score);
        }

        @Test
        void testNullOutputUX() {
            assertEquals(0.0, evaluator.evaluateUserExperience(null));
        }

        @Test
        void testBlankOutputUX() {
            assertEquals(0.0, evaluator.evaluateUserExperience("  "));
        }
    }

    @Nested
    @DisplayName("分数范围验证")
    class ScoreRanges {

        @Test
        void testAllScoresInValidRange() {
            String output = "The sky is blue and the weather is nice today";
            String expected = "The sky is blue";

            double acc = evaluator.evaluateAccuracy(output, expected);
            double comp = evaluator.evaluateCompleteness(output, "Describe the sky color");
            double safety = evaluator.evaluateSafety(output);
            double ux = evaluator.evaluateUserExperience(output);

            assertTrue(acc >= 0.0 && acc <= 1.0, "accuracy: " + acc);
            assertTrue(comp >= 0.0 && comp <= 1.0, "completeness: " + comp);
            assertTrue(safety >= 0.0 && safety <= 1.0, "safety: " + safety);
            assertTrue(ux >= 0.0 && ux <= 1.0, "ux: " + ux);
        }
    }
}
