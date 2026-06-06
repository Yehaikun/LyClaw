package lyjew.com.lyclaw.session;

import java.util.List;

import lyjew.com.lyclaw.model.Message;

/**
 * 消息存储 SPI —— 追加型消息存取接口。
 *
 * <p>与 {@link SessionStore} 分离设计，因为消息的读写模式与会话元数据完全不同：
 * <ul>
 *   <li>消息是<strong>追加密集型</strong>——每条对话轮次追加 N 条消息</li>
 *   <li>消息需要<strong>分页查询</strong>和<strong>序号定位</strong></li>
 *   <li>消息可能有独立的存储后端需求（时序库、分表）</li>
 * </ul>
 *
 * <p>默认实现 {@link lyjew.com.lyclaw.session.InMemoryMessageStore} 为内存模式。
 * 生产环境可替换为 SQLite、PostgreSQL、Redis 等实现。</p>
 *
 * @see lyjew.com.lyclaw.session.SessionStore
 * @see lyjew.com.lyclaw.session.VariableStore
 */
public interface MessageStore {

    /**
     * 追加单条消息，返回分配的序号（从 0 开始递增）。
     *
     * @param sessionId 会话 ID
     * @param message   消息对象
     * @return 分配的序号
     */
    int append(String sessionId, Message message);

    /**
     * 批量追加消息，返回分配的序号数组。
     *
     * @param sessionId 会话 ID
     * @param messages  消息列表
     * @return 分配的序号数组（与 messages 顺序一致）
     */
    int[] appendBatch(String sessionId, List<Message> messages);

    /**
     * 分页查询消息（按时间正序）。
     *
     * @param sessionId 会话 ID
     * @param offset    跳过的消息数（从 0 开始）
     * @param limit     最大返回条数
     * @return 消息列表
     */
    List<Message> load(String sessionId, int offset, int limit);

    /**
     * 加载最近 N 条消息（用于构建 LLM 上下文）。
     *
     * @param sessionId 会话 ID
     * @param lastN     返回最近多少条
     * @return 最近 N 条消息，按时间正序
     */
    List<Message> loadLatest(String sessionId, int lastN);

    /**
     * 从指定序号开始加载所有后续消息（续传 / 增量同步场景）。
     *
     * @param sessionId  会话 ID
     * @param afterIndex 起始序号（不包含）
     * @return 序号 {@code > afterIndex} 的消息列表
     */
    List<Message> loadSince(String sessionId, int afterIndex);

    /**
     * 更新指定序号消息的内容（用于流式消息内容逐步写入）。
     *
     * @param sessionId 会话 ID
     * @param index     消息序号
     * @param content   新的内容
     */
    void updateContent(String sessionId, int index, String content);

    /**
     * 删除指定序号之前的消息（上下文裁剪）。
     *
     * @param sessionId  会话 ID
     * @param keepLastN  保留最近 N 条，之前的删除
     * @return 实际删除的消息数
     */
    int pruneBefore(String sessionId, int keepLastN);

    /**
     * 删除会话的所有消息。
     *
     * @param sessionId 会话 ID
     */
    void deleteBySession(String sessionId);

    /**
     * 获取会话的消息总数。
     *
     * @param sessionId 会话 ID
     * @return 消息数
     */
    int count(String sessionId);
}
