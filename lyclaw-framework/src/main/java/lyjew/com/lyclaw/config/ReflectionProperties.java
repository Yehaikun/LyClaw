package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Reflection framework configuration properties under {@code lyclaw.reflection}.
 *
 * <p>Centralizes all previously hardcoded thresholds, temperatures, timeouts,
 * and truncation limits. Each sub-group can be overridden in {@code application.yml}.</p>
 */
@ConfigurationProperties(prefix = "lyclaw.reflection")
public class ReflectionProperties {

    private final Evaluator evaluator = new Evaluator();
    private final Temperature temperature = new Temperature();
    private final Timeout timeout = new Timeout();
    private final Truncation truncation = new Truncation();
    private final Router router = new Router();

    // ── Evaluator ──
    public static class Evaluator {
        private double defaultScore = 0.65;
        private double successThreshold = 0.65;
        private double errorPenalty = 0.1;
        private double hallucinationPenalty = 0.08;
        private double contradictionPenalty = 0.15;
        private int minChars = 20;
        private int maxChars = 10000;
        private double shortPenalty = 0.35;
        private double somewhatShortPenalty = 0.15;
        private double longPenalty = 0.1;
        private double structureCodeReward = 0.1;
        private double structureListReward = 0.05;
        private double structureParagraphReward = 0.05;
        private double intentMatchBonus = 0.1;

        public double getDefaultScore() { return defaultScore; }
        public void setDefaultScore(double v) { this.defaultScore = v; }
        public double getSuccessThreshold() { return successThreshold; }
        public void setSuccessThreshold(double v) { this.successThreshold = v; }
        public double getErrorPenalty() { return errorPenalty; }
        public void setErrorPenalty(double v) { this.errorPenalty = v; }
        public double getHallucinationPenalty() { return hallucinationPenalty; }
        public void setHallucinationPenalty(double v) { this.hallucinationPenalty = v; }
        public double getContradictionPenalty() { return contradictionPenalty; }
        public void setContradictionPenalty(double v) { this.contradictionPenalty = v; }
        public int getMinChars() { return minChars; }
        public void setMinChars(int v) { this.minChars = v; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int v) { this.maxChars = v; }
        public double getShortPenalty() { return shortPenalty; }
        public void setShortPenalty(double v) { this.shortPenalty = v; }
        public double getSomewhatShortPenalty() { return somewhatShortPenalty; }
        public void setSomewhatShortPenalty(double v) { this.somewhatShortPenalty = v; }
        public double getLongPenalty() { return longPenalty; }
        public void setLongPenalty(double v) { this.longPenalty = v; }
        public double getStructureCodeReward() { return structureCodeReward; }
        public void setStructureCodeReward(double v) { this.structureCodeReward = v; }
        public double getStructureListReward() { return structureListReward; }
        public void setStructureListReward(double v) { this.structureListReward = v; }
        public double getStructureParagraphReward() { return structureParagraphReward; }
        public void setStructureParagraphReward(double v) { this.structureParagraphReward = v; }
        public double getIntentMatchBonus() { return intentMatchBonus; }
        public void setIntentMatchBonus(double v) { this.intentMatchBonus = v; }
    }

    // ── Temperature ──
    public static class Temperature {
        private double evaluator = 0.1;
        private double reflector = 0.3;
        private double router = 0.2;
        private double synthesizer = 0.3;

        public double getEvaluator() { return evaluator; }
        public void setEvaluator(double v) { this.evaluator = v; }
        public double getReflector() { return reflector; }
        public void setReflector(double v) { this.reflector = v; }
        public double getRouter() { return router; }
        public void setRouter(double v) { this.router = v; }
        public double getSynthesizer() { return synthesizer; }
        public void setSynthesizer(double v) { this.synthesizer = v; }
    }

    // ── Timeout ──
    public static class Timeout {
        private Duration actor = Duration.ofMinutes(5);
        private Duration evaluator = Duration.ofMinutes(2);
        private Duration reflector = Duration.ofMinutes(2);
        private Duration router = Duration.ofMinutes(1);
        private Duration synthesizer = Duration.ofMinutes(2);

        public Duration getActor() { return actor; }
        public void setActor(Duration v) { this.actor = v; }
        public Duration getEvaluator() { return evaluator; }
        public void setEvaluator(Duration v) { this.evaluator = v; }
        public Duration getReflector() { return reflector; }
        public void setReflector(Duration v) { this.reflector = v; }
        public Duration getRouter() { return router; }
        public void setRouter(Duration v) { this.router = v; }
        public Duration getSynthesizer() { return synthesizer; }
        public void setSynthesizer(Duration v) { this.synthesizer = v; }
    }

    // ── Truncation ──
    public static class Truncation {
        private int actorOutput = 4000;
        private int routerContext = 500;
        private int routerOutput = 1000;
        private int routerReflection = 800;
        private int synthesisOutput = 2000;

        public int getActorOutput() { return actorOutput; }
        public void setActorOutput(int v) { this.actorOutput = v; }
        public int getRouterContext() { return routerContext; }
        public void setRouterContext(int v) { this.routerContext = v; }
        public int getRouterOutput() { return routerOutput; }
        public void setRouterOutput(int v) { this.routerOutput = v; }
        public int getRouterReflection() { return routerReflection; }
        public void setRouterReflection(int v) { this.routerReflection = v; }
        public int getSynthesisOutput() { return synthesisOutput; }
        public void setSynthesisOutput(int v) { this.synthesisOutput = v; }
    }

    // ── Router ──
    public static class Router {
        private double thresholdDefault = 0.7;
        private double llmEarlyStopScore = 0.85;
        private int fixedIterDefault = 3;

        public double getThresholdDefault() { return thresholdDefault; }
        public void setThresholdDefault(double v) { this.thresholdDefault = v; }
        public double getLlmEarlyStopScore() { return llmEarlyStopScore; }
        public void setLlmEarlyStopScore(double v) { this.llmEarlyStopScore = v; }
        public int getFixedIterDefault() { return fixedIterDefault; }
        public void setFixedIterDefault(int v) { this.fixedIterDefault = v; }
    }

    // ── top-level getters ──
    public Evaluator getEvaluator() { return evaluator; }
    public Temperature getTemperature() { return temperature; }
    public Timeout getTimeout() { return timeout; }
    public Truncation getTruncation() { return truncation; }
    public Router getRouter() { return router; }
}
