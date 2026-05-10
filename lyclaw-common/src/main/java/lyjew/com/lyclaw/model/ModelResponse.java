package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelResponse {

    private String id;
    private String content;
    private String thinking;
    private String model;
    private List<ToolCallRequest> toolCalls;
    private String finishReason;
    private Usage usage;
    private Map<String, Object> metadata;

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean hasThinking() {
        return thinking != null && !thinking.isEmpty();
    }

    public boolean isStopped() {
        return "stop".equals(finishReason);
    }

    public boolean isTruncated() {
        return "length".equals(finishReason);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallRequest {
        private String id;
        private String name;
        private String arguments;
        private int index;

        public void appendArguments(String argsFragment) {
            if (argsFragment == null || argsFragment.isEmpty()) return;
            if (this.arguments == null || this.arguments.isEmpty()) {
                this.arguments = argsFragment;
            } else {
                String base = this.arguments.trim();
                String frag = argsFragment.trim();
                if (base.endsWith("}")) {
                    base = base.substring(0, base.length() - 1);
                }
                if (frag.startsWith("{")) {
                    frag = frag.substring(1);
                }
                this.arguments = base + frag;
            }
        }
    }
}
