package lyjew.com.lyclaw.reflect.impl.evaluator;

import lyjew.com.lyclaw.reflect.model.*;
import lyjew.com.lyclaw.reflect.primitive.Evaluator;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/**
 * 工具调用结果验证器 — 支持 4 种验证模式，检查 Actor 执行工具后的输出是否正确。
 *
 * <p><b>验证模式：</b>
 * <ol>
 *   <li>{@link VerificationType#EXIT_CODE} — 检查输出中的进程退出码（0=成功）</li>
 *   <li>{@link VerificationType#TEST_SUITE} — 解析测试框架输出（Tests run/PASSED/FAILED）</li>
 *   <li>{@link VerificationType#OUTPUT_DIFF} — 比对实际输出与预期输出（编辑距离）</li>
 *   <li>{@link VerificationType#CUSTOM_SCRIPT} — 委托给自定义验证函数</li>
 * </ol>
 *
 * <p>{@code testCount} 和 {@code passCount} 记录在 Evaluation 中，供 Router 做通过率判断。
 */
public class ToolVerifierEvaluator implements Evaluator {

    private final VerificationType mode;
    /** 预期输出，仅 OUTPUT_DIFF 模式使用 */
    private final String expectedOutput;
    /** 自定义验证脚本，仅 CUSTOM_SCRIPT 模式使用 */
    private final Function<String, Boolean> customVerifier;
    /** 测试结果解析正则：JUnit/Maven/Go test/Pytest */
    private static final Pattern TEST_RUN_PATTERN =
            Pattern.compile("Tests run:\\s*(\\d+).*?Failures:\\s*(\\d+)", Pattern.DOTALL);
    private static final Pattern PYTEST_PATTERN =
            Pattern.compile("(\\d+)\\s+passed,?\\s*(\\d+)?\\s*failed", Pattern.DOTALL);
    private static final Pattern GO_TEST_PATTERN =
            Pattern.compile("(PASS|FAIL)\\s*$", Pattern.MULTILINE);

    // ── 工厂方法 ──

    public static ToolVerifierEvaluator exitCode() {
        return new ToolVerifierEvaluator(VerificationType.EXIT_CODE, null, null);
    }
    public static ToolVerifierEvaluator testSuite() {
        return new ToolVerifierEvaluator(VerificationType.TEST_SUITE, null, null);
    }
    public static ToolVerifierEvaluator outputDiff(String expected) {
        return new ToolVerifierEvaluator(VerificationType.OUTPUT_DIFF, expected, null);
    }
    public static ToolVerifierEvaluator customScript(Function<String, Boolean> verifier) {
        return new ToolVerifierEvaluator(VerificationType.CUSTOM_SCRIPT, null, verifier);
    }

    private ToolVerifierEvaluator(VerificationType mode, String expected, Function<String, Boolean> custom) {
        this.mode = mode;
        this.expectedOutput = expected;
        this.customVerifier = custom;
    }

    @Override
    public Evaluation evaluate(ReflectionContext ctx) {
        return switch (mode) {
            case EXIT_CODE -> verifyExitCode(ctx);
            case TEST_SUITE -> verifyTestSuite(ctx);
            case OUTPUT_DIFF -> verifyOutputDiff(ctx);
            case CUSTOM_SCRIPT -> verifyCustomScript(ctx);
        };
    }

    // ── 退出码验证 ──

    /** 检查输出中是否包含编译/运行错误标记，无错误即为通过 */
    private Evaluation verifyExitCode(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        Evaluation eval = baseEval(output);

        if (output == null || output.isBlank()) {
            eval.setScore(0.0);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("退出码验证：无工具输出");
            return eval;
        }

        String lower = output.toLowerCase();
        boolean hasError = lower.contains("error:") || lower.contains("exception in thread")
                || lower.contains("traceback (most recent call last)")
                || lower.contains("command not found")
                || lower.contains("fatal:") || lower.contains("panic:");

        if (hasError) {
            eval.setScore(0.2);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("退出码验证：检测到错误输出");
            eval.getIssues().add(new Issue(Severity.CRITICAL, "exit_code", "工具执行报错"));
        } else {
            // 尝试解析显式退出码
            if (lower.contains("exit code 0") || lower.contains("exited with 0")
                    || lower.contains("process finished with exit code 0")) {
                eval.setScore(1.0);
                eval.setSuccess(true);
                eval.setReasoning("退出码验证：成功（exit code 0）");
            } else {
                eval.setScore(0.85);
                eval.setSuccess(true);
                eval.setReasoning("退出码验证：未检测到错误，假定成功");
            }
        }
        eval.setTestCount(1);
        eval.setPassCount(eval.isSuccess() ? 1 : 0);
        return eval;
    }

    // ── 测试套件验证 ──

