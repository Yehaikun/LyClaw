package lyjew.com.lyclaw.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {

    private String id;
    private String type;
    private String role;
    private String model;
    private List<ContentBlock> content;
    private AnthropicUsage usage;
    @JsonProperty("stop_reason")
    private String stopReason;
    @JsonProperty("base_resp")
    private BaseResp baseResp;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentBlock {
        private String type;
        private String thinking;
        private String signature;
        private String text;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnthropicUsage {
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BaseResp {
        @JsonProperty("status_code")
        private int statusCode;
        @JsonProperty("status_msg")
        private String statusMsg;

        public boolean isSuccess() {
            return statusCode == 0;
        }
    }
}
