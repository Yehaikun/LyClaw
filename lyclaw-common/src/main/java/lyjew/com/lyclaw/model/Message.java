package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.Builder.Default;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;


import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Message extends BaseDTO {

    private String role;        // system / user / assistant / tool

    private String content;     // 消息内容

    // assistant消息特有
    private String model;       // 使用的模型

    private Usage usage;        // token用量

    private List<ToolCall> toolCalls;  // 工具调用
}