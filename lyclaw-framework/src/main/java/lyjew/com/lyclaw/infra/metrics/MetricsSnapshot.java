package lyjew.com.lyclaw.infra.metrics;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 指标快照，封装某一时刻框架运行的核心性能指标数据。
 *
 * <p>作为不可变数据传输对象（DTO），由{@link MetricsCollector#getSnapshot()}生成，
 * 供{@link lyjew.com.lyclaw.infra.alert.AlertManager#check}进行告警评估。
 * 使用 Lombok 的 {@code @Data} 和 {@code @Builder} 注解自动生成 getter/setter 与构建器。</p>
 */
@Data
@Builder
public class MetricsSnapshot {

    /** LLM 调用总次数 */
    private long totalLlmCalls;
    /** 消耗的 Token 总量 */
    private long totalTokensConsumed;
    /** 工具调用总次数 */
    private long totalToolCalls;
    /** 工具调用失败次数 */
    private long failedToolCalls;
    /** LLM 平均延迟（毫秒） */
    private double avgLlmLatencyMs;
    /** 工具平均延迟（毫秒） */
    private double avgToolLatencyMs;
    /** 流水线运行总次数 */
    private long totalPipelineRuns;
    /** 流水线平均耗时（毫秒） */
    private double avgPipelineDurationMs;
    /** Agent 任务总数 */
    private long totalAgentTasks;
    /** Agent 任务失败次数 */
    private long failedAgentTasks;
    /** 各阶段耗时映射，键为阶段名称，值为耗时（毫秒） */
    private Map<String, Long> stageDurations;
    /** 快照时间戳 */
    private long timestamp;
}
