package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
@Deprecated
public class CalculatorTool implements Tool {

    private static final String TOOL_NAME = "calculator";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String expression = extractExpression(toolCall);
            if (expression == null || expression.isBlank()) {
                return ToolResult.failure("计算失败：表达式为空");
            }
            log.debug("计算表达式: {}", expression);
            double result = eval(expression);
            String formatted = formatResult(result);
            long elapsed = System.currentTimeMillis() - startTime;
            return new ToolResult(true, "计算结果: " + formatted, null, elapsed, 0);
        } catch (ArithmeticException e) {
            return ToolResult.failure("计算错误: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("表达式错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("计算异常", e);
            return ToolResult.failure("计算失败: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .source("builtin")
                .description("执行数学计算。支持四则运算（+ - * /）、幂运算（^）、括号分组、取余（%）。传入表达式如 \"2 + 3 * 4\"。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of(
                                        "type", "string",
                                        "description", "要计算的数学表达式"
                                )
                        ),
                        "required", List.of("expression")
                ))
                .build();
    }

    private String formatResult(double result) {
        if (result == Math.floor(result) && !Double.isInfinite(result)) {
            return String.format("%.0f", result);
        }
        return String.format("%.6f", result);
    }

    private double eval(String expr) {
        String clean = expr.replaceAll("\\s+", "");
        if (clean.isEmpty()) throw new IllegalArgumentException("表达式为空");
        int[] pos = new int[]{0};
        double result = parseExpression(clean, pos);
        if (pos[0] < clean.length()) {
            throw new IllegalArgumentException(
                    "位置 " + pos[0] + " 处有无法识别的字符: '" + clean.charAt(pos[0]) + "'");
        }
        return result;
    }

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

    private double parsePower(String s, int[] pos) {
        double base = parseFactor(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '^') {
            pos[0]++;
            double exponent = parsePower(s, pos);
            return Math.pow(base, exponent);
        }
        return base;
    }

    private double parseFactor(String s, int[] pos) {
        skipSpaces(s, pos);
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

    private void skipSpaces(String s, int[] pos) {
        while (pos[0] < s.length() && s.charAt(pos[0]) == ' ') pos[0]++;
    }

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
            throw new IllegalArgumentException(
                    "期望数字，但在位置 " + start + " 遇到字符 '" + s.charAt(start) + "'");
        return Double.parseDouble(s.substring(start, pos[0]));
    }

    private String extractExpression(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) return "";
        int exprIdx = args.indexOf("\"expression\"");
        if (exprIdx < 0) return args.replaceAll("\"", "").trim();
        int start = args.indexOf("\"", args.indexOf(":", exprIdx) + 1) + 1;
        int end = args.indexOf("\"", start);
        if (start < end && start > 0) return args.substring(start, end);
        return "";
    }
}
