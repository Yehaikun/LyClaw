package lyjew.com.lyclaw.orchestration.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import org.springframework.stereotype.Component;

import java.util.List;

@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeepSeekDTO {
    String id;
    String object;
    Long timestamp;
    String model;
    @JsonProperty("system_fingerprint")
    String systemFingerprint;
    List<Choices> choices;

    @NoArgsConstructor
    @Data
    public static class Choices{
        String index;
        Delta delta;
        String logprobs;

        @JsonProperty("finish_reason")
        String finishReason;

        @NoArgsConstructor
        @Data
        public static class Delta{
            String role;
            String content;

            @JsonProperty("reasoning_content")
            String reasoningContent;
        }
    }
}
