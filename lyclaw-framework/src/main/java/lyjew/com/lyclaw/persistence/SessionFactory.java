package lyjew.com.lyclaw.persistence;

import lyjew.com.lyclaw.model.Session;

/**
 * 会话工厂接口——供framework层的SubagentSpawner使用，
 * 避免直接依赖web层的SessionManager。
 *
 * lyclaw-web中的SessionManager实现此接口，
 * 在StorageAutoConfiguration中通过@Bean暴露为SessionFactory类型。
 * lyclaw-framework中的SubagentSpawner通过构造函数注入此接口，
 * 形成依赖倒置（DIP）：framework定义接口，web提供实现。
 */
public interface SessionFactory {
    /**
     * 创建子Agent会话——当父Agent执行delegate_to_agent时调用。
     * @param parentSessionId 父会话ID（用于建立父子层级关系）
     * @param parentAgentId 父Agent ID
     * @param childAgentId 被生成的子Agent ID
     * @param model 子Agent使用的模型（通常继承父Agent的模型配置）
     * @return 新创建的子会话（含sessionId、filePath等信息）
     */
    Session createSubagentSession(String parentSessionId, String parentAgentId,
                                   String childAgentId, String model);

    /**
     * 获取某Agent的活跃子会话数——用于限制最大并发子Agent数量。
     * @param agentId Agent ID
     * @return 该Agent当前活跃的子会话总数
     */
    int getActiveCount(String agentId);
}
