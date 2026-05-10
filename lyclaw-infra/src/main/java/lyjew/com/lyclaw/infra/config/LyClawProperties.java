package lyjew.com.lyclaw.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LyClaw 分层配置属性 —— 系统级 → 引擎级 → 会话级 → 请求级。
 *
 * <p>绑定 application.yml 中的 lyclaw.* 配置树。</p>
 *
 * @since 2.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "lyclaw")
public class LyClawProperties {

    private MemoryProperties memory = new MemoryProperties();
    private SecurityProperties security = new SecurityProperties();
    private MetricsProperties metrics = new MetricsProperties();
    private AgentProperties agent = new AgentProperties();

    @Data
    public static class MemoryProperties {
        private boolean enabled = true;
        private String vectorStore = "inmemory";
        private EmbeddingProperties embedding = new EmbeddingProperties();
        private TemporalProperties temporal = new TemporalProperties();
        private RetrievalProperties retrieval = new RetrievalProperties();
        private ConsolidatorProperties consolidator = new ConsolidatorProperties();
        private JanitorProperties janitor = new JanitorProperties();
    }

    @Data
    public static class EmbeddingProperties {
        private String model = "local-onnx";
        private int dimension = 768;
    }

    @Data
    public static class TemporalProperties {
        private String decayModel = "exponential";
        private int halfLifeDays = 30;
    }

    @Data
    public static class RetrievalProperties {
        private int topK = 20;
        private double alpha = 0.45;
        private double beta = 0.20;
        private double gamma = 0.15;
        private double delta = 0.20;
    }

    @Data
    public static class ConsolidatorProperties {
        private String cron = "0 0 * * * *";
    }

    @Data
    public static class JanitorProperties {
        private String cron = "0 0 2 * * *";
        private double duplicateThreshold = 0.85;
    }

    @Data
    public static class SecurityProperties {
        private boolean enabled = true;
        private String defaultPermissionLevel = "EXECUTE_SAFE";
        private boolean auditEnabled = true;
    }

    @Data
    public static class MetricsProperties {
        private boolean enabled = true;
        private String backend = "micrometer";
    }

    @Data
    public static class AgentProperties {
        private int maxConcurrent = 5;
        private int poolSize = 10;
        private long defaultTimeoutMs = 300000;
        private ScalingProperties scaling = new ScalingProperties();
    }

    @Data
    public static class ScalingProperties {
        private boolean enabled = true;
        private double targetIdleRatio = 0.3;
        private int maxQueueDepth = 20;
    }
}
