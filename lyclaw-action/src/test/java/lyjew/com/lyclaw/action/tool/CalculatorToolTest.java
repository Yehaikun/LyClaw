package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 CalculatorTool 的递归下降解析器
 */
@DisplayName("CalculatorTool 递归下降解析器测试")
class CalculatorToolTest {

    private CalculatorTool calculator;

    @BeforeEach
    void setUp() {
        calculator = new CalculatorTool();
    }

    @Nested
    @DisplayName("基础四则运算")
    class BasicArithmetic {

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource(delimiter = '=', value = {
                "2 + 3 = 计算结果: 5",
                "10 - 3 = 计算结果: 7",
                "4 * 5 = 计算结果: 20",
                "20 / 4 = 计算结果: 5",
                "10 % 3 = 计算结果: 1",
                "2.5 + 3.5 = 计算结果: 6",
                "10 / 3 = 计算结果: 3.333333",
        })
        void testBasicOperations(String expr, String expectedOutput) {
            ToolCall call = ToolCall.builder()
                    .name("calculator")
                    .arguments("{\"expression\": \"" + expr + "\"}")
                    .build();
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess(), "表达式 '" + expr + "' 应成功计算");
            assertEquals(expectedOutput, result.getResult());
        }
    }

    @Nested
    @DisplayName("运算符优先级")
    class OperatorPrecedence {

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource(delimiter = '=', value = {
                "2 + 3 * 4 = 计算结果: 14",
                "2 * 3 + 4 = 计算结果: 10",
                "6 / 2 + 1 = 计算结果: 4",
                "2 + 6 / 3 = 计算结果: 4",
                "2 + 3 * 4 - 1 = 计算结果: 13",
                "10 - 2 * 3 = 计算结果: 4",
                "2 * 3 + 4 * 5 = 计算结果: 26",
                "10 % 3 + 2 = 计算结果: 3",
                "10 + 3 % 2 = 计算结果: 11",
        })
        void testPrecedence(String expr, String expectedOutput) {
            ToolCall call = buildExprCall(expr);
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess(), "表达式 '" + expr + "' 应成功计算");
            assertEquals(expectedOutput, result.getResult());
        }
    }

    @Nested
    @DisplayName("括号分组")
    class Parentheses {

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource(delimiter = '=', value = {
                "(2 + 3) * 4 = 计算结果: 20",
                "2 * (3 + 4) = 计算结果: 14",
                "((2 + 3) * 2) + 1 = 计算结果: 11",
                "(1 + 2) * (3 + 4) = 计算结果: 21",
                "2 * (3 + (4 - 1)) = 计算结果: 12",
        })
        void testParentheses(String expr, String expectedOutput) {
            ToolCall call = buildExprCall(expr);
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess(), "表达式 '" + expr + "' 应成功计算");
            assertEquals(expectedOutput, result.getResult());
        }
    }

    @Nested
    @DisplayName("幂运算")
    class Power {

        @Test
        void testPowerBasic() {
            ToolCall call = buildExprCall("2 ^ 3");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("计算结果: 8", result.getResult());
        }

        @Test
        void testPowerRightAssociative() {
            ToolCall call = buildExprCall("2 ^ 3 ^ 2");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("计算结果: 512", result.getResult());
        }

        @Test
        void testPowerWithMultiplication() {
            ToolCall call = buildExprCall("2 * 3 ^ 2");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("计算结果: 18", result.getResult());
        }
    }

    @Nested
    @DisplayName("负数/一元运算符")
    class UnaryOperators {

        @Test
        void testNegativeNumber() {
            ToolCall call = buildExprCall("-5 + 3");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("计算结果: -2", result.getResult());
        }

        @Test
        void testDoubleNegative() {
            ToolCall call = buildExprCall("--5");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("计算结果: 5", result.getResult());
        }

        @Test
        void testNegativeParentheses() {
            ToolCall call = buildExprCall("-(3 + 2)");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("计算结果: -5", result.getResult());
        }
    }

    @Nested
    @DisplayName("错误处理")
    class ErrorHandling {

        @Test
        void testDivisionByZero() {
            ToolCall call = buildExprCall("5 / 0");
            ToolResult result = calculator.execute(call, null);
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("除零"));
        }

        @Test
        void testUnclosedParenthesis() {
            ToolCall call = buildExprCall("(2 + 3");
            ToolResult result = calculator.execute(call, null);
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("缺少闭合括号"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"", "   "})
        void testEmptyExpression(String expr) {
            ToolCall call = ToolCall.builder()
                    .name("calculator")
                    .arguments(expr == null ? "" : "{\"expression\": \"" + expr + "\"}")
                    .build();
            ToolResult result = calculator.execute(call, null);
            assertFalse(result.isSuccess());
        }

        @Test
        void testInvalidCharacter() {
            ToolCall call = buildExprCall("2 + a");
            ToolResult result = calculator.execute(call, null);
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("位置"));
        }
    }

    @Nested
    @DisplayName("结果格式化")
    class Formatting {

        @Test
        void testWholeNumberNoDecimal() {
            ToolCall call = buildExprCall("10.0 + 2.0");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("计算结果: 12", result.getResult());
        }

        @Test
        void testDecimalFormat() {
            ToolCall call = buildExprCall("10 / 3");
            ToolResult result = calculator.execute(call, null);
            assertTrue(result.isSuccess());
            assertTrue(result.getResult().matches("计算结果: [0-9]+\\.[0-9]{6}"));
        }
    }

    @Nested
    @DisplayName("def/name")
    class Definition {

        @Test
        void testGetName() {
            assertEquals("calculator", calculator.getName());
        }

        @Test
        void testGetDefinition() {
            ToolDefinition def = calculator.getDefinition();
            assertEquals("calculator", def.getName());
            assertEquals("builtin", def.getSource());
            assertNotNull(def.getDescription());
            assertTrue(def.getDescription().contains("四则运算"));
            assertNotNull(def.getParameters());
        }
    }

    /** 辅助方法：构建带 expression 参数的 ToolCall */
    private ToolCall buildExprCall(String expression) {
        return ToolCall.builder()
                .name("calculator")
                .arguments("{\"expression\": \"" + expression + "\"}")
                .build();
    }
}
