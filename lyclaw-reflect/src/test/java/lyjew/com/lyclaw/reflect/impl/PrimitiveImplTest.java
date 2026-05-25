package lyjew.com.lyclaw.reflect.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.reflect.impl.evaluator.HeuristicEvaluator;
import lyjew.com.lyclaw.reflect.impl.evaluator.LLMJudgeEvaluator;
import lyjew.com.lyclaw.reflect.impl.router.ThresholdRouter;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Batch 4 — Primitive Implementations")
class PrimitiveImplTest {

    // ── HeuristicEvaluator ──

    @Nested
    @DisplayName("HeuristicEvaluator")
    class HeuristicTests {
        HeuristicEvaluator evaluator = new HeuristicEvaluator();

        @Test
        void emptyOutput() {
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("");
            Evaluation eval = evaluator.evaluate(ctx);
            assertEquals(0.0, eval.getScore());
            assertFalse(eval.isSuccess());
        }

        @Test
        void perfectOutput() {
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("This is a well-structured response.\n\nIt addresses the user's question clearly and completely.\n\n- Point one: detailed explanation\n- Point two: another detailed explanation\n\n```\nexample code block\n```");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getScore() > 0.5);
        }

        @Test
        void errorMarkersPenalize() {
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("I'm sorry, an error occurred. The operation failed with an exception.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getScore() < 0.5, "score should be low due to error markers, got " + eval.getScore());
        }

        @Test
        void hallucinationMarkersPenalize() {
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("Research shows that this is definitely true. Experts agree without a doubt.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getIssues().stream().anyMatch(i -> i.getCategory().equals("hallucination_risk")));
        }

        @Test
        void contradictionDetected() {
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("The value shows an increase recently. However, the value shows a decrease lately.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getIssues().stream().anyMatch(i -> i.getCategory().equals("contradiction")));
        }

        @Test
        void tooShortPenalized() {
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("OK.");
            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getScore() < 0.5, "very short output should score low");
        }
    }

    // ── ThresholdRouter ──

    @Nested
    @DisplayName("ThresholdRouter")
    class ThresholdRouterTests {

        @Test
        void stopOnHighScore() {
            ThresholdRouter router = new ThresholdRouter(0.7);
            Evaluation eval = new Evaluation();
            eval.setScore(0.85);
            eval.setSuccess(true);
            assertEquals(RouteDecision.STOP, router.route(new ReflectionContext(), eval, 1, 3));
        }

        @Test
        void retryOnLowScore() {
            ThresholdRouter router = new ThresholdRouter(0.7);
            Evaluation eval = new Evaluation();
            eval.setScore(0.4);
            eval.setSuccess(false);
            assertEquals(RouteDecision.RETRY, router.route(new ReflectionContext(), eval, 1, 3));
        }

        @Test
        void fallbackWhenMaxIterationsReached() {
            ThresholdRouter router = new ThresholdRouter(0.7);
            Evaluation eval = new Evaluation();
            eval.setScore(0.4);
            eval.setSuccess(false);
            assertEquals(RouteDecision.FALLBACK, router.route(new ReflectionContext(), eval, 3, 3));
        }

        @Test
        void stopExactlyAtThreshold() {
            ThresholdRouter router = new ThresholdRouter(0.7);
            Evaluation eval = new Evaluation();
            eval.setScore(0.70);
            eval.setSuccess(true);
            assertEquals(RouteDecision.STOP, router.route(new ReflectionContext(), eval, 1, 5));
        }

        @Test
        void nullEvaluationRetries() {
            ThresholdRouter router = new ThresholdRouter(0.7);
            assertEquals(RouteDecision.RETRY, router.route(new ReflectionContext(), null, 1, 3));
        }

        @Test
        void nullEvaluationFallbackAtMax() {
            ThresholdRouter router = new ThresholdRouter(0.7);
            assertEquals(RouteDecision.FALLBACK, router.route(new ReflectionContext(), null, 3, 3));
        }
    }

    // ── LLMJudgeEvaluator ──

    @Nested
    @DisplayName("LLMJudgeEvaluator")
    class LLMJudgeTests {

        @Test
        void parsesValidJsonEvaluation() throws Exception {
            ChatFacade mockChat = mock(ChatFacade.class);
            ReActEngine mockReAct = mock(ReActEngine.class);
            ToolRegistry mockTools = mock(ToolRegistry.class);
            String jsonResponse = """
                    {
                      "score": 0.85,
                      "dimensions": {"relevance": 0.9, "correctness": 0.8, "completeness": 0.85, "clarity": 0.7},
                      "reasoning": "Good overall response with minor clarity issues",
                      "isSuccess": true,
                      "needsRetry": false,
                      "issues": [{"severity": "MINOR", "category": "clarity", "description": "Could be more concise"}]
                    }""";
            when(mockTools.getAllDefinitions()).thenReturn(List.of());
            when(mockReAct.execute(any(ChatFacade.class), any(ChatRequest.class), any(ToolExecutor.class)))
                    .thenReturn(jsonResponse);

            LLMJudgeEvaluator evaluator = new LLMJudgeEvaluator(mockChat, new ObjectMapper(), mockReAct, mockTools);
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("A good response to the query.");
            ctx.setUserMessage("Hello");

            Evaluation eval = evaluator.evaluate(ctx);
            assertEquals(0.85, eval.getScore(), 0.01);
            assertTrue(eval.isSuccess());
            assertEquals(1, eval.getIssues().size());
        }

        @Test
        void handlesJsonInMarkdownBlock() throws Exception {
            ChatFacade mockChat = mock(ChatFacade.class);
            ReActEngine mockReAct = mock(ReActEngine.class);
            ToolRegistry mockTools = mock(ToolRegistry.class);
            String jsonResponse = """
                    ```json
                    {"score": 0.6, "dimensions": {"relevance": 0.5}, "reasoning": "mediocre", "isSuccess": false, "needsRetry": true, "issues": []}
                    ```""";
            when(mockTools.getAllDefinitions()).thenReturn(List.of());
            when(mockReAct.execute(any(ChatFacade.class), any(ChatRequest.class), any(ToolExecutor.class)))
                    .thenReturn(jsonResponse);

            LLMJudgeEvaluator evaluator = new LLMJudgeEvaluator(mockChat, new ObjectMapper(), mockReAct, mockTools);
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("A mediocre response.");
            ctx.setUserMessage("Test");

            Evaluation eval = evaluator.evaluate(ctx);
            assertEquals(0.6, eval.getScore(), 0.01);
            assertFalse(eval.isSuccess());
            assertTrue(eval.isNeedsRetry());
        }

        @Test
        void fallbackOnLlmFailure() {
            ChatFacade mockChat = mock(ChatFacade.class);
            ReActEngine mockReAct = mock(ReActEngine.class);
            ToolRegistry mockTools = mock(ToolRegistry.class);
            when(mockTools.getAllDefinitions()).thenReturn(List.of());
            when(mockReAct.execute(any(ChatFacade.class), any(ChatRequest.class), any(ToolExecutor.class)))
                    .thenThrow(new RuntimeException("LLM unavailable"));

            LLMJudgeEvaluator evaluator = new LLMJudgeEvaluator(mockChat, new ObjectMapper(), mockReAct, mockTools);
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("A decent response that should get a reasonable heuristic score.");
            ctx.setUserMessage("Test");

            Evaluation eval = evaluator.evaluate(ctx);
            assertTrue(eval.getScore() > 0.0);
            assertTrue(eval.getReasoning().contains("启发式降级") || eval.getReasoning().contains("Heuristic"));
        }

        @Test
        void emptyOutputReturnsZero() {
            LLMJudgeEvaluator evaluator = new LLMJudgeEvaluator(mock(ChatFacade.class), new ObjectMapper(), null, null);
            ReflectionContext ctx = new ReflectionContext();
            ctx.setCurrentOutput("");
            Evaluation eval = evaluator.evaluate(ctx);
            assertEquals(0.0, eval.getScore());
            assertFalse(eval.isSuccess());
        }
    }
}
