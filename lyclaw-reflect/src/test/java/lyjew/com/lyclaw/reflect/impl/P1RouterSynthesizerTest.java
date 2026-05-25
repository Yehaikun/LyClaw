package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.impl.router.FixedIterRouter;
import lyjew.com.lyclaw.reflect.impl.router.LLMRouter;
import lyjew.com.lyclaw.reflect.impl.synthesizer.BestScoreSynthesizer;
import lyjew.com.lyclaw.reflect.impl.synthesizer.LastOutputSynthesizer;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Batch 9 — P1 Router/Synthesizer 扩展")
class P1RouterSynthesizerTest {

    // ── FixedIterRouter ──

    @Nested
    @DisplayName("FixedIterRouter")
    class FixedIterTests {
        @Test
        void retryBeforeFixedIterations() {
            FixedIterRouter router = new FixedIterRouter(3);
            Evaluation eval = new Evaluation();
            eval.setScore(1.0);
            eval.setSuccess(true);
            // 第1轮，设定3轮迭代 → 应该继续 RETRY
            assertEquals(RouteDecision.RETRY, router.route(new ReflectionContext(), eval, 1, 5));
        }

        @Test
        void stopAfterFixedIterations() {
            FixedIterRouter router = new FixedIterRouter(3);
            Evaluation eval = new Evaluation();
            eval.setScore(0.6);
            eval.setSuccess(false);
            // 第3轮达到目标 → STOP（即使评分不高）
            assertEquals(RouteDecision.STOP, router.route(new ReflectionContext(), eval, 3, 5));
        }

        @Test
        void fallbackWhenExceedsMax() {
            FixedIterRouter router = new FixedIterRouter(3);
            // iteration=6 超过 maxIterations=5
            assertEquals(RouteDecision.FALLBACK, router.route(new ReflectionContext(), null, 6, 5));
        }

        @Test
        void nullEvalAfterFixedIters() {
            FixedIterRouter router = new FixedIterRouter(2);
            // 达到固定轮次但 eval 为 null 且 iteration==maxIterations → FALLBACK
            assertEquals(RouteDecision.FALLBACK, router.route(new ReflectionContext(), null, 2, 2));
        }

        @Test
        void invalidConstructorRejected() {
            assertThrows(IllegalArgumentException.class, () -> new FixedIterRouter(0));
        }
    }

    // ── BestScoreSynthesizer ──

    @Nested
    @DisplayName("BestScoreSynthesizer")
    class BestScoreTests {
        @Test
        void picksHighestScore() {
            BestScoreSynthesizer synth = new BestScoreSynthesizer();
            Evaluation e1 = new Evaluation(); e1.setScore(0.6);
            Evaluation e2 = new Evaluation(); e2.setScore(0.9);
            Evaluation e3 = new Evaluation(); e3.setScore(0.7);

            String result = synth.synthesize(new ReflectionContext(),
                    List.of("bad output", "best output!", "ok output"),
                    List.of(e1, e2, e3));
            assertEquals("best output!", result);
        }

        @Test
        void emptyOutputs() {
            BestScoreSynthesizer synth = new BestScoreSynthesizer();
            assertEquals("", synth.synthesize(new ReflectionContext(), List.of(), List.of()));
        }

        @Test
        void nullEvaluationsFallsBackToLast() {
            BestScoreSynthesizer synth = new BestScoreSynthesizer();
            String result = synth.synthesize(new ReflectionContext(),
                    List.of("first", "second", "third"), null);
            assertEquals("third", result);
        }

        @Test
        void indexOutOfBoundsFallsBackToLast() {
            BestScoreSynthesizer synth = new BestScoreSynthesizer();
            // evaluations 比 outputs 多，bestIdx 指向不存在的输出
            Evaluation e1 = new Evaluation(); e1.setScore(0.9);
            String result = synth.synthesize(new ReflectionContext(),
                    List.of("only one"), List.of(e1));
            assertEquals("only one", result);
        }
    }

    // ── LastOutputSynthesizer ──

    @Nested
    @DisplayName("LastOutputSynthesizer")
    class LastOutputTests {
        @Test
        void returnsLastOutput() {
            LastOutputSynthesizer synth = new LastOutputSynthesizer();
            String result = synth.synthesize(new ReflectionContext(),
                    List.of("first round", "second round", "final round"), List.of());
            assertEquals("final round", result);
        }

        @Test
        void emptyOutputs() {
            LastOutputSynthesizer synth = new LastOutputSynthesizer();
            assertEquals("", synth.synthesize(new ReflectionContext(), List.of(), List.of()));
        }

        @Test
        void singleOutput() {
            LastOutputSynthesizer synth = new LastOutputSynthesizer();
            assertEquals("only", synth.synthesize(new ReflectionContext(), List.of("only"), List.of()));
        }
    }

    // ── LLMRouter (构造函数验证) ──

    @Test
    void llmRouterConstructible() {
        // 验证默认阈值 0.7 的构造函数
        LLMRouter router = new LLMRouter(null);
        // 阈值降级逻辑：null eval + iteration=1, max=3 → 未达上限 → RETRY
        RouteDecision decision = router.route(new ReflectionContext(), null, 1, 3);
        assertEquals(RouteDecision.RETRY, decision);
    }
}
