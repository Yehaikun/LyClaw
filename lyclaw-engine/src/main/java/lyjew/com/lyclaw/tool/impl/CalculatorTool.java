package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import javax.script.ScriptEngineManager;
import javax.script.ScriptEngine;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 数学计算工具 —— 使用 Java ScriptEngine 执行数学表达式求值。
 *
 * <p>模型需要精确计算时调用此工具。支持基础四则运算 (加减乘除), 幂运算 (Math.pow)、三角函数 (sin/cos/tan)、对数 (log) 等。</p>
        *
        * @since 1.0
        * @author LyClaw Team
 * @see Tool
 */

@Component
public class CalculatorTool implements Tool {

    /** 工具名称常量 */
    private static final String TOOL_NAME = "calculator";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        try {
            // 1. 从 toolCall 的 arguments 中提取数学表达式
            String expression = parseExpression(toolCall);

            // 2. 使用 Java ScriptEngine 执行表达式求值
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");

            // 执行求值
            Object result = engine.eval(expression);

            // 3. 格式化并返回计算结果
            return ToolResult.success("计算结果: " + result);
        } catch (Exception e) {
            // 计算失败（如表达式语法错误、除零等）
            return ToolResult.failure("计算失败: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        // 返回工具定义 —— 模型根据 JSON Schema 知道需要传入 expression
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("执行数学计算。支持四则运算、幂运算、三角函数等。"
                        + "当需要精确计算数值时使用。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of(
                                        "type", "string",
                                        "description", "要计算的数学表达式，"
                                                + "如 \"2 + 3 * 4\""
                                )
                        ),
                        "required", java.util.List.of("expression")
                ))
                .build();
    }

    /**
     * 从 toolCall 参数中提取数学表达式。
     *
     * @param toolCall 工具调用请求
     * @return 数学表达式字符串
     */
    private String parseExpression(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) {
            return "";
        }
        if (args.contains("\"expression\"")) {
            int start = args.indexOf("\"expression\"") + "\"expression\"".length();
            start = args.indexOf(":", start) + 1;
            start = args.indexOf("\"", start) + 1;
            int end = args.indexOf("\"", start);
            return args.substring(start, end);
        }
        return args.replaceAll("\"", "").trim();
    }
}