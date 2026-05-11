package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

/**
 * 工具调用模型，记录 AI 模型发起的单次工具调用请求及结果。
 *
 * 包含工具调用的唯一 ID、工具名称、参数和最终返回结果。在消息流中，
 * assistant 消息包含 toolCalls 列表，tool 消息则通过 toolCallId 关联到
 * 具体的工具调用并携带 result。继承自 BaseDTO，使用 Lombok @Data/@SuperBuilder
 * 自动生成数据访问方法。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall extends BaseDTO {

    /** 工具调用的唯一标识 ID */
    private String toolCallId;
    /** 被调用的工具名称 */
    private String name;
    /** 工具调用描述 */
    private String description;
    /** JSON 格式的调用参数 */
    private String arguments;
    /** 工具执行返回的结果 */
    private String result;
}
