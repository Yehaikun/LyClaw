package lyjew.com.lyclaw.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ModelResponse.ToolCallRequest#appendArguments(String)}
 * and the downstream {@code mergeChunks} path to verify that literal braces
 * inside JSON string values survive streaming fragment assembly.
 */
class ModelResponseTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ── appendArguments unit tests ──────────────────────────────────

    @Nested
    @DisplayName("appendArguments fragment concatenation")
    class AppendArguments {

        @Test
        @DisplayName("simple command split across two chunks")
        void simpleTwoChunk() throws Exception {
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"");
            tcr.appendArguments("ls -la\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "ls -la");
        }

        @Test
        @DisplayName("literal closing brace inside string value — the core bug")
        void literalBraceAtChunkBoundary() throws Exception {
            // Simulates: {"command":"echo }"} arriving as two streaming fragments
            // where the chunk boundary falls RIGHT AFTER the literal '}' in "echo }"
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"echo }");
            tcr.appendArguments("\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "echo }");
        }

        @Test
        @DisplayName("literal opening brace inside string value")
        void literalOpeningBraceAtChunkBoundary() throws Exception {
            // {"command":"echo {hello"} — '{' is inside the string value
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"echo {");
            tcr.appendArguments("hello\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "echo {hello");
        }

        @Test
        @DisplayName("both literal braces in same string value split at chunk boundary")
        void literalBracesAtBothBoundaries() throws Exception {
            // {"command":"echo {inner}"}  — both braces are literal
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"echo {inner}");
            tcr.appendArguments("\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "echo {inner}");
        }

        @Test
        @DisplayName("nested JSON as shell command argument")
        void nestedJsonInShellCommand() throws Exception {
            // AI sends: echo '{"key":"value"}'
            // JSON: {"command":"echo '{\"key\":\"value\"}'"}
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"echo '{\\\"key\\\":");
            tcr.appendArguments("\\\"value\\\"}'\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "echo '{\"key\":\"value\"}'");
        }

        @Test
        @DisplayName("awk-like script with multiple brace pairs")
        void awkScriptWithBraces() throws Exception {
            // Command: awk '{print $1}' file.txt
            // JSON: {"command":"awk '{print $1}' file.txt"}
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"awk '{print $1}'");
            tcr.appendArguments(" file.txt\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "awk '{print $1}' file.txt");
        }

        @Test
        @DisplayName("find -exec with literal braces")
        void findExecWithBraces() throws Exception {
            // Full JSON: {"command":"find . -name '*.java' -exec grep 'TODO' {} \\;"}
            // Split mid-way through the string
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"find . -name '*.java' -exec grep 'TODO' {}");
            tcr.appendArguments(" \\\\;\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command",
                    "find . -name '*.java' -exec grep 'TODO' {} \\;");
        }

        @Test
        @DisplayName("jq JSON query with literal braces")
        void jqQueryWithBraces() throws Exception {
            // Command: jq '.items[] | {id: .id, name: .name}' data.json
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"jq '.items[] | {id: .id, name: .name}'");
            tcr.appendArguments(" data.json\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command",
                    "jq '.items[] | {id: .id, name: .name}' data.json");
        }

        @Test
        @DisplayName("python one-liner with dict literal")
        void pythonDictLiteral() throws Exception {
            // python3 -c "print({'a':1}.get('a'))"
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"python3 -c \\\"print({'a':1}.get('a'))\\\"\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command",
                    "python3 -c \"print({'a':1}.get('a'))\"");
        }

        @Test
        @DisplayName("sed substitution with braces")
        void sedWithBraces() throws Exception {
            // sed 's/{old}/{new}/g' file.txt
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"sed 's/{old}");
            tcr.appendArguments("/{new}/g' file.txt\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "sed 's/{old}/{new}/g' file.txt");
        }

        @Test
        @DisplayName("three chunks with brace splits")
        void threeChunksWithBraces() throws Exception {
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"command\":\"echo 'st");
            tcr.appendArguments("art} midd");
            tcr.appendArguments("le {end}'\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("command", "echo 'start} middle {end}'");
        }

        @Test
        @DisplayName("script parameter with code block containing braces")
        void scriptWithCodeBlock() throws Exception {
            // Python script as a string parameter
            var tcr = new ModelResponse.ToolCallRequest();
            tcr.setArguments("{\"language\":\"python\",\"script\":\"def foo():\\n    if True:\\n        print('{");
            tcr.appendArguments("bar}");
            tcr.appendArguments("')\\n\"}");

            assertJsonValid(tcr.getArguments());
            Map<String, Object> parsed = parse(tcr.getArguments());
            assertThat(parsed).containsEntry("language", "python");
            assertThat((String) parsed.get("script")).contains("{bar}");
        }
    }

    // ── mergeChunks integration tests ────────────────────────────────

    @Nested
    @DisplayName("mergeChunks streaming assembly (ChatModel default)")
    class MergeChunks {

        /**
         * Builds a fake ChatModel just to call the default mergeChunks method.
         */
        private lyjew.com.lyclaw.chat.ChatModel dummyModel() {
            return new lyjew.com.lyclaw.chat.ChatModel() {
                @Override public String provider() { return "test"; }
                @Override public String model() { return "test"; }
                @Override public lyjew.com.lyclaw.chat.ModelCapabilities capabilities() {
                    return lyjew.com.lyclaw.chat.ModelCapabilities.openAiDefaults();
                }
                @Override public reactor.core.publisher.Flux<ModelResponse> stream(
                        lyjew.com.lyclaw.model.ChatRequest r) {
                    return reactor.core.publisher.Flux.empty();
                }
                @Override public int countTokens(String t) { return 0; }
            };
        }

        @Test
        @DisplayName("streaming fragments merged into valid JSON with literal braces")
        void streamingFragmentsWithLiteralBraces() throws Exception {
            // Simulate streaming chunks where arguments arrive as incremental deltas
            var chunk1 = ModelResponse.builder()
                    .content("")
                    .toolCalls(List.of(new ModelResponse.ToolCallRequest("call_1", "command", "{\"command\":\"echo }", 0)))
                    .build();
            var chunk2 = ModelResponse.builder()
                    .content("")
                    .toolCalls(List.of(new ModelResponse.ToolCallRequest("call_1", null, "\"}", 0)))
                    .build();

            ModelResponse merged = dummyModel().mergeChunks(List.of(chunk1, chunk2));

            assertThat(merged.getToolCalls()).hasSize(1);
            String args = merged.getToolCalls().get(0).getArguments();
            assertJsonValid(args);
            Map<String, Object> parsed = parse(args);
            assertThat(parsed).containsEntry("command", "echo }");
        }

        @Test
        @DisplayName("streaming fragments — find with -exec {}")
        void streamingFindExec() throws Exception {
            var chunk1 = ModelResponse.builder()
                    .content("")
                    .toolCalls(List.of(new ModelResponse.ToolCallRequest(
                            "call_2", "command",
                            "{\"command\":\"find . -type f -exec chmod 644 {} \\\\", 0)))
                    .build();
            var chunk2 = ModelResponse.builder()
                    .content("")
                    .toolCalls(List.of(new ModelResponse.ToolCallRequest(
                            "call_2", null, ";\"}", 0)))
                    .build();

            ModelResponse merged = dummyModel().mergeChunks(List.of(chunk1, chunk2));

            String args = merged.getToolCalls().get(0).getArguments();
            assertJsonValid(args);
            Map<String, Object> parsed = parse(args);
            assertThat(parsed).containsEntry("command", "find . -type f -exec chmod 644 {} \\;");
        }

        @Test
        @DisplayName("streaming fragments — multiple tools, one with braces")
        void multipleToolsOneWithBraces() throws Exception {
            // tool c2 command: curl -X POST -d '{"key":"val"}' http://api/v1{/id}
            // The JSON for this is: {"command":"curl -X POST -d '{\"key\":\"val\"}' http://api/v1{/id}"}
            // Streaming fragment 1:  {"command":"curl -X POST -d '{\"key\":
            // Streaming fragment 2:  \"val\"}' http://api/v1{/id}"}
            var chunk1 = ModelResponse.builder()
                    .content("")
                    .toolCalls(List.of(
                            new ModelResponse.ToolCallRequest("c1", "web_search",
                                    "{\"query\":\"best restaurants\"}", 0),
                            new ModelResponse.ToolCallRequest("c2", "command",
                                    "{\"command\":\"curl -X POST -d '{\\\"key\\\":", 1)))
                    .build();
            var chunk2 = ModelResponse.builder()
                    .content("")
                    .toolCalls(List.of(
                            new ModelResponse.ToolCallRequest("c2", null,
                                    "\\\"val\\\"}' http://api/v1{/id}\"}", 1)))
                    .build();

            ModelResponse merged = dummyModel().mergeChunks(List.of(chunk1, chunk2));

            assertThat(merged.getToolCalls()).hasSize(2);
            assertJsonValid(merged.getToolCalls().get(0).getArguments());
            String args2 = merged.getToolCalls().get(1).getArguments();
            assertJsonValid(args2);
            Map<String, Object> parsed2 = parse(args2);
            assertThat(parsed2).containsEntry("command",
                    "curl -X POST -d '{\"key\":\"val\"}' http://api/v1{/id}");
        }

        @Test
        @DisplayName("streaming fragments — sed substitute with braces split across many chunks")
        void sedManyChunks() throws Exception {
            var tcr = new ModelResponse.ToolCallRequest("c3", "command", "{\"command\":\"sed 's/\\\\{", 0);
            var chunks = List.of(
                    ModelResponse.builder().toolCalls(List.of(tcr)).build(),
                    ModelResponse.builder().toolCalls(List.of(
                            new ModelResponse.ToolCallRequest("c3", null, "OLD}", 0))).build(),
                    ModelResponse.builder().toolCalls(List.of(
                            new ModelResponse.ToolCallRequest("c3", null, "/\\\\{NEW}/g' f", 0))).build(),
                    ModelResponse.builder().toolCalls(List.of(
                            new ModelResponse.ToolCallRequest("c3", null, "ile\"}", 0))).build()
            );

            ModelResponse merged = dummyModel().mergeChunks(chunks);

            String args = merged.getToolCalls().get(0).getArguments();
            assertJsonValid(args);
            Map<String, Object> parsed = parse(args);
            assertThat(parsed).containsEntry("command", "sed 's/\\{OLD}/\\{NEW}/g' file");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> parse(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private static void assertJsonValid(String json) {
        try {
            mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("Invalid JSON: " + json, e);
        }
    }

    private static void assertJsonValid(String json, String message) {
        try {
            mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(message + " — Invalid JSON: " + json, e);
        }
    }
}
