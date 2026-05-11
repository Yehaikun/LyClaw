package lyjew.com.lyclaw.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LyClaw 应用的全局配置属性类，绑定 Spring {@code lyclaw.*} 前缀的配置项。
 *
 * <p>通过 {@code @ConfigurationProperties(prefix = "lyclaw")} 自动映射
 * application.yml/properties 中的配置到内嵌的属性类。
 * 同时标注 {@code @Component} 确保被 Spring 容器管理。</p>
 *
 * <p>四大配置模块：
 * <ul>
 *   <li>{@link MemoryProperties}：记忆系统配置（向量存储、嵌入模型、检索、整合、清理）</li>
 *   <li>{@link SecurityProperties}：安全配置（启用开关、默认权限级别、审计）</li>
 *   <li>{@link MetricsProperties}：指标配置（启用开关、后端类型）</li>
 *   <li>{@link AgentProperties}：Agent 配置（并发、池大小、超时、弹性伸缩）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "lyclaw")
public class LyClawProperties {

    /** 记忆系统配置 */
    private MemoryProperties memory = new MemoryProperties();
    /** 安全配置 */
    private SecurityProperties security = new SecurityProperties();
    /** 指标收集配置 */
    private MetricsProperties metrics = new MetricsProperties();
    /** Agent 运行时配置 */
    private AgentProperties agent = new AgentProperties();

    /**
     * 记忆系统配置。
     *
     * <p>包含向量存储后端、嵌入模型、时间衰减、检索参数、
     * 记忆整合和记忆清理等子配置。</p>
     */
    @Data
    public static class MemoryProperties {
        /** 是否启用记忆系统，默认 true */
        private boolean enabled = true;
        /** 向量存储后端类型，默认 "inmemory" */
        private String vectorStore = "inmemory";
        /** 嵌入模型配置 */
        private EmbeddingProperties embedding = new EmbeddingProperties();
        /** 时间衰减配置 */
        private TemporalProperties temporal = new TemporalProperties();
        /** 记忆检索配置 */
        private RetrievalProperties retrieval = new RetrievalProperties();
        /** 记忆整合配置 */
        private ConsolidatorProperties consolidator = new ConsolidatorProperties();
        /** 记忆清理配置 */
        private JanitorProperties janitor = new JanitorProperties();
    }

    /**
     * 嵌入模型配置。
     */
    @Data
    public static class EmbeddingProperties {
        /** 嵌入模型名称，默认 "local-onnx" */
        private String model = "local-onnx";
        /** 嵌入向量维度，默认 768 */
        private int dimension = 768;
    }

    /**
     * 时间衰减配置，用于控制记忆随时间淡化的行为。
     */
    @Data
    public static class TemporalProperties {
        /** 衰减模型，默认 "exponential"（指数衰减） */
        private String decayModel = "exponential";
        /** 半衰期天数，默认 30 天 */
        private int halfLifeDays = 30;
    }

    /**
     * 记忆检索配置，控制混合检索的权重参数。
     *
     * <p>alpha + beta + gamma + delta 通常合计为 1.0，
     * 分别对应语义相似度、时间衰减、关键词匹配、重要性评分的权重。</p>
     */
    @Data
    public static class RetrievalProperties {
        /** 最大返回结果数，默认 20 */
        private int topK = 20;
        /** 语义相似度权重，默认 0.45 */
        private double alpha = 0.45;
        /** 时间衰减权重，默认 0.20 */
        private double beta = 0.20;
        /** 关键词匹配权重，默认 0.15 */
        private double gamma = 0.15;
        /** 重要性评分权重，默认 0.20 */
        private double delta = 0.20;
    }

    /**
     * 记忆整合调度配置，控制后台整合任务的执行频率。
     */
    @Data
    public static class ConsolidatorProperties {
        /** Cron 表达式，默认每小时执行一次 ("0 0 * * * *") */
        private String cron = "0 0 * * * *";
    }

    /**
     * 记忆清理配置，控制过期和重复记忆的清理策略。
     */
    @Data
    public static class JanitorProperties {
        /** Cron 表达式，默认每天凌晨 2 点执行 ("0 0 2 * * *") */
        private String cron = "0 0 2 * * *";
        /** 重复记忆判定阈值（余弦相似度），默认 0.85 */
        private double duplicateThreshold = 0.85;
    }

    /**
     * 安全配置。
     */
    @Data
    public static class SecurityProperties {
        /** 是否启用安全模块，默认 true */
        private boolean enabled = true;
        /** 默认权限级别，默认 "EXECUTE_SAFE" */
        private String defaultPermissionLevel = "EXECUTE_SAFE";
        /** 是否启用审计日志，默认 true */
        private boolean auditEnabled = true;
    }

    /**
     * 指标收集配置。
     */
    @Data
    public static class MetricsProperties {
        /** 是否启用指标收集，默认 true */
        private boolean enabled = true;
        /** 指标后端类型，默认 "micrometer" */
        private String backend = "micrometer";
    }

    /**
     * Agent 运行时配置，控制 Agent 池的并发和超时参数。
     */
    @Data
    public static class AgentProperties {
        /** 最大并发 Agent 数，默认 5 */
        private int maxConcurrent = 5;
        /** Agent 线程池大小，默认 10 */
        private int poolSize = 10;
        /** 默认任务超时时间（毫秒），默认 300000 (5分钟) */
        private long defaultTimeoutMs = 300000;
        /** 弹性伸缩配置 */
        private ScalingProperties scaling = new ScalingProperties();
    }

    /**
     * Agent 弹性伸缩配置，根据负载动态调整 Agent 池大小。
     */
    @Data
    public static class ScalingProperties {
        /** 是否启用弹性伸缩，默认 true */
        private boolean enabled = true;
        /** 目标空闲率，默认 0.30（30%） */
        private double targetIdleRatio = 0.3;
        /** 最大队列深度，默认 20 */
        private int maxQueueDepth = 20;
    }
}
