package lyjew.com.lyclaw.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {

    private String model;
    private String system;
    private List<Message> messages;

    @Builder.Default
    private boolean stream = false;

    @JsonProperty("max_tokens")
    private Integer maxTokens;
    private Double temperature;
    @JsonProperty("top_p")
    private Double topP;
    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

    private List<Map<String, Object>> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;
    private Thinking thinking;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private Object content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Thinking {
        private String type;
        @JsonProperty("budget_tokens")
        private Integer budgetTokens;
    }
}
