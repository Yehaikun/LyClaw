package lyjew.com.lyclaw.infra.metrics;

/**
 * 指标采集器接口，定义框架运行时各类指标的记录与快照获取规范。
 *
 * <p>框架内部的各个组件（LLM 调用、工具执行、流水线、记忆检索、Agent 任务等）
 * 通过此接口上报性能数据。实现类负责聚合这些数据并在调用{@link #getSnapshot()}
 * 时生成一个统一的{@link MetricsSnapshot}快照供告警模块消费。</p>
 */
public interface MetricsCollector {

    /**
     * 记录一次 LLM 调用。
     *
     * @param provider          服务提供商名称
     * @param model             模型名称
     * @param promptTokens      提示词 Token 数
     * @param completionTokens  补全 Token 数
     * @param latencyMs         调用延迟（毫秒）
     */
    void recordLlmCall(String provider, String model, int promptTokens, int completionTokens, long latencyMs);

    /**
     * 记录一次工具调用。
     *
     * @param toolName  工具名称
     * @param success   是否执行成功
     * @param latencyMs 调用延迟（毫秒）
     */
    void recordToolCall(String toolName, boolean success, long latencyMs);

    /**
     * 记录一个流水线阶段的耗时。
     *
     * @param stageName  阶段名称
     * @param durationMs 阶段耗时（毫秒）
     */
    void recordPipelineStage(String stageName, long durationMs);

    /**
     * 记录一次记忆检索操作。
     *
     * @param durationMs  检索耗时（毫秒）
     * @param resultCount 返回结果数量
     */
    void recordMemoryRetrieval(long durationMs, int resultCount);

    /**
     * 记录一次 Agent 任务执行。
     *
     * @param agentId    Agent 标识
     * @param success    是否执行成功
     * @param durationMs 任务耗时（毫秒）
     */
    void recordAgentTask(String agentId, boolean success, long durationMs);

    /**
     * 获取当前时刻的指标快照。
     *
     * @return 包含所有聚合指标的快照对象
     */
    MetricsSnapshot getSnapshot();
}
