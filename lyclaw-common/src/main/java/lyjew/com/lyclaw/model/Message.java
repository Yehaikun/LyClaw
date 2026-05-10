package lyjew.com.lyclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message extends BaseDTO {

    private String role;
    private String content;
    private String model;
    private Usage usage;
    private List<ToolCall> toolCalls;
    private String toolCallId;

    public static Message user(String content) {
        return Message.builder().role("user").content(content).build();
    }

    public static Message assistant(String content) {
        return Message.builder().role("assistant").content(content).build();
    }

    public static Message system(String content) {
        return Message.builder().role("system").content(content).build();
    }

    public static Message tool(String toolCallId, String content) {
        return Message.builder().role("tool").toolCallId(toolCallId).content(content).build();
    }
}
