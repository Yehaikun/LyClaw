package lyjew.com.lyclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.util.List;

/**
 * 消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message extends BaseDTO {

    private String role;        // system / user / assistant / tool

    private String content;     // 消息内容

    // assistant消息特有
    private String model;       // 使用的模型

    private Usage usage;        // token用量

    private List<ToolCall> toolCalls;  // 工具调用

    /** tool 角色消息特有关联 ID */
    private String toolCallId;      // 工具调用ID（role=tool时设置，关联tool_calls.id）
}
