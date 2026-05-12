package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.annotation.tool.Tool;
import lyjew.com.lyclaw.annotation.tool.Param;

/**
 * 数学表达式计算工具，使用递归下降解析器对表达式求值。
 *
 * <p>支持的操作：加法(+)、减法(-)、乘法(*)、除法(/)、取模(%)、幂运算(^)、一元正负号、
 * 括号、小数。解析器按标准运算符优先级处理（幂 > 乘除模 > 加减）。
 * 除法会检测除零错误。</p>
 *
 * <p>通过 {@code @Tool} 注解标记为内置只读工具，group 为 "builtin"。</p>
 */
@Tool(name = "calculator",
      description = "执行数学表达式计算，支持四则运算(+,-,*,/)、幂(^)、括号和基本函数",
      readonly = true,
      group = "builtin")
public class AnnotatedCalculatorTool {

    /**
     * 计算数学表达式的值。
     *
     * @param expression 数学表达式字符串，例如 "2+3*4"
     * @return 计算结果或错误描述
     */
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

    /**
     * 格式化计算结果。整数结果不显示小数位，非整数保留 6 位小数。
     */
    private String formatResult(double result) {
        if (result == Math.floor(result) && !Double.isInfinite(result)) {
            return String.format("%.0f", result);
        }
        return String.format("%.6f", result);
    }

    /**
     * 入口：去除空白后开始递归下降解析表达式。
     *
     * @param expr 原始表达式字符串
     * @return 计算结果
     */
    private double eval(String expr) {
        String clean = expr.replaceAll("\\s+", "");
        if (clean.isEmpty()) throw new IllegalArgumentException("表达式为空");
        // pos[0] 用于在递归中跟踪当前解析位置
        int[] pos = new int[]{0};
        double result = parseExpression(clean, pos);
        // 全部解析完后应到达字符串末尾，否则存在无法识别的字符
        if (pos[0] < clean.length()) {
            throw new IllegalArgumentException(
                    "位置 " + pos[0] + " 处有无法识别的字符: '" + clean.charAt(pos[0]) + "'");
        }
        return result;
    }

    /**
     * 解析加减法（最低优先级）。
     * 表达式 = 项 (('+'|'-') 项)*
     */
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

    /**
     * 解析乘除法和取模（中等优先级）。
     * 项 = 幂 (('*'|'/'|'%') 幂)*
     */
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

    /**
     * 解析幂运算（右结合，高优先级）。
     * 幂 = 因子 ('^' 幂)?
     */
    private double parsePower(String s, int[] pos) {
        double base = parseFactor(s, pos);
        // 幂运算右结合
        if (pos[0] < s.length() && s.charAt(pos[0]) == '^') {
            pos[0]++;
            double exponent = parsePower(s, pos);
            return Math.pow(base, exponent);
        }
        return base;
    }

    /**
     * 解析基本因子：一元正负号、括号表达式、数字。
     */
    private double parseFactor(String s, int[] pos) {
        skipSpaces(s, pos);
        if (pos[0] >= s.length()) throw new IllegalArgumentException("表达式不完整");
        char c = s.charAt(pos[0]);
        if (c == '-') { pos[0]++; return -parseFactor(s, pos); }  // 一元负号
        if (c == '+') { pos[0]++; return parseFactor(s, pos); }    // 一元正号
        if (c == '(') {
            pos[0]++;
            double val = parseExpression(s, pos);                   // 递归解析括号内容
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ')')
                throw new IllegalArgumentException("缺少闭合括号");
            pos[0]++;
            return val;
        }
        return parseNumber(s, pos);
    }

    /** 跳过空格。 */
    private void skipSpaces(String s, int[] pos) {
        while (pos[0] < s.length() && s.charAt(pos[0]) == ' ') pos[0]++;
    }

    /**
     * 解析数字：支持整数和小数，至多一个小数点。
     */
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
