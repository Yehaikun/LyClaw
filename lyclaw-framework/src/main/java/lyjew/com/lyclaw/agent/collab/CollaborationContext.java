package lyjew.com.lyclaw.agent.collab;

import lombok.Builder;
import lombok.Data;
import lyjew.com.lyclaw.agent.AgentHandle;

import java.util.List;
import java.util.Map;

/**
 * 协作上下文，保存一次多代理协作运行的完整运行时信息。
 *
 * CollaborationContext 是每次协作执行的会话容器，记录了协作的唯一标识、
 * 使用的协作模式、所有参与者、共享状态、最大交互轮数和超时时间。
 * sharedState 为并发访问的共享 Map，允许参与协作的代理在协作期间读写
 * 公共数据，实现信息交换。提供类型安全的 getState 泛型方法和 setState
 * 方法简化状态存取。maxRounds 和 timeoutMs 用于防止协作无限循环或死锁。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class CollaborationContext {

    /** 协作会话的唯一标识 */
    private String collaborationId;
    /** 所使用的协作模式标识 */
    private String modeId;
    /** 参与本次协作的代理句柄列表 */
    private List<AgentHandle> participants;
    /** 协作共享状态，代理间通过此 Map 交换数据 */
    private Map<String, Object> sharedState;
    /** 最大交互轮数，避免无限循环 */
    private int maxRounds;
    /** 协作超时时间（毫秒），超时后强制结束 */
    private long timeoutMs;

    /**
     * 以类型安全的方式从共享状态中读取数据。
     *
     * @param key  状态键名
     * @param <T>  期望的返回值类型
     * @return 对应键的值，类型由调用方推断
     */
    @SuppressWarnings("unchecked")
    public <T> T getState(String key) {
        // 从共享状态 Map 中取值，使用强转实现泛型返回
        return (T) sharedState.get(key);
    }

    /**
     * 向共享状态中写入或更新数据。
     *
     * @param key   状态键名
     * @param value 待存储的值
     */
    public void setState(String key, Object value) {
        sharedState.put(key, value);
    }
}
