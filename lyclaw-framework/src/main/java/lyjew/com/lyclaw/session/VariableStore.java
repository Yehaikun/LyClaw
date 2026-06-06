package lyjew.com.lyclaw.session;

import java.util.Map;
import java.util.Optional;

/**
 * 会话变量存储 SPI —— 跨对话轮次保留的会话级键值存储。
 *
 * <p>用于在对话过程中保持状态：AgentHook 可在 beforeRequest 中写入，
 * afterResult 中读取，实现跨轮次的上下文传递。</p>
 *
 * <p>典型用途：
 * <ul>
 *   <li>记录当前正在执行的子任务状态</li>
 *   <li>缓存中间计算结果</li>
 *   <li>记录用户偏好设置（对话期间有效）</li>
 * </ul>
 *
 * <p>会话变量的生命周期与会话绑定：会话删除时变量随之清除。
 * 默认实现 {@link lyjew.com.lyclaw.session.InMemoryVariableStore}。</p>
 *
 * @see lyjew.com.lyclaw.session.SessionStore
 * @see lyjew.com.lyclaw.session.MessageStore
 */
public interface VariableStore {

    /** 设置会话变量 */
    void set(String sessionId, String key, Object value);

    /** 批量设置会话变量 */
    void setAll(String sessionId, Map<String, Object> values);

    /** 获取会话变量 */
    <T> Optional<T> get(String sessionId, String key, Class<T> type);

    /** 获取会话所有变量 */
    Map<String, Object> getAll(String sessionId);

    /** 删除指定变量 */
    <T> Optional<T> remove(String sessionId, String key);

    /** 清空会话所有变量 */
    void clear(String sessionId);

    /** 判断变量是否存在 */
    boolean exists(String sessionId, String key);
}
