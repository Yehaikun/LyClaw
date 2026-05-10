package lyjew.com.lyclaw.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SSE event writer utility -- extracted from ChatController for reusability.
 *
 * <p>Parses raw SSE data lines from Flux elements and writes
 * properly formatted SSE events to the OutputStream.</p>
 */
public final class SseEmitterWriter {

    private SseEmitterWriter() {
        // utility class
    }

    /**
     * Parse a Flux SSE data line and write formatted SSE events to the OutputStream.
     *
     * <p>Input format: data: {"choices":[{"delta":{"content":"Hello"}}]}</p>
     * <p>Output format: event:message\ndata:Hello\n\n</p>
     *
     * @param os     the output stream to write to
     * @param line   the raw Flux element (may have "data:" prefix)
     * @param mapper Jackson ObjectMapper instance
     */
    public static void writeEvent(OutputStream os, String line, ObjectMapper mapper) throws Exception {
        // Strip "data:" prefix
        String raw = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
        if (raw.isEmpty()) return;

        if ("[DONE]".equals(raw)) {
            os.write("event:done\ndata:[DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            return;
        }

        if (raw.startsWith("{")) {
            JsonNode root = mapper.readTree(raw);

            // Tool call events -- use event:tool_call format
            if (root.has("type") && "tool_call".equals(root.get("type").asText())) {
                os.write("event:tool_call\n".getBytes(StandardCharsets.UTF_8));
                os.write("data:".getBytes(StandardCharsets.UTF_8));
                os.write(raw.getBytes(StandardCharsets.UTF_8));
                os.write("\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                return;
            }

            // Standard OpenAI SSE -- extract delta.content
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                    String content = delta.get("content").asText();
                    if (!content.isEmpty()) {
                        os.write("event:message\n".getBytes(StandardCharsets.UTF_8));
                        os.write("data:".getBytes(StandardCharsets.UTF_8));
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                        os.write("\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                }
            }
        } else {
            // Non-JSON, pass through as-is
            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }
}
