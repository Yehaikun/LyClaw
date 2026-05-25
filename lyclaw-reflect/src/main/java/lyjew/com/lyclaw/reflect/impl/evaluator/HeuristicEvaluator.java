package lyjew.com.lyclaw.reflect.impl.evaluator;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.Issue;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.Severity;
import lyjew.com.lyclaw.reflect.primitive.Evaluator;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.*;

/**
 * 纯规则驱动的输出评估器 — 不依赖 LLM 调用，适合作为低延迟兜底或轻量评估场景。
 *
 * <p>检测规则：
 * <ol>
 *   <li><b>错误标记</b> — 输出中包含 "error"、"exception"、"failed" 等术语，每发现一个扣 0.15</li>
 *   <li><b>虚假断言标记</b> — 识别 "research shows"、"definitely" 等无证据支撑的断言模式</li>
 *   <li><b>长度检测</b> — 过短（&lt;30字符）严重扣分，过长（&gt;10000字符）轻微扣分</li>
 *   <li><b>逻辑矛盾</b> — 检查 increase/decrease、always/never 等对立词对是否在不同句子中同时出现</li>
 *   <li><b>结构奖励</b> — 包含代码块、列表、段落分隔则加分</li>
 * </ol>
 *
 * <p>起始分 0.65，经各项加减后钳位到 [0.0, 1.0]。分数 ≥0.65 视为成功。
 */
@Primitive(type = PrimitiveType.EVALUATOR, name = "heuristic", isDefault = true)
public class HeuristicEvaluator implements Evaluator {

    private static final List<String> ERROR_MARKERS = List.of(
            "error", "exception", "failed", "cannot", "unable to",
            "not possible", "syntax error", "runtime error", "stack trace"
    );

    private static final List<String> HALLUCINATION_MARKERS = List.of(
            "research shows", "it is well known", "without a doubt",
            "experts agree", "it has been demonstrated", "studies confirm",
            "definitely", "absolutely", "guaranteed", "without exception",
            "undoubtedly", "certainly true"
    );

    /** 对立词对，用于检测逻辑矛盾 */
    private static final List<String[]> CONTRADICTION_PAIRS = List.of(
            new String[]{"increase", "decrease"},
            new String[]{"always", "never"},
            new String[]{"true", "false"},
            new String[]{"recommend", "avoid"},
            new String[]{"good", "bad"},
            new String[]{"correct", "incorrect"},
            new String[]{"open", "closed"},
            new String[]{"safe", "dangerous"}
    );

    @Override
    public Evaluation evaluate(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        Evaluation eval = new Evaluation();
        eval.setRawOutput(output);
        eval.setDimensions(new LinkedHashMap<>());

        if (output == null || output.isBlank()) {
            eval.setScore(0.0);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("输出为空或纯空白");
            eval.getIssues().add(new Issue(Severity.CRITICAL, "output", "输出为空"));
            return eval;
        }

        String lower = output.toLowerCase();
        List<Issue> issues = new ArrayList<>();
        double score = 0.65;

        // 提取非代码文本（代码块中的error不算错误）
        String textOutsideCode = stripCodeBlocks(output).toLowerCase();

        // 检测错误标记（仅检查非代码文本）
        long errorCount = ERROR_MARKERS.stream().filter(textOutsideCode::contains).count();
        if (errorCount > 0) {
            score -= 0.1 * Math.min(errorCount, 3);
            issues.add(new Issue(Severity.MAJOR, "errors",
                    "输出包含 " + errorCount + " 个错误指示词"));
        }

        // 检测虚假断言标记
        long halluCount = HALLUCINATION_MARKERS.stream().filter(lower::contains).count();
        if (halluCount > 0) {
            score -= 0.08 * Math.min(halluCount, 3);
            issues.add(new Issue(Severity.MINOR, "hallucination_risk",
                    "输出包含 " + halluCount + " 个无证据断言标记"));
        }

        // 长度检测
        int len = output.length();
        if (len < 20) {
            score -= 0.35;
            issues.add(new Issue(Severity.MAJOR, "length", "输出过短（" + len + " 字符）"));
        } else if (len < 50) {
            score -= 0.15;
            issues.add(new Issue(Severity.MINOR, "length", "输出偏短（" + len + " 字符）"));
        } else if (len > 10000) {
            score -= 0.1;
            issues.add(new Issue(Severity.MINOR, "length", "输出过长（" + len + " 字符）"));
        }

        // 简单问题意图匹配：用户消息短且输出相关性
        String userMsg = ctx.getUserMessage();
        if (userMsg != null && userMsg.length() < 20 && len > 30 && errorCount == 0) {
            score += 0.1;
        }

        // 逻辑矛盾检测：对立词对出现在不同句子中
        String[] sentences = output.split("[.!?]\\s+");
        if (sentences.length >= 2) {
            for (String[] pair : CONTRADICTION_PAIRS) {
                boolean foundFirst = false, foundSecond = false;
                for (String s : sentences) {
                    String sl = s.toLowerCase();
                    if (!foundFirst && sl.contains(pair[0])) foundFirst = true;
                    if (!foundSecond && sl.contains(pair[1])) foundSecond = true;
                }
                if (foundFirst && foundSecond) {
                    score -= 0.15;
                    issues.add(new Issue(Severity.MAJOR, "contradiction",
                            "输出同时声称 \"" + pair[0] + "\" 和 \"" + pair[1] + "\""));
                    break;  // 只报告第一对矛盾
                }
            }
        }

        // 结构奖励
        if (output.contains("```")) score += 0.1;
        if (output.contains("- ") || output.contains("1. ")) score += 0.05;
        if (output.contains("\n\n")) score += 0.05;

        score = Math.max(0.0, Math.min(1.0, score));

        eval.setScore(score);
        eval.setSuccess(score >= 0.65);
        eval.setNeedsRetry(!eval.isSuccess());
        eval.setIssues(issues);
        eval.getDimensions().put("heuristic", score);

        StringBuilder reason = new StringBuilder("启发式评估结果：");
        if (issues.isEmpty()) reason.append("未检测到问题");
        else issues.forEach(i -> reason.append(i.getCategory()).append("; "));
        eval.setReasoning(reason.toString());

        return eval;
    }

    /** 去除markdown代码块，避免代码中的error等关键词被误判 */
    private String stripCodeBlocks(String text) {
        if (text == null) return "";
        return text.replaceAll("```[\\s\\S]*?```", "")
                   .replaceAll("(?m)^    .*", ""); // 缩进代码块
    }
}