    /** 解析 JUnit/Maven/Pytest/Go test 输出，提取运行/通过/失败数 */
    private Evaluation verifyTestSuite(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        Evaluation eval = baseEval(output);

        if (output == null || output.isBlank()) {
            eval.setScore(0.0);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("测试套件验证：无测试输出");
            return eval;
        }

        int total = 0, failed = 0;
        boolean matched = false;

        // JUnit / Maven Surefire
        Matcher m = TEST_RUN_PATTERN.matcher(output);
        if (m.find()) {
            total = Integer.parseInt(m.group(1));
            failed = Integer.parseInt(m.group(2));
            matched = true;
        }

        // Pytest
        if (!matched) {
            m = PYTEST_PATTERN.matcher(output);
            if (m.find()) {
                total = Integer.parseInt(m.group(1));
                failed = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                matched = true;
            }
        }

        // Go test
        if (!matched) {
            m = GO_TEST_PATTERN.matcher(output);
            long passCount = m.results().filter(r -> r.group(1).equals("PASS")).count();
            long failCount = m.results().filter(r -> r.group(1).equals("FAIL")).count();
            if (passCount + failCount > 0) {
                total = (int) (passCount + failCount);
                failed = (int) failCount;
                matched = true;
            }
        }

        if (!matched) {
            eval.setScore(0.0);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("测试套件验证：无法解析测试结果格式");
            eval.getIssues().add(new Issue(Severity.MAJOR, "parse", "无法解析测试输出格式"));
            return eval;
        }

        int passed = total - failed;
        double passRate = total > 0 ? (double) passed / total : 0.0;

        eval.setTestCount(total);
        eval.setPassCount(passed);
        eval.setScore(passRate);
        eval.setSuccess(passRate >= 0.8);
        eval.setNeedsRetry(passRate < 0.8);
        eval.setReasoning(String.format("测试套件验证：%d/%d 通过（%.1f%%）", passed, total, passRate * 100));

        if (failed > 0) {
            eval.getIssues().add(new Issue(
                    passRate < 0.5 ? Severity.CRITICAL : Severity.MAJOR,
                    "test_failures", failed + " 个测试失败"));
        }

        eval.getDimensions().put("passRate", passRate);
        eval.getDimensions().put("total", (double) total);
        eval.getDimensions().put("passed", (double) passed);
        return eval;
    }

    // ── 输出差异验证 ──

    /** 计算实际输出与预期输出的相似度（Levenshtein 距离比） */
    private Evaluation verifyOutputDiff(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        Evaluation eval = baseEval(output);

        if (output == null || output.isBlank()) {
            eval.setScore(0.0);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("输出差异验证：实际输出为空");
            return eval;
        }
        if (expectedOutput == null || expectedOutput.isBlank()) {
            eval.setScore(0.5);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("输出差异验证：未设置预期输出");
            return eval;
        }

        double similarity = normalizedLevenshtein(output, expectedOutput);
        eval.setScore(similarity);
        eval.setSuccess(similarity >= 0.9);
        eval.setNeedsRetry(similarity < 0.9);
        eval.setReasoning(String.format("输出差异验证：相似度 %.1f%%", similarity * 100));
        eval.getDimensions().put("similarity", similarity);

        if (similarity < 0.9) {
            eval.getIssues().add(new Issue(
                    similarity < 0.5 ? Severity.CRITICAL : Severity.MAJOR,
                    "output_diff", "实际输出与预期差异较大"));
        }
        return eval;
    }

    // ── 自定义脚本验证 ──

    private Evaluation verifyCustomScript(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        Evaluation eval = baseEval(output);

        if (customVerifier == null) {
            eval.setScore(0.0);
            eval.setSuccess(false);
            eval.setReasoning("自定义验证：未配置验证函数");
            return eval;
        }

        try {
            boolean passed = customVerifier.apply(output);
            eval.setScore(passed ? 1.0 : 0.0);
            eval.setSuccess(passed);
            eval.setNeedsRetry(!passed);
            eval.setReasoning(passed ? "自定义验证：通过" : "自定义验证：失败");
        } catch (Exception e) {
            eval.setScore(0.0);
            eval.setSuccess(false);
            eval.setNeedsRetry(true);
            eval.setReasoning("自定义验证异常：" + e.getMessage());
            eval.getIssues().add(new Issue(Severity.CRITICAL, "custom_script", e.getMessage()));
        }
        return eval;
    }

    // ── 归一化 Levenshtein 距离 ──

    /**
     * 计算两个字符串的归一化相似度（0=完全不同，1=完全相同）。
     * 使用 Levenshtein 编辑距离 / 较长的字符串长度。
     */
    public static double normalizedLevenshtein(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;

        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return 1.0 - (double) prev[b.length()] / maxLen;
    }

    private Evaluation baseEval(String output) {
        Evaluation eval = new Evaluation();
        eval.setRawOutput(output);
        eval.setDimensions(new LinkedHashMap<>());
        eval.setIssues(new ArrayList<>());
        return eval;
    }
}
