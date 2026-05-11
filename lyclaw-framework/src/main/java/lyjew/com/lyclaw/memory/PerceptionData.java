package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 感知数据对象，封装单条对话消息的原始输入。
 *
 * 作为记忆系统的入口数据——从对话流中采样的每条消息（用户消息、助手回复、
 * 工具调用结果等）都会被包装为 PerceptionData 后送入 {@link MemorySystem#ingestPerception}。
 * 系统随后从中提取关键信息生成短期/长期记忆。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等样板方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerceptionData {
    /** 消息角色（如 "user"、"assistant"、"tool"） */
    private String role;
    /** 消息正文内容 */
    private String content;
    /** 消息产生的时间戳（毫秒） */
    private long timestamp;
    /** 该消息关联的工具调用 ID 列表 */
    private List<String> toolCallIds;
    /** 扩展元数据（如模型名称、token 用量等） */
    private Map<String, Object> metadata;
}
