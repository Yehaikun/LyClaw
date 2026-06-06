package lyjew.com.lyclaw.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.SessionQuery;
import lyjew.com.lyclaw.model.SessionStatus;
import lyjew.com.lyclaw.model.SessionTree;

/**
 * 会话服务门面 —— 框架业务代码操作会话的唯一入口。
 *
 * <p>组合 {@link SessionStore}（元数据）、{@link MessageStore}（消息）、
 * {@link VariableStore}（变量），并应用 {@link SessionWritePolicy}（写策略）
 * 和 {@link ContextPolicy}（上下文裁剪）。</p>
 *
 * <p>AgentInvocationHandler、ChatController 等框架代码统一通过此接口操作会话，
 * 不直接调用底层 SPI。</p>
 */
public interface SessionService {

    // ── 会话元数据管理 ──

    /** 获取或创建会话 */
    Session getOrCreate(String sessionId, String agentId, String model);

    /** 创建新会话 */
    Session create(String agentId, String model);

    /** 获取会话（不含消息列表） */
    Optional<Session> get(String sessionId);

    /** 更新会话元数据（名称、标签等） */
    void update(String sessionId, SessionUpdate update);

    /** 删除会话及其所有消息 */
    void delete(String sessionId);

    /** 标记会话状态 */
    void markStatus(String sessionId, SessionStatus status);

    /** 分页查询会话列表 */
    List<Session> list(SessionQuery query);

    /** 获取会话总数（可选过滤） */
    int count(SessionQuery query);

    /**
     * 简易列表查询（基于 agentId 过滤，返回 Map 形式兼容旧版前端）。
     *
     * @deprecated 使用 {@link #list(SessionQuery)} 代替
     */
    @Deprecated
    default List<Map<String, Object>> listSessions(String agentId) {
        SessionQuery query = SessionQuery.builder().agentId(agentId).build();
        return list(query).stream().map(this::sessionToMap).toList();
    }

    /** 将 Session 转为前端兼容的 Map 格式 */
    default Map<String, Object> sessionToMap(Session s) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("sessionId", s.getSessionId());
        map.put("session_id", s.getSessionId());
        map.put("name", s.getName());
        map.put("agentId", s.getAgentId() != null ? s.getAgentId() : s.getName());
        map.put("agent_id", s.getAgentId() != null ? s.getAgentId() : s.getName());
        map.put("model", s.getModel());
        map.put("messageCount", s.getMessageCount());
        map.put("message_count", s.getMessageCount());
        map.put("createdAt", s.getCreatedAt());
        map.put("updatedAt", s.getUpdatedAt());
        map.put("created_at", s.getCreatedAt());
        map.put("updated_at", s.getUpdatedAt());
        map.put("firstMsgPreview", "");
        map.put("first_msg_preview", "");
        map.put("status", s.getStatus() != null ? s.getStatus().name() : "ACTIVE");
        return map;
    }

    // ── 消息操作 ──

    /** 追加单条消息 */
    void appendMessage(String sessionId, Message message);

    /** 批量追加消息 */
    void appendMessages(String sessionId, List<Message> messages);

    /** 分页查询消息 */
    List<Message> loadMessages(String sessionId, int offset, int limit);

    /** 加载最近 N 条消息 */
    List<Message> loadLatestMessages(String sessionId, int lastN);

    /** 获取消息总数 */
    int messageCount(String sessionId);

    // ── 会话变量 ──

    void setVariable(String sessionId, String key, Object value);
    void setVariables(String sessionId, Map<String, Object> values);
    <T> Optional<T> getVariable(String sessionId, String key, Class<T> type);
    Map<String, Object> getAllVariables(String sessionId);
    void clearVariables(String sessionId);

    // ── 上下文构建（LLM 调用前调用） ──

    /**
     * 加载消息并根据 ContextPolicy 裁剪，返回送入 LLM 的消息列表。
     * 如果 policy 为 null，使用默认策略。
     */
    List<Message> buildContext(String sessionId, ContextPolicy policy);

    /**
     * 强制 flush 待写入数据（写策略未提交的 pending 消息）。
     */
    void flush(String sessionId);

    // ── 多 Agent 会话树 ──

    /**
     * 创建子会话（多 Agent 委托时调用）。
     *
     * @param parentSessionId 父会话 ID
     * @param agentId         子 Agent 标识
     * @param task            子 Agent 接收的任务
     * @return 创建的子会话
     */
    Session createChildSession(String parentSessionId, String agentId, String task);

    /**
     * 获取会话树（根会话 + 所有子会话分支）。
     */
    SessionTree getSessionTree(String sessionId);

    /**
     * 获取指定会话的所有直接子会话。
     */
    List<Session> getChildSessions(String parentSessionId);
}
