package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.model.*;
import lyjew.com.lyclaw.reflect.primitive.*;
import lyjew.com.lyclaw.reflect.registry.PrimitiveFactory;
import lyjew.com.lyclaw.reflect.topology.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TopologyExecutor")
class TopologyExecutorTest {

    private PrimitiveFactory factory;
    private TopologyExecutor executor;

    @BeforeEach
    void setUp() {
        factory = new PrimitiveFactory();
        executor = new TopologyExecutor(factory);
    }

    // Type-safe register helpers to work around ReflectionPrimitive marker interface
    private void regActor(String name, Actor a) { factory.register(PrimitiveType.ACTOR, name, a); }
    private void regEvaluator(String name, Evaluator e) { factory.register(PrimitiveType.EVALUATOR, name, e); }
    private void regReflector(String name, Reflector r) { factory.register(PrimitiveType.REFLECTOR, name, r); }
    private void regRouter(String name, Router r) { factory.register(PrimitiveType.ROUTER, name, r); }
    private void regSynth(String name, Synthesizer s) { factory.register(PrimitiveType.SYNTHESIZER, name, s); }

    @Nested
    @DisplayName("Passthrough topology: Actor → exit")
    class Passthrough {

        @Test
        void executesSingleActor() {
            regActor("simple", ctx -> new ActorResult("Hello, World!"));

            regSynth("passthrough", (ctx, outputs, evals) ->
                    outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1));

            ReflectionTopology topology = ReflectionTopology.builder()
                    .name("passthrough")
                    .actor("simple")
                    .node("exit", PrimitiveType.SYNTHESIZER, "passthrough")
                    .edge("actor-0", "exit", EdgeCondition.ALWAYS)
                    .entryNode("actor-0")
                    .exitNode("exit")
                    .build();

            ReflectionContext ctx = new ReflectionContext();
            ctx.setUserMessage("Hi");
            ctx.setSystemPrompt("Be helpful");

            ExecutionResult result = executor.execute(topology, ctx);

