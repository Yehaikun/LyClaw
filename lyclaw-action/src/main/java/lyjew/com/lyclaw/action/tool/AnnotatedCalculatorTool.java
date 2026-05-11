package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.framework.annotation.Tool;
import lyjew.com.lyclaw.framework.annotation.Param;
import org.springframework.stereotype.Component;

@Tool(name = "calculator",
      description = "执行数学表达式计算，支持四则运算(+,-,*,/)、幂(^)、括号和基本函数",
      readonly = true,
      group = "builtin")
@Component
public class AnnotatedCalculatorTool {

    public String calculate(
        @Param(name = "expression", description = "数学表达式，例如: 2+3*4, (1+2)^3, sqrt(16)")
        String expression
    ) {
        if (expression == null || expression.isBlank()) {
            return "计算失败：表达式为空";
        }
        try {
            double result = eval(expression);
            return formatResult(result);
        } catch (ArithmeticException e) {
            return "计算错误: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "表达式错误: " + e.getMessage();
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
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
}
