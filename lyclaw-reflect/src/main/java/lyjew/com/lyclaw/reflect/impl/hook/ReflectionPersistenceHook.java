package lyjew.com.lyclaw.reflect.impl.hook;

import static lyjew.com.lyclaw.react.ContextKeys.REFLECTION_SUMMARY;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.reflect.topology.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import lyjew.com.lyclaw.common.StringUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反思数据持久化钩子 — 在拓扑执行生命周期中收集 Actor输出、评估分数、路由决策和反思文本，
 * 在拓扑结束后汇总写入 ReflectionContext.attributes["reflectionSummary"]，
 * 供 ReflectionTopologyStage 取出并通过 SSE 推送和 Session 持久化。
 */
public class ReflectionPersistenceHook implements MemoryHook {

    private static final Logger log = LoggerFactory.getLogger(ReflectionPersistenceHook.class);

    /** @deprecated 使用 {@link lyjew.com.lyclaw.react.ContextKeys#REFLECTION_SUMMARY} */
    @Deprecated
    private static final String ATTR_KEY = REFLECTION_SUMMARY;

    @Override
    public void onActorAfter(ReflectionContext ctx, String actorName, String output) {
        appendActor(ctx, actorName, output);
    }

    @Override
    public void onEvaluatorAfter(ReflectionContext ctx, Evaluation evaluation) {
        appendEvaluation(ctx, evaluation);
    }

    @Override
    public void onRouterAfter(ReflectionContext ctx, RouteDecision decision, int iteration) {
        appendDecision(ctx, decision, iteration);
    }

    @Override
    public void onReflectorAfter(ReflectionContext ctx, String reflection) {
        appendReflection(ctx, reflection);
    }

    @Override
    public void onTopologyEnd(ReflectionContext ctx, ExecutionResult result) {
        Map<String, Object> summary = getOrCreateSummary(ctx);
        summary.put("topologyCompleted", true);
        summary.put("totalIterations", result.getTotalIterations());
        summary.put("totalDurationMs", result.getTotalDurationMs());
        if (result.getScores() != null && !result.getScores().isEmpty()) {
            summary.put("finalScores", result.getScores());
        }
        log.debug("Reflection summary persisted: {} iterations, {} steps",
                result.getTotalIterations(), getStepCount(summary));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateSummary(ReflectionContext ctx) {
        Object existing = ctx.getAttribute(ATTR_KEY);
        if (existing instanceof Map) return (Map<String, Object>) existing;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topologyName", "");
        summary.put("actors", new ArrayList<>());
        summary.put("evaluations", new ArrayList<>());
        summary.put("decisions", new ArrayList<>());
        summary.put("reflections", new ArrayList<>());
        ctx.setAttribute(ATTR_KEY, summary);
        return summary;
    }

    @SuppressWarnings("unchecked")
    private void appendActor(ReflectionContext ctx, String actorName, String output) {
        Map<String, Object> summary = getOrCreateSummary(ctx);
        List<Map<String, Object>> actors = (List<Map<String, Object>>) summary.get("actors");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("actorName", actorName != null ? actorName : "unknown");
        entry.put("outputPreview", StringUtils.truncate(output, 500));
        entry.put("iteration", ctx.getIteration());
        actors.add(entry);
    }

    @SuppressWarnings("unchecked")
    private void appendEvaluation(ReflectionContext ctx, Evaluation evaluation) {
        if (evaluation == null) return;
        Map<String, Object> summary = getOrCreateSummary(ctx);
        List<Map<String, Object>> evals = (List<Map<String, Object>>) summary.get("evaluations");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("score", evaluation.getScore());
        entry.put("success", evaluation.isSuccess());
        entry.put("reasoning", evaluation.getReasoning());
        entry.put("iteration", ctx.getIteration());
        if (evaluation.getIssues() != null && !evaluation.getIssues().isEmpty()) {
            List<String> issueDescs = evaluation.getIssues().stream()
                    .map(i -> "[" + i.getSeverity() + "] " + i.getDescription())
                    .toList();
            entry.put("issues", issueDescs);
        }
        evals.add(entry);
    }

    @SuppressWarnings("unchecked")
    private void appendDecision(ReflectionContext ctx, RouteDecision decision, int iteration) {
        Map<String, Object> summary = getOrCreateSummary(ctx);
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) summary.get("decisions");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("decision", decision.name());
        entry.put("iteration", iteration);
        decisions.add(entry);
    }

    @SuppressWarnings("unchecked")
    private void appendReflection(ReflectionContext ctx, String reflection) {
        Map<String, Object> summary = getOrCreateSummary(ctx);
        List<String> reflections = (List<String>) summary.get("reflections");
        reflections.add(StringUtils.truncate(reflection, 800));
    }

    private int getStepCount(Map<String, Object> summary) {
        int count = 0;
        for (String key : new String[]{"actors", "evaluations", "decisions", "reflections"}) {
            Object v = summary.get(key);
            if (v instanceof List) count += ((List<?>) v).size();
        }
        return count;
    }

}
