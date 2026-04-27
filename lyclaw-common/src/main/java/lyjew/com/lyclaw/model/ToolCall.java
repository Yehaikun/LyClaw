package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.time.LocalDateTime;

/**
 * 工具调用记录
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall extends BaseDTO {


    private String toolCallId;     // 工具调用ID
    private String name;          // 工具名称
    private String description;
    private String arguments;      // 工具参数（JSON字符串）
    private String result;        // 执行结果
}
