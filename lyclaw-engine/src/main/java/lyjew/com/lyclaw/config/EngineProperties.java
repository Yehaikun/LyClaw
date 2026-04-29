package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 引擎配置属性类 —— 绑定 application.yml 中 lyclaw.engine.* 配置项。
 *
 * <p>所有引擎组件通过注入 EngineProperties 获取配置，
 * 不需要各自从 Environment 手动读取。配置变更只需改 yml 文件，
 * 不需要改代码。</p>
 *
 * <p><b>配置示例</b>：
 * <pre>
 * lyclaw:
 *   engine:
 *     data-dir: ./LyClaw
 *     default-provider: minimax
 *     enabled: true
 *     pipeline:
 *       timeout: 30000
 *       max-tool-rounds: 10
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConfigurationProperties(prefix = "lyclaw.engine")
public class EngineProperties {

    /** 数据目录 —— 记忆、会话、日志等文件的存储根目录，默认 ./LyClaw */
    private String dataDir = "./LyClaw";

    /** 默认模型厂商 —— ToolCallLoopStage 通过 ModelProvider.getAdapter(defaultProvider) 获取适配器 */
    private String defaultProvider = "minimax";

    /** 引擎是否启用 —— false 时 EngineSelector 返回 fallback 结果，不执行完整管道 */
    private boolean enabled = true;

    /** 管道相关配置 */
    private final Pipeline pipeline = new Pipeline();

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Pipeline getPipeline() { return pipeline; }

    /**
     * 管道配置 —— 嵌套静态类，对应 lyclaw.engine.pipeline.*。
     */
    public static class Pipeline {

        /** 管道执行超时（毫秒），默认 30 秒 */
        private long timeout = 30000L;

        /** 最大工具调用轮次，默认 10 */
        private int maxToolRounds = 10;

        public long getTimeout() { return timeout; }
        public void setTimeout(long timeout) { this.timeout = timeout; }

        public int getMaxToolRounds() { return maxToolRounds; }
        public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }
    }
}