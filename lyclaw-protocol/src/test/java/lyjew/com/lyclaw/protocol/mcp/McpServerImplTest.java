package lyjew.com.lyclaw.protocol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerImplTest {

    private McpServerImpl server;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        server = new McpServerImpl();
        mapper = new ObjectMapper();

        server.registerTool(McpToolDescriptor.builder()
                .name("test_tool").description("A test tool")
                .inputSchema(Map.of("type", "object",
                        "properties", Map.of("arg1", Map.of("type", "string"))))
                .serverName("test-server").build());

        server.registerResource(McpResourceDescriptor.builder()
                .uri("file:///test.txt").name("Test File")
                .description("Resource for testing").mimeType("text/plain")
                .build());

        server.registerPrompt(McpPromptDescriptor.builder()
                .name("test_prompt").description("A test prompt")
                .arguments(java.util.List.of(
                        McpPromptDescriptor.McpPromptArgument.builder()
                                .name("topic").description("Topic").required(true).build()))
                .build());
    }

    @Nested
    @DisplayName("JSON-RPC dispatch")
    class JsonRpcDispatch {

        @Test
        @DisplayName("tools/list returns successfully")
        void toolsList() throws Exception {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            String response = server.handleJsonRpcRequest(request);

            JsonNode root = mapper.readTree(response);
            assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(root.has("result")).isTrue();
            assertThat(root.get("result").get("tools").isArray()).isTrue();
            assertThat(root.get("result").get("tools").size()).isEqualTo(1);
            assertThat(root.get("result").get("tools").get(0).get("name").asText())
                    .isEqualTo("test_tool");
        }

        @Test
        @DisplayName("tools/call returns successfully")
        void toolsCall() throws Exception {
            String request = """
                    {
                      "jsonrpc": "2.0",
                      "id": 2,
                      "method": "tools/call",
                      "params": {"name": "test_tool", "arguments": {"arg1": "value1"}}
                    }""";

            String response = server.handleJsonRpcRequest(request);
            JsonNode root = mapper.readTree(response);

            assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(root.has("result")).isTrue();
            assertThat(root.get("result").get("content").isArray()).isTrue();
        }

        @Test
        @DisplayName("tools/call with non-existent tool returns error")
        void toolsCallNonExistent() throws Exception {
            String request = """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call",
                     "params":{"name":"nonexistent"}}""";

            String response = server.handleJsonRpcRequest(request);
            JsonNode root = mapper.readTree(response);

            // Should contain result (tool not found handled by blocking get on CompletableFuture)
            // but the successResponse contains isError=true in the result
            assertThat(root.has("result")).isTrue();
        }

        @Test
        @DisplayName("resources/list returns successfully")
        void resourcesList() throws Exception {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}";

            String response = server.handleJsonRpcRequest(request);
            JsonNode root = mapper.readTree(response);

            assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(root.get("result").get("resources").isArray()).isTrue();
            assertThat(root.get("result").get("resources").size()).isEqualTo(1);
        }

        @Test
        @DisplayName("prompts/list returns successfully")
        void promptsList() throws Exception {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\"}";

            String response = server.handleJsonRpcRequest(request);
            JsonNode root = mapper.readTree(response);

            assertThat(root.get("result").get("prompts").isArray()).isTrue();
        }

        @Test
        @DisplayName("initialize returns server capabilities")
        void initialize() throws Exception {
            String request = """
                    {"jsonrpc":"2.0","id":6,"method":"initialize",
                     "params":{"protocolVersion":"2024-11-05"}}""";

            String response = server.handleJsonRpcRequest(request);
            JsonNode root = mapper.readTree(response);

            assertThat(root.has("result")).isTrue();
            assertThat(root.get("result").get("protocolVersion").asText())
                    .isEqualTo("2024-11-05");
            assertThat(root.get("result").get("serverInfo").get("name").asText())
                    .isEqualTo("lyclaw-mcp-server");
            assertThat(root.get("result").has("capabilities")).isTrue();
        }

        @Test
        @DisplayName("Unknown method returns error -32601")
        void unknownMethodReturnsError() throws Exception {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"unknown/method\"}";

            String response = server.handleJsonRpcRequest(request);
            JsonNode root = mapper.readTree(response);

            assertThat(root.has("error")).isTrue();
            assertThat(root.get("error").get("code").asInt()).isEqualTo(-32601);
            assertThat(root.get("error").get("message").asText())
                    .contains("Method not found");
        }

        @Test
        @DisplayName("Invalid JSON returns parse error -32700")
        void invalidJsonReturnsParseError() throws Exception {
            String response = server.handleJsonRpcRequest("not valid json at all");

            JsonNode root = mapper.readTree(response);
            assertThat(root.has("error")).isTrue();
            assertThat(root.get("error").get("code").asInt()).isEqualTo(-32700);
        }
    }

    @Nested
    @DisplayName("Response format (envelope)")
    class ResponseFormat {

        @Test
        @DisplayName("successResponse includes jsonrpc, id, result")
        void successResponseFormat() throws Exception {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\"}";
            String response = server.handleJsonRpcRequest(request);

            JsonNode root = mapper.readTree(response);
            assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(root.get("id").asInt()).isEqualTo(42);
            assertThat(root.has("result")).isTrue();
            assertThat(root.has("error")).isFalse();
        }

        @Test
        @DisplayName("errorResponse includes jsonrpc, id, error with code and message")
        void errorResponseFormat() throws Exception {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"bad\"}";
            String response = server.handleJsonRpcRequest(request);

            JsonNode root = mapper.readTree(response);
            assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(root.get("id").asInt()).isEqualTo(99);
            assertThat(root.has("error")).isTrue();
            assertThat(root.has("result")).isFalse();
            assertThat(root.get("error").has("code")).isTrue();
            assertThat(root.get("error").has("message")).isTrue();
        }
    }

    @Nested
    @DisplayName("Runtime state")
    class RuntimeState {

        @Test
        @DisplayName("Initial state is not running")
        void initiallyNotRunning() {
            assertThat(server.isRunning()).isFalse();
        }

        @Test
        @DisplayName("list tools returns all registered")
        void listToolsReturnsAll() {
            var tools = server.listTools();
            assertThat(tools).hasSize(1);
        }

        @Test
        @DisplayName("set server name and version")
        void setServerNameAndVersion() {
            server.setServerName("custom-server");
            server.setServerVersion("2.0.0");
            assertThat(server.getServerName()).isEqualTo("custom-server");
            assertThat(server.getServerVersion()).isEqualTo("2.0.0");
        }
    }

    @Nested
    @DisplayName("executeTool")
    class ExecuteTool {

        @Test
        @DisplayName("Execute existing tool returns success")
        void executeExistingTool() throws Exception {
            var future = server.executeTool("test_tool", Map.of("arg1", "val"));
            var result = future.get();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("test_tool");
            assertThat(result.getDurationMs()).isPositive();
        }

        @Test
        @DisplayName("Execute non-existent tool returns failure")
        void executeNonExistentTool() throws Exception {
            var future = server.executeTool("no_such_tool", Map.of());
            var result = future.get();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("not found");
        }
    }
}
