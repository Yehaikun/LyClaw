package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

/**
 * 工具调用模型（Tool Call），记录 AI 大模型发起的单次工具调用请求及其执行结果。
 *
 * <p>在 LLM Agent 架构中，AI 模型在处理用户请求时，可能会决定调用外部工具来获取信息
 * 或执行操作（如搜索互联网、查询数据库、执行计算等）。ToolCall 类就是在消息流中
 * 表示这种"工具调用"的数据模型，它完整地记录了每次工具调用的生命周期数据：AI 模型
 * 通过 assistant 消息中的 tool_calls 数组声明它要调用哪些工具（携带工具名和参数），
 * 框架执行工具后通过 tool 消息携带 tool_call_id 和 result 将执行结果反馈给模型。
 * ToolCall 类正是这两类消息中"工具调用"部分的数据载体。
 *
 * <p>核心字段及其在消息流中的角色：
 * <ul>
 *   <li><b>toolCallId</b>：工具调用的全局唯一标识符，由 AI 模型在发起调用时生成
 *       （通常为 "call_" 前缀加上随机字符串）。此 ID 是关联 assistant 消息中的调用请求
 *       和 tool 消息中执行结果的关键纽带，框架依赖此字段匹配请求和响应</li>
 *   <li><b>name</b>：被调用的工具名称，必须与 {@link ToolDefinition} 中注册的名称
 *       完全匹配。框架的 ToolRegistry 通过此名称查找对应的工具执行器并调用</li>
 *   <li><b>description</b>：工具调用的描述信息，记录工具的功能说明，方便日志阅读
 *       和调试时快速了解调用意图</li>
 *   <li><b>arguments</b>：JSON 格式的调用参数字符串，由 AI 模型根据 ToolDefinition
 *       中定义的参数 Schema 生成。框架在调用工具前会将其解析为具体的参数对象。
 *       例如 {@code {"query": "今天天气", "location": "北京"}}</li>
 *   <li><b>result</b>：工具执行返回的结果字符串，由框架的工具执行器填充。AI 模型
 *       会根据此结果决定下一步行动（是否继续调用工具、总结结果、回答用户等）</li>
 * </ul>
 *
 * <p>在消息流中的典型位置：在 OpenAI 兼容协议中，assistant 角色的消息包含
 * tool_calls 列表（仅含 toolCallId、name、arguments），对应的 tool 角色消息则包含
 * tool_call_id（引用 assistant 消息中的 toolCallId）和 content（即 result 字段的值），
 * 两者通过 tool_call_id/toolCallId 实现引用关联。
 *
 * <p>继承自 {@link lyjew.com.lyclaw.base.BaseDTO}，使用 Lombok 的 {@code @Data}
 * 和 {@code @SuperBuilder} 注解自动生成 getter/setter 和建造者模式方法，支持在父类
 * 字段基础上的链式构建。
 *
 * @see lyjew.com.lyclaw.model.ToolDefinition
 * @see lyjew.com.lyclaw.model.Message
 * @see lyjew.com.lyclaw.tool.ToolExecutionResult
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
