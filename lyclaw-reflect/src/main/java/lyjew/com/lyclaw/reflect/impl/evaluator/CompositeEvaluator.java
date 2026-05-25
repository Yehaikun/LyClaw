package lyjew.com.lyclaw.reflect.impl.evaluator;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.Issue;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.Severity;
import lyjew.com.lyclaw.reflect.primitive.Evaluator;

import java.util.*;

/**
 * 加权组合评估器 — 聚合多个 Evaluator 的结果，按权重计算加权总分。
 *
 * <p>工作流程：
 * <ol>
 *   <li>依次调用每个子评估器，收集各自的 Evaluation</li>
 *   <li>按权重计算加权总分：{@code total = Σ(score_i × weight_i) / Σweight_i}</li>
 *   <li>汇总所有子评估器发现的问题列表</li>
 *   <li>总体成功判定：加权分 ≥ 0.7 且无 CRITICAL 级别问题</li>
 * </ol>
 *
 * <p>线程安全：子评估器按顺序串行调用（避免并发修改 ReflectionContext）。
 * 若子评估器数量与权重数量不匹配，多余者将被忽略，不足者权重默认为 1.0。
 */
public class CompositeEvaluator implements Evaluator {

    private final List<Evaluator> evaluators;
    private final List<Double> weights;
    private final String name;

    public CompositeEvaluator(List<Evaluator> evaluators, List<Double> weights) {
        this(evaluators, weights, "composite");
    }

    public CompositeEvaluator(List<Evaluator> evaluators, List<Double> weights, String name) {
        this.evaluators = new ArrayList<>(evaluators);
        this.weights = new ArrayList<>();
        for (int i = 0; i < evaluators.size(); i++) {
            this.weights.add(i < weights.size() ? weights.get(i) : 1.0);
        }
        this.name = name;
    }

    @Override
    public Evaluation evaluate(ReflectionContext ctx) {
        Evaluation composite = new Evaluation();
        composite.setDimensions(new LinkedHashMap<>());
        composite.setIssues(new ArrayList<>());

        if (evaluators.isEmpty()) {
            composite.setScore(0.5);
            composite.setSuccess(false);
            composite.setNeedsRetry(true);
            composite.setReasoning("组合评估器为空，返回默认分");
            return composite;
        }

        double totalWeighted = 0.0;
        double totalWeight = 0.0;
        int subCount = 0;

        for (int i = 0; i < evaluators.size(); i++) {
            Evaluator e = evaluators.get(i);
            double w = weights.get(i);
            try {
                Evaluation sub = e.evaluate(ctx);
                subCount++;
                totalWeighted += sub.getScore() * w;
                totalWeight += w;

                // 合并维度
                final int idx = subCount;
                sub.getDimensions().forEach((k, v) ->
                        composite.getDimensions().put(k + "_sub" + idx, v));
                // 合并问题
                if (sub.getIssues() != null) {
                    composite.getIssues().addAll(sub.getIssues());
                }
            } catch (Exception ex) {
                composite.getIssues().add(new Issue(Severity.MAJOR,
                        "evaluator_error", "子评估器 " + subCount + " 抛异常：" + ex.getMessage()));
            }
        }

        if (totalWeight == 0) {
            composite.setScore(0.0);
            composite.setSuccess(false);
            composite.setNeedsRetry(true);
            composite.setReasoning("所有子评估器权重为零");
            return composite;
        }

        double weightedScore = totalWeighted / totalWeight;
        weightedScore = Math.max(0.0, Math.min(1.0, weightedScore));

        // 存在 CRITICAL 问题时强制定为失败
        boolean hasCritical = composite.getIssues().stream()
                .anyMatch(i -> i.getSeverity() == Severity.CRITICAL);

        composite.setScore(weightedScore);
        composite.setSuccess(!hasCritical && weightedScore >= 0.7);
        composite.setNeedsRetry(!composite.isSuccess());
        composite.setReasoning(String.format("组合评估（%s）：%d 个子评估器，加权分 %.2f%s",
                name, subCount, weightedScore, hasCritical ? " [存在严重问题]" : ""));
        composite.getDimensions().put("composite", weightedScore);
        composite.getDimensions().put("subEvaluators", (double) subCount);

        return composite;
    }
}
