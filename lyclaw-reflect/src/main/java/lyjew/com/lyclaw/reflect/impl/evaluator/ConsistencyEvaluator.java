package lyjew.com.lyclaw.reflect.impl.evaluator;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.*;
import lyjew.com.lyclaw.reflect.primitive.Evaluator;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.*;

/**
 * 自一致性验证器 — CoVe (Chain-of-Verification) 启发式实现。
 *
 * <p>将输出文本拆分为独立陈述句，逐句检查是否存在事实矛盾：
 * <ol>
 *   <li><b>语句拆分</b> — 按句号、问号、换行等分隔符切分为 statement 列表</li>
 *   <li><b>两两比对</b> — 对所有 statement 对 (i, j) 进行 4 项矛盾检测：
 *     <ul>
 *       <li>数值矛盾：同一主题出现不同的数字/日期</li>
 *       <li>极性翻转：肯定/否定同一命题</li>
 *       <li>版本冲突：v1/v2、old/new 等版本指代不一致</li>
 *       <li>对立词对：increase/decrease 等反义词各自出现</li>
 *     </ul>
 *   </li>
 *   <li><b>评分</b> — 每发现一处不一致扣 0.15，起始分 1.0</li>
 * </ol>
 *
 * <p>仅做启发式规则检测（无 LLM 调用），适合作为快速一致性筛查器。
 */
@Primitive(type = PrimitiveType.EVALUATOR, name = "consistency")
public class ConsistencyEvaluator implements Evaluator {

    /** 同一主题翻转模式：is/is not, can/cannot, will/will not 等 */
    private static final List<String[]> POLARITY_PAIRS = List.of(
            new String[]{" is ", " is not "},
            new String[]{" are ", " are not "},
            new String[]{" can ", " cannot "},
            new String[]{" will ", " will not "},
            new String[]{" should ", " should not "},
            new String[]{" does ", " does not "}
    );

    /** 事实性反义词对 */
    private static final List<String[]> FACTUAL_CONTRADICTIONS = List.of(
            new String[]{"increase", "decrease"},
            new String[]{"always", "never"},
            new String[]{"true", "false"},
            new String[]{"correct", "incorrect"},
            new String[]{"supported", "unsupported"},
            new String[]{"compatible", "incompatible"},
            new String[]{"recommend", "avoid"},
            new String[]{"safe", "dangerous"},
            new String[]{"open source", "proprietary"},
            new String[]{"synchronous", "asynchronous"},
            new String[]{"blocking", "non-blocking"},
            new String[]{"deprecated", "recommended"},
            new String[]{"obsolete", "current"}
    );

    /** 版本指代冲突模式 */
    private static final List<String> VERSION_PATTERNS = List.of(
            "v1", "v2", "v3", "v4",
            "version 1", "version 2", "version 3",
            "old", "new", "former", "latter",
            "previous", "current", "latest"
    );

    /** 数值匹配正则 — 检测数字/日期/时间等可量化声明 */
    private static final String NUMBER_RE = "\\d+(?:\\.\\d+)?(?:%|ms|s|mb|gb|tb|px|em|rem)?";