            assertEquals("Hello, World!", result.getFinalOutput());
            assertEquals(1, result.getTotalIterations());
            assertTrue(result.getTotalDurationMs() >= 0);
        }
    }

    @Nested
    @DisplayName("Full Reflexion loop: Actor→Evaluator→Router→Reflector→Actor")
    class Reflexion {

        @Test
        void stopsOnHighScoreAfterOneIteration() {
            regActor("react", ctx -> new ActorResult("Response to: " + ctx.getUserMessage()));

            regEvaluator("llmJudge", ctx -> {
                Evaluation eval = new Evaluation();
                eval.setScore(0.85);
                eval.setSuccess(true);
                eval.setNeedsRetry(false);
                eval.setReasoning("Good response");
                return eval;
            });

            regRouter("threshold", new lyjew.com.lyclaw.reflect.impl.router.ThresholdRouter(0.7));
            regReflector("verbal", (ctx, eval) -> "Should not be called");
            regSynth("last", (ctx, outputs, evals) ->
                    outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1));

            ReflectionTopology topology = ReflectionTopology.builder()
                    .name("reflexion")
                    .actor("react").evaluator("llmJudge").router("threshold")
                    .reflector("verbal").synthesizer("last")
                    .edge("actor-0", "evaluator-0", EdgeCondition.ALWAYS)
                    .edge("evaluator-0", "router-0", EdgeCondition.ALWAYS)
                    .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                    .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                    .edge("reflector-0", "actor-0", EdgeCondition.ALWAYS)
                    .entryNode("actor-0").exitNode("synthesizer-0")
                    .maxIterations(3)
                    .build();

            ReflectionContext ctx = new ReflectionContext();
            ctx.setUserMessage("What is Java?");
            ctx.setSystemPrompt("Be a helpful assistant");

            ExecutionResult result = executor.execute(topology, ctx);

            assertEquals("Response to: What is Java?", result.getFinalOutput());
            assertEquals(1, result.getTotalIterations());
            assertEquals(0.85, ctx.getLastScore(), 0.01);
            assertTrue(ctx.isLastEvalSuccess());
        }

        @Test
        void retriesOnLowScoreThenStops() {
            final int[] actorCalls = {0};
            final int[] evalCalls = {0};
            final int[] reflectorCalls = {0};

            regActor("react", ctx -> {
                actorCalls[0]++;
                return new ActorResult(actorCalls[0] == 1 ? "Bad response" : "Good response after reflection");
            });

            regEvaluator("llmJudge", ctx -> {
                evalCalls[0]++;
                Evaluation eval = new Evaluation();
                if (evalCalls[0] == 1) {
                    eval.setScore(0.4);
                    eval.setSuccess(false);
                    eval.setNeedsRetry(true);
                    eval.setReasoning("Poor response");
                    eval.getIssues().add(new Issue(Severity.MAJOR, "quality", "Too vague"));
                } else {
                    eval.setScore(0.85);
                    eval.setSuccess(true);
                    eval.setNeedsRetry(false);
                    eval.setReasoning("Much better after reflection");
                }
                return eval;
            });

            regRouter("threshold", new lyjew.com.lyclaw.reflect.impl.router.ThresholdRouter(0.7));

            regReflector("verbal", (ctx, eval) -> {
                reflectorCalls[0]++;
                return "The response was too vague. Be more specific.";
            });

            regSynth("last", (ctx, outputs, evals) ->
                    outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1));

            ReflectionTopology topology = ReflectionTopology.builder()
                    .name("reflexion")
                    .actor("react").evaluator("llmJudge").router("threshold")
                    .reflector("verbal").synthesizer("last")
                    .edge("actor-0", "evaluator-0", EdgeCondition.ALWAYS)
                    .edge("evaluator-0", "router-0", EdgeCondition.ALWAYS)
                    .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                    .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                    .edge("reflector-0", "actor-0", EdgeCondition.ALWAYS)
                    .entryNode("actor-0").exitNode("synthesizer-0")
                    .maxIterations(3)
                    .build();

            ReflectionContext ctx = new ReflectionContext();
            ctx.setUserMessage("Explain quantum computing");
            ctx.setSystemPrompt("Be thorough");

            ExecutionResult result = executor.execute(topology, ctx);

            assertEquals("Good response after reflection", result.getFinalOutput());
            assertEquals(2, actorCalls[0], "Actor should be called twice");
            assertEquals(2, evalCalls[0], "Evaluator should be called twice");
            assertEquals(1, reflectorCalls[0], "Reflector should be called once");
            assertEquals(2, result.getTotalIterations(), "Should have 1 retry iteration (iteration starts at 1)");
            assertEquals(2, result.getScores().size());
            assertEquals(0.85, ctx.getLastScore(), 0.01);
        }

        @Test
        void enforcesMaxIterations() {
            final int[] actorCalls = {0};

            regActor("react", ctx -> {
                actorCalls[0]++;
                return new ActorResult("Still bad " + actorCalls[0]);
            });

            regEvaluator("llmJudge", ctx -> {
                Evaluation eval = new Evaluation();
                eval.setScore(0.3);
                eval.setSuccess(false);
                eval.setNeedsRetry(true);
                eval.setReasoning("Still not good enough");
                return eval;
            });

            regRouter("threshold", new lyjew.com.lyclaw.reflect.impl.router.ThresholdRouter(0.7));
            regReflector("verbal", (ctx, eval) -> "Try harder");
            regSynth("last", (ctx, outputs, evals) ->
                    outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1));

            ReflectionTopology topology = ReflectionTopology.builder()
                    .name("reflexion-limited")
                    .actor("react").evaluator("llmJudge").router("threshold")
                    .reflector("verbal").synthesizer("last")
                    .edge("actor-0", "evaluator-0", EdgeCondition.ALWAYS)
                    .edge("evaluator-0", "router-0", EdgeCondition.ALWAYS)
                    .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                    .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                    .edge("reflector-0", "actor-0", EdgeCondition.ALWAYS)
                    .entryNode("actor-0").exitNode("synthesizer-0")
                    .maxIterations(2)
                    .build();

            ReflectionContext ctx = new ReflectionContext();
            ctx.setUserMessage("Test");
            ctx.setSystemPrompt("Test");

            ExecutionResult result = executor.execute(topology, ctx);

            assertTrue(actorCalls[0] <= 4, "Actor calls should be bounded by maxIterations");
            assertTrue(result.getTotalIterations() <= 2);
        }
    }

    @Nested
    @DisplayName("Self-Refine topology")
    class SelfRefine {

        @Test
        void selfRefineLoop() {
            final int[] attempts = {0};

            regActor("simple", ctx -> {
                attempts[0]++;
                if (attempts[0] == 1) return new ActorResult("draft v1");
                if (attempts[0] == 2) return new ActorResult("draft v2 improved");
                return new ActorResult("final v3");
            });

            regEvaluator("heuristic", ctx -> {
                Evaluation eval = new Evaluation();
                String output = ctx.getCurrentOutput();
                if (output != null && output.contains("final")) {
                    eval.setScore(0.9); eval.setSuccess(true);
                } else if (output != null && output.contains("improved")) {
                    eval.setScore(0.6); eval.setSuccess(false); eval.setNeedsRetry(true);
                } else {
                    eval.setScore(0.3); eval.setSuccess(false); eval.setNeedsRetry(true);
                }
                return eval;
            });

            regRouter("threshold", new lyjew.com.lyclaw.reflect.impl.router.ThresholdRouter(0.7));
            regReflector("verbal", (ctx, eval) -> "Needs more detail and precision");
            regSynth("best", (ctx, outputs, evals) -> {
                List<String> outs = ctx.getOutputs();
                return outs.isEmpty() ? "" : outs.get(outs.size() - 1);
            });

            ReflectionTopology topology = ReflectionTopology.builder()
                    .name("self-refine")
                    .actor("simple").evaluator("heuristic").router("threshold")
                    .reflector("verbal").synthesizer("best")
                    .edge("actor-0", "evaluator-0", EdgeCondition.ALWAYS)
                    .edge("evaluator-0", "router-0", EdgeCondition.ALWAYS)
                    .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                    .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                    .edge("reflector-0", "actor-0", EdgeCondition.ALWAYS)
                    .entryNode("actor-0").exitNode("synthesizer-0")
                    .maxIterations(5)
                    .build();

            ReflectionContext ctx = new ReflectionContext();
            ctx.setUserMessage("Write about AI");
            ctx.setSystemPrompt("You are a writer");

            ExecutionResult result = executor.execute(topology, ctx);

            assertEquals(3, attempts[0], "Should take 3 attempts to reach final");
            assertEquals("final v3", result.getFinalOutput());
        }
    }
}
