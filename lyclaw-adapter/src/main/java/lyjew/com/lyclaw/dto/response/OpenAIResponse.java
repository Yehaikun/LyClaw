package lyjew.com.lyclaw.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIResponse {

    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    private OpenAIUsage usage;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private int index;
        private ResponseMessage message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseMessage {
        private String role;
        private String content;
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        private String id;
        private String type;
        private FunctionCall function;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        private String name;
        private String arguments;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAIUsage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
    }

    public Choice getFirstChoice() {
        return choices != null && !choices.isEmpty() ? choices.get(0) : null;
    }

    public boolean isSuccess() {
        Choice first = getFirstChoice();
        return first != null && "stop".equals(first.getFinishReason());
    }
}