    @Override
    public Evaluation evaluate(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        Evaluation eval = new Evaluation();
        eval.setRawOutput(output);
        eval.setDimensions(new LinkedHashMap<>());
        eval.setIssues(new ArrayList<>());
        eval.setInconsistencies(new ArrayList<>());

        if (output == null || output.isBlank()) {
            eval.setScore(1.0);
            eval.setConsistent(true);
            eval.setSuccess(true);
            eval.setReasoning("一致性验证：空输出，跳过");
            return eval;
        }

        // 拆分为独立陈述句
        String[] rawStatements = output.split("(?<=[.!?])\\s+");
        List<String> statements = new ArrayList<>();
        for (String s : rawStatements) {
            String trimmed = s.trim();
            if (trimmed.length() > 15 && !trimmed.startsWith("```")) {
                statements.add(trimmed);
            }
        }

        double score = 1.0;
        List<Inconsistency> inconsistencies = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();

        // 两两比对检测矛盾
        for (int i = 0; i < statements.size(); i++) {
            for (int j = i + 1; j < statements.size(); j++) {
                String a = statements.get(i).toLowerCase();
                String b = statements.get(j).toLowerCase();

                // 1. 极性翻转检测
                for (String[] pair : POLARITY_PAIRS) {
                    boolean aHasPos = a.contains(pair[0]) && !a.contains(pair[1]);
                    boolean bHasNeg = b.contains(pair[1]);
                    boolean bHasPos = b.contains(pair[0]) && !b.contains(pair[1]);
                    boolean aHasNeg = a.contains(pair[1]);
                    if ((aHasPos && bHasNeg) || (bHasPos && aHasNeg)) {
                        score -= 0.2;
                        inconsistencies.add(new Inconsistency(
                                statements.get(i), statements.get(j),
                                "极性翻转: " + pair[0].trim() + " vs " + pair[1].trim(), null));
                    }
                }

                // 2. 事实矛盾检测
                for (String[] pair : FACTUAL_CONTRADICTIONS) {
                    if (a.contains(pair[0]) && b.contains(pair[1])) {
                        score -= 0.15;
                        inconsistencies.add(new Inconsistency(
                                statements.get(i), statements.get(j),
                                "事实矛盾: " + pair[0] + " vs " + pair[1], null));
                    }
                }

                // 3. 版本冲突检测
                long versionsInA = VERSION_PATTERNS.stream().filter(a::contains).count();
                long versionsInB = VERSION_PATTERNS.stream().filter(b::contains).count();
                if (versionsInA > 0 && versionsInB > 0) {
                    // 检查是否引用不同版本（如 a 说 v1, b 说 v2）
                    boolean conflict = false;
                    for (String vp : VERSION_PATTERNS) {
                        if (a.contains(vp) && !b.contains(vp)) {
                            conflict = true;
                            break;
                        }
                    }
                    if (conflict) {
                        score -= 0.1;
                        inconsistencies.add(new Inconsistency(
                                statements.get(i), statements.get(j),
                                "版本指代不一致", null));
                    }
                }

                // 4. 数值矛盾（同主题不同数值）
                List<String> numsA = extractNumbers(a);
                List<String> numsB = extractNumbers(b);
                if (!numsA.isEmpty() && !numsB.isEmpty()) {
                    // 检查是否有共同的关键词（同一主题）
                    Set<String> wordsA = new HashSet<>(List.of(a.split("\\s+")));
                    Set<String> wordsB = new HashSet<>(List.of(b.split("\\s+")));
                    wordsA.retainAll(wordsB);
                    wordsA.removeIf(w -> w.length() < 4 || w.matches("\\d+.*"));
                    if (!wordsA.isEmpty() && !numsA.equals(numsB)) {
                        score -= 0.1;
                        inconsistencies.add(new Inconsistency(
                                statements.get(i), statements.get(j),
                                "数值矛盾（共同主题: " + String.join(",", wordsA) + "）", null));
                    }
                }
            }
        }

        score = Math.max(0.0, Math.min(1.0, score));
        boolean isConsistent = score >= 0.8;

        eval.setScore(score);
        eval.setConsistent(isConsistent);
        eval.setSuccess(isConsistent);
        eval.setNeedsRetry(!isConsistent);
        eval.setInconsistencies(inconsistencies);
        eval.setIssues(issues);
        eval.getDimensions().put("consistency", score);
        eval.getDimensions().put("statements", (double) statements.size());
        eval.getDimensions().put("inconsistencies", (double) inconsistencies.size());

        if (inconsistencies.isEmpty()) {
            eval.setReasoning("一致性验证：未检测到矛盾");
        } else {
            eval.setReasoning(String.format("一致性验证：发现 %d 处矛盾，一致性分 %.2f",
                    inconsistencies.size(), score));
            for (Inconsistency inc : inconsistencies) {
                issues.add(new Issue(
                        Severity.MAJOR, "inconsistency",
                        inc.getReason()));
            }
        }

        return eval;
    }

    /** 提取语句中的数字/日期/百分比 */
    private List<String> extractNumbers(String text) {
        List<String> nums = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(NUMBER_RE)
                .matcher(text);
        while (m.find()) {
            nums.add(m.group());
        }
        return nums;
    }
}
