package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数学计算工具 —— 使用纯 Java 表达式求值器执行数学计算。
 *
 * <p>模型需要精确计算时调用此工具。支持基础四则运算（加减乘除）、
 * 幂运算（^）、括号分组等。当模型不确定计算结果时也应使用此工具。</p>
 *
 * <p><b>JDK 兼容性说明</b>：JDK 15+ 移除 Nashorn ScriptEngine，
 * 本工具使用纯 Java 表达式解析器，不依赖任何第三方库或 JSR 223 引擎。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
@Component
public class CalculatorTool implements Tool {

    private static final String TOOL_NAME = "calculator";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        try {
            String expression = parseExpression(toolCall);
            if (expression == null || expression.isBlank()) {
                return ToolResult.failure("计算失败：表达式为空");
            }
            double result = eval(expression);
            String formatted;
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                formatted = String.format("%.0f", result);
            } else {
                formatted = String.format("%.6f", result);
            }
            return ToolResult.success("计算结果: " + formatted);
        } catch (Exception e) {
            return ToolResult.failure("计算失败: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("执行数学计算。支持四则运算（+ - * /）"
                        + "、幂运算（^）、括号分组、取余（%%）。"
                        + "当需要精确计算数值时使用。"
                        + "传入表达式如 \"2 + 3 * 4\" 或 \"(2+3)^3\"。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of(
                                        "type", "string",
                                        "description", "要计算的数学表达式，"
                                                + "如 \"2 + 3 * 4\""
                                )
                        ),
                        "required", List.of("expression")
                ))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 纯 Java 表达式求值器（递归下降解析）
    // 支持：+ - * / % ^ ( ) 正整数和小数
    // ═══════════════════════════════════════════════════════════════

    /** 运算符优先级的入口：+ - */
    private double parseExpression(String s, int[] pos) {
        double left = parseTerm(s, pos);
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '+') { pos[0]++; left += parseTerm(s, pos); }
            else if (c == '-') { pos[0]++; left -= parseTerm(s, pos); }
            else break;
        }
        return left;
    }

    /** 运算符优先级：* / % */
    private double parseTerm(String s, int[] pos) {
        double left = parsePower(s, pos);
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '*') { pos[0]++; left *= parsePower(s, pos); }
            else if (c == '/') {
                pos[0]++;
                double right = parsePower(s, pos);
                if (right == 0) throw new ArithmeticException("除零错误");
                left /= right;
            }
            else if (c == '%') { pos[0]++; left %= parsePower(s, pos); }
            else break;
        }
        return left;
    }

    /** 运算符优先级：^（右结合） */
    private double parsePower(String s, int[] pos) {
        double base = parseFactor(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '^') {
            pos[0]++;
            double exponent = parsePower(s, pos); // 递归实现右结合
            return Math.pow(base, exponent);
        }
        return base;
    }

    /** 因子：数字、括号表达式、一元正负号 */
    private double parseFactor(String s, int[] pos) {
        while (pos[0] < s.length() && s.charAt(pos[0]) == ' ') pos[0]++;
        if (pos[0] >= s.length()) throw new IllegalArgumentException("表达式不完整");
        char c = s.charAt(pos[0]);
        if (c == '-') { pos[0]++; return -parseFactor(s, pos); }
        if (c == '+') { pos[0]++; return parseFactor(s, pos); }
        if (c == '(') {
            pos[0]++;
            double val = parseExpression(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ')')
                throw new IllegalArgumentException("缺少闭合括号");
            pos[0]++;
            return val;
        }
        return parseNumber(s, pos);
    }

    /** 解析数字（整数或小数） */
    private double parseNumber(String s, int[] pos) {
        int start = pos[0];
        boolean hasDot = false;
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c >= '0' && c <= '9') pos[0]++;
            else if (c == '.' && !hasDot) { hasDot = true; pos[0]++; }
            else break;
        }
        if (pos[0] == start)
            throw new IllegalArgumentException("期望数字，但在位置 " + start + " 遇到字符 '" + s.charAt(start) + "'");
        return Double.parseDouble(s.substring(start, pos[0]));
    }

    /** 表达式求值入口 */
    private double eval(String expr) {
        String clean = expr.replaceAll("\\s+", "");
        if (clean.isEmpty()) throw new IllegalArgumentException("表达式为空");
        return parseExpression(clean, new int[]{0});
    }

    /** 从 toolCall.arguments 中提取 expression 字段 */
    private String parseExpression(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) return "";
        if (args.contains("\"expression\"")) {
            int start = args.indexOf("\"expression\"") + "\"expression\"".length();
            start = args.indexOf(":", start) + 1;
            start = args.indexOf("\"", start) + 1;
            int end = args.indexOf("\"", start);
            if (start < end && start > 0) return args.substring(start, end);
        }
        return args.replaceAll("\"", "").trim();
    }
}
