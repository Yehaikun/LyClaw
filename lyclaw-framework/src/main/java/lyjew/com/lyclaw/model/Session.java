package lyjew.com.lyclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话模型，表示一次完整的 AI 对话会话。
 *
 * 每个会话通过 sessionId 唯一标识，维护完整的消息历史列表（messages）。
 * 支持命名和指定默认模型。提供 {@link #addMessage} 方法在对话过程中追加消息。
 * 继承自 BaseDTO，使用 @JsonIgnoreProperties 兼容序列化时的未知字段。
 *
 * <h3>持久化</h3>
 * 元数据存储在 SQLite sessions 表，转录记录以 JSONL 追加写入 filePath。
 * heartbeatMode=true 的会话不持久化（跳过 JSONL 写入和 SQLite 更新）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Session extends BaseDTO {
    /** 会话唯一标识 */
    private String sessionId;
    /** 会话名称，用于 UI 显示 */
    private String name;
    /** 会话默认使用的模型名称 */
    private String model;

    // ── 持久化字段（SQLite sessions 表映射） ──────────

    /** 所属 Agent ID（外键 → agents 表） */
    private String agentId;
    /** JSONL 转录文件相对路径（如 agents/{agentId}/sessions/{sessionId}.jsonl） */
    private String filePath;
    /** 当前消息序号（JSONL 行号，用于分页加载） */
    private int messageIndex;
    /** 已执行压缩的次数 */
    private int compactionCount;
    /** 父会话 ID（子 Agent 会话链接到父会话） */
    private String parentSessionId;
    /** 父 Agent ID（子 Agent 的创建者） */
    private String parentAgentId;
    /** 心跳模式：为 true 时跳过所有持久化写入 */
    private boolean heartbeatMode;
    // createdAt / updatedAt 继承自 BaseDTO（LocalDateTime 类型）

    /** 会话中的消息历史列表 */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    /**
     * 向会话追加一条消息。
     * 如果消息列表为 null（如反序列化未初始化），则先创建空列表。
     *
     * @param message 要追加的消息
     */
    public void addMessage(Message message) {
        // 防御性处理：messages 可能因反序列化而为 null
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        this.messageIndex = this.messages.size();
        this.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 是否为心跳会话（不持久化）。
     */
    public boolean isHeartbeatSession() {
        return heartbeatMode;
    }
}
