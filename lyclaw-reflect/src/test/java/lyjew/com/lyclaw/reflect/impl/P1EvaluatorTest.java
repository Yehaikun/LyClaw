package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.impl.evaluator.*;
import lyjew.com.lyclaw.reflect.model.*;
import lyjew.com.lyclaw.reflect.primitive.Evaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Batch 8 — P1 Evaluator 扩展")
class P1EvaluatorTest {

    // ── ToolVerifierEvaluator ──

    @Nested
    @DisplayName("ToolVerifierEvaluator — EXIT_CODE 模式")
    class ExitCodeTests {
        @Test
        void successExitCode() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.exitCode();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Build successful. Process finished with exit code 0");
            Evaluation eval = verifier.evaluate(ctx);
            assertTrue(eval.isSuccess());
            assertEquals(1.0, eval.getScore(), 0.01);
        }

        @Test
        void errorDetected() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.exitCode();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("error: compilation failed\nException in thread \"main\" java.lang.NullPointerException");
            Evaluation eval = verifier.evaluate(ctx);
            assertFalse(eval.isSuccess());
            assertTrue(eval.getScore() < 0.5);
        }

        @Test
        void noErrorAssumedSuccess() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.exitCode();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Hello World\nThis is a normal output.");
            Evaluation eval = verifier.evaluate(ctx);
            assertTrue(eval.isSuccess());
            assertEquals(0.85, eval.getScore(), 0.01);
        }
    }

    @Nested
    @DisplayName("ToolVerifierEvaluator — TEST_SUITE 模式")
    class TestSuiteTests {
        @Test
        void junitOutput() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.testSuite();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Tests run: 10, Failures: 2, Errors: 0, Skipped: 1");
            Evaluation eval = verifier.evaluate(ctx);
            assertEquals(10, eval.getTestCount());
            assertEquals(8, eval.getPassCount());
            assertEquals(0.8, eval.getScore(), 0.01);
        }

        @Test
        void pytestOutput() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.testSuite();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("========================= test session starts =========================\n5 passed, 1 failed in 2.34s");
            Evaluation eval = verifier.evaluate(ctx);
            assertEquals(5, eval.getTestCount());
            // total=5 (group1), failed=1 (group2) → passRate=4/5=0.8 → success (≥0.8)
            assertEquals(4, eval.getPassCount());
            assertTrue(eval.isSuccess());
            assertEquals(0.8, eval.getScore(), 0.01);
        }

        @Test
        void allPassing() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.testSuite();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Tests run: 8, Failures: 0, Errors: 0, Skipped: 0");
            Evaluation eval = verifier.evaluate(ctx);
            assertTrue(eval.isSuccess());
            assertEquals(1.0, eval.getScore(), 0.01);
            assertEquals(8, eval.getPassCount());
        }
    }

    @Nested
    @DisplayName("ToolVerifierEvaluator — OUTPUT_DIFF 模式")
    class OutputDiffTests {
        @Test
        void identicalStrings() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.outputDiff("hello world");
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("hello world");
            Evaluation eval = verifier.evaluate(ctx);
            assertTrue(eval.isSuccess());
            assertEquals(1.0, eval.getScore(), 0.01);
        }

        @Test
        void similarStrings() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.outputDiff("hello world");
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("hello world!");
            Evaluation eval = verifier.evaluate(ctx);
            assertTrue(eval.getScore() > 0.8); // 1 char diff should be high similarity
        }

        @Test
        void completelyDifferentStrings() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.outputDiff("expected output here");
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("completely different text with no shared words at all");
            Evaluation eval = verifier.evaluate(ctx);
            assertTrue(eval.getScore() < 0.5);
            assertFalse(eval.isSuccess());
        }
    }

    @Nested
    @DisplayName("ToolVerifierEvaluator — CUSTOM_SCRIPT 模式")
    class CustomScriptTests {
        @Test
        void customPassing() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.customScript(
                    output -> output != null && output.contains("SUCCESS"));
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Task completed: SUCCESS");
            Evaluation eval = verifier.evaluate(ctx);
            assertTrue(eval.isSuccess());
            assertEquals(1.0, eval.getScore(), 0.01);
        }

        @Test
        void customFailing() {
            ToolVerifierEvaluator verifier = ToolVerifierEvaluator.customScript(
                    output -> output != null && output.contains("SUCCESS"));
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Task completed: FAILED");
            Evaluation eval = verifier.evaluate(ctx);
            assertFalse(eval.isSuccess());
            assertEquals(0.0, eval.getScore(), 0.01);
        }
    }

    // ── CompositeEvaluator ──

    @Nested
    @DisplayName("CompositeEvaluator")
    class CompositeTests {
        @Test
        void weightedCombination() {
            // 两个子评估器：一个满分，一个零分，权重 3:1 → 期望 0.75
            Evaluator good = ctx -> {
                Evaluation e = new Evaluation();
                e.setScore(1.0);
                e.setSuccess(true);
                e.setIssues(List.of());
                return e;
            };
            Evaluator bad = ctx -> {
                Evaluation e = new Evaluation();
                e.setScore(0.0);
                e.setSuccess(false);
                e.setIssues(List.of());
                return e;
            };

            CompositeEvaluator composite = new CompositeEvaluator(
                    List.of(good, bad), List.of(3.0, 1.0));
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("test");

            Evaluation eval = composite.evaluate(ctx);
            assertEquals(0.75, eval.getScore(), 0.01);
        }

        @Test
        void emptyEvaluators() {
            CompositeEvaluator composite = new CompositeEvaluator(List.of(), List.of());
            Evaluation eval = composite.evaluate(new ReflectionContext());
            assertEquals(0.5, eval.getScore(), 0.01);
            assertFalse(eval.isSuccess());
        }

        @Test
        void criticalIssueMarksFailure() {
            Evaluator critical = ctx -> {
                Evaluation e = new Evaluation();
                e.setScore(0.9);
                e.setSuccess(true);
                e.setIssues(List.of(new Issue(Severity.CRITICAL, "security", "严重安全漏洞")));
                return e;
            };
            CompositeEvaluator composite = new CompositeEvaluator(List.of(critical), List.of(1.0));
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("test");

            Evaluation eval = composite.evaluate(ctx);
            assertFalse(eval.isSuccess()); // CRITICAL issue forces failure
        }

        @Test
        void mergesIssues() {
            Evaluator a = ctx -> {
                Evaluation e = new Evaluation();
                e.setScore(1.0);
                e.setIssues(List.of(new Issue(Severity.MINOR, "style", "代码风格问题")));
                return e;
            };
            Evaluator b = ctx -> {
                Evaluation e = new Evaluation();
                e.setScore(1.0);
                e.setIssues(List.of(new Issue(Severity.MAJOR, "perf", "性能瓶颈")));
                return e;
            };
            CompositeEvaluator composite = new CompositeEvaluator(List.of(a, b), List.of(1.0, 1.0));
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("test");

            Evaluation eval = composite.evaluate(ctx);
            assertEquals(2, eval.getIssues().size());
        }
    }

    // ── ImportanceEvaluator ──

    @Nested
    @DisplayName("ImportanceEvaluator")
    class ImportanceTests {
        @Test
        void securityContentHighImportance() {
            ImportanceEvaluator evaluator = new ImportanceEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("The authentication system uses password hashing and token-based access control for authorization.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getImportanceScore() >= 0.3);
            assertTrue(eval.getCategory().contains("安全"));
        }

        @Test
        void businessCriticalHighImportance() {
            ImportanceEvaluator evaluator = new ImportanceEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("The payment processing system handles billing and invoice generation for all financial transactions.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getImportanceScore() >= 0.3);
            assertTrue(eval.getCategory().contains("业务关键"));
        }

        @Test
        void regularContentLowImportance() {
            ImportanceEvaluator evaluator = new ImportanceEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Hello! How can I help you today?");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getImportanceScore() < 0.2);
        }

        @Test
        void emptyContent() {
            ImportanceEvaluator evaluator = new ImportanceEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("");
            Evaluation eval = evaluator.evaluate(ctx);
            assertEquals(0.0, eval.getImportanceScore(), 0.01);
            assertEquals("空内容", eval.getCategory());
        }
    }

    // ── ConsistencyEvaluator ──

    @Nested
    @DisplayName("ConsistencyEvaluator")
    class ConsistencyTests {
        @Test
        void consistentOutput() {
            ConsistencyEvaluator evaluator = new ConsistencyEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("The library uses synchronous HTTP calls. It blocks the calling thread until a response is received. The timeout is set to 30 seconds.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.isConsistent());
            assertEquals(1.0, eval.getScore(), 0.01);
        }

        @Test
        void contradictoryOutput() {
            ConsistencyEvaluator evaluator = new ConsistencyEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("The API is synchronous and blocks the thread. The API is asynchronous and non-blocking for better scalability.");
            Evaluation eval = evaluator.evaluate(ctx);
            // 应该检测到 synchronous vs asynchronous 的矛盾
            assertTrue(eval.getScore() < 0.9, "expected contradiction penalty, got score=" + eval.getScore());
            assertFalse(eval.getInconsistencies().isEmpty());
        }

        @Test
        void polarityFlip() {
            ConsistencyEvaluator evaluator = new ConsistencyEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("This feature is supported in the current version. This feature is unsupported in the latest release.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertFalse(eval.getInconsistencies().isEmpty());
            assertTrue(eval.getScore() < 1.0);
        }

        @Test
        void emptyOutput() {
            ConsistencyEvaluator evaluator = new ConsistencyEvaluator();
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.isConsistent());
            assertEquals(1.0, eval.getScore(), 0.01);
        }
    }

    // ── Levenshtein 距离 ──

    @Test
    void levenshteinExactMatch() {
        assertEquals(1.0, ToolVerifierEvaluator.normalizedLevenshtein("abc", "abc"), 0.001);
    }

    @Test
    void levenshteinCompletelyDifferent() {
        assertEquals(0.0, ToolVerifierEvaluator.normalizedLevenshtein("abc", "xyz"), 0.001);
    }

    @Test
    void levenshteinOneEdit() {
        double sim = ToolVerifierEvaluator.normalizedLevenshtein("hello", "helloo");
        assertTrue(sim > 0.8, "one extra char should be high similarity");
    }
}
