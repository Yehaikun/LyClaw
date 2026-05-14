package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("TavilyWebSearchTool 测试")
class TavilyWebSearchToolTest {

    private TavilyWebSearchTool tool;

    private String TAVILY_API_KEY="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";
    @BeforeEach
    void setUp() {
        tool = new TavilyWebSearchTool();
    }

    @Nested
    @DisplayName("元数据")
    class Metadata {

        @Test
        @DisplayName("getName 返回 web_search")
        void getName() {
            assertThat(tool.getName()).isEqualTo("web_search");
        }

        @Test
        @DisplayName("getDefinition 返回正确的参数 schema")
        void getDefinition() {
            ToolDefinition def = tool.getDefinition();

            assertThat(def.getName()).isEqualTo("web_search");
            assertThat(def.getDescription()).contains("Tavily");
            assertThat(def.isReadOnly()).isTrue();
            assertThat(def.getSource()).isEqualTo("builtin");
            assertThat(def.getParameters()).containsKey("properties");
            assertThat(def.getParameters()).containsKey("required");
        }
    }

    @Nested
    @DisplayName("execute 集成测试（需 TAVILY_API_KEY）")
    class Execute {

        @Test
        @DisplayName("真实搜索返回成功结果")
        void realSearch() {
            String apiKey = TAVILY_API_KEY;
            assumeTrue(apiKey != null && !apiKey.isBlank(),
                    "跳过：未设置 TAVILY_API_KEY 环境变量");

            ToolCall call = ToolCall.builder()
                    .name("web_search")
                    .arguments("{\"query\":\"Java 21 new features\"}")
                    .build();

            ToolExecutionResult result = tool.execute(call, null);

            assertThat(result.isSuccess())
                    .as("搜索失败: " + result.getError())
                    .isTrue();
            assertThat(result.getResult()).isNotEmpty();
            assertThat(result.getToolName()).isEqualTo("web_search");
            assertThat(result.getElapsedMs()).isPositive();
            System.out.println("搜索耗时: " + result.getElapsedMs() + "ms");
            System.out.println("搜索结果:\n" + result.getResult());
        }

        @Test
        @DisplayName("空查询返回失败")
        void emptyQuery() {
            ToolCall call = ToolCall.builder()
                    .name("web_search")
                    .arguments("{\"query\":\"\"}")
                    .build();

            ToolExecutionResult result = tool.execute(call, null);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("关键词为空");
        }

        @Test
        @DisplayName("纯文本参数也能提取查询")
        void plainTextQuery() {
            String apiKey = TAVILY_API_KEY;
            assumeTrue(apiKey != null && !apiKey.isBlank(),
                    "跳过：未设置 TAVILY_API_KEY 环境变量");

            // arguments 是纯文本而非 JSON 时，直接作为 query 使用
            ToolCall call = ToolCall.builder()
                    .name("web_search")
                    .arguments("Python 3.13 new features")
                    .build();

            ToolExecutionResult result = tool.execute(call, null);

            assertThat(result.isSuccess())
                    .as("搜索失败: " + result.getError())
                    .isTrue();
            assertThat(result.getResult()).isNotEmpty();
        }
    }
}
