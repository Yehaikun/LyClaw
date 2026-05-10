package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @Builder.Default
    private String sessionId = "";

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @Builder.Default
    private String systemPrompt = "";

    @Builder.Default
    private String model = "";

    private Integer maxTokens;

    @Builder.Default
    private boolean stream = false;

    private Double temperature;

    private Double topP;

    @Builder.Default
    private List<ToolDefinition> tools = new ArrayList<>();

    @Builder.Default
    private boolean thinkingEnabled = false;

    private Integer thinkingBudget;

    private String toolChoice;

    @Builder.Default
    private List<String> stopSequences = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();

    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isEmpty();
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    public String getLastUserMessage() {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("user".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    public int getMessageCount() {
        return messages != null ? messages.size() : 0;
    }
}
