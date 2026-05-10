package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 感知数据 —— 从对话轮次中提取的原始感知信息。
 *
 * @since 2.0
 */
@Data
@Builder
public class PerceptionData {

    /** 消息角色 (user/assistant/system/tool) */
    private String role;

    /** 消息内容 */
    private String content;

    /** 消息时间戳 */
    private long timestamp;

    /** 关联的工具调用记录 */
    private List<String> toolCallIds;

    /** 关联的标记 */
    private Map<String, Object> metadata;
}
