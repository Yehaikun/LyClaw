package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.config.LyClawProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LyClaw 配置信息 Actuator 端点，通过 HTTP 暴露当前运行时的框架配置快照。
 *
 * <p>该端点通过 Spring Boot Actuator 的 {@code @Endpoint} 和 {@code @ReadOperation}
 * 机制对外提供只读的配置查询接口，端点 ID 为 {@code lyclaw-config}，访问路径为
 * {@code /actuator/lyclaw-config}。返回的配置信息涵盖了 LyClaw 框架的五大核心模块：
 * LLM 大语言模型配置、Pipeline 管道配置、Tools 工具配置、Sandbox 沙箱安全配置
 * 以及 Agent 代理配置。</p>
 *
 * <p><b>安全性设计：</b>API Key 等敏感信息会被自动脱敏处理——仅显示密钥的前4位和
 * 最后2位字符，中间部分用四个星号（****）替代。如果密钥长度不足6个字符，则完全
 * 隐藏为 "****"。这种掩码策略在保证可调试性的同时防止了敏感凭据的意外泄露。</p>
 *
 * <p><b>可用性检测：</b>当 {@link LyClawProperties} Bean 未注册到 Spring 容器时
 * （例如在非 LyClaw 应用中引入此模块），端点返回 {@code "available": false} 和
 * 相应的原因说明，而非抛出异常或返回空数据，保证了端点在各种环境下的稳定响应。</p>
 *
 * <p><b>配置信息来源：</b>所有配置数据均来源于 {@link LyClawProperties} 对象，
 * 该对象通过 Spring Boot 的 {@code @ConfigurationProperties} 机制与配置文件中的
 * {@code lyclaw.*} 前缀属性绑定。Spring {@link org.springframework.core.env.Environment}
 * 作为备用数据源，可在未来扩展中用于读取未被 LyClawProperties 覆盖的底层环境变量。</p>
 */
@Endpoint(id = "lyclaw-config")
public class LyClawConfigEndpoint {

    private final LyClawProperties props;
    private final Environment env;

    @Autowired
    public LyClawConfigEndpoint(@Autowired(required = false) LyClawProperties props,
                                 @Autowired(required = false) Environment env) {
        this.props = props;
        this.env = env;
    }

    @ReadOperation
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (props != null) {
            // LLM config (sanitized)
            Map<String, Object> llm = new LinkedHashMap<>();
            llm.put("provider", props.getLlm().getProvider());
            llm.put("model", props.getLlm().getModel());
            llm.put("temperature", props.getLlm().getTemperature());
            llm.put("maxTokens", props.getLlm().getMaxTokens());
            llm.put("timeout", props.getLlm().getTimeout());
            llm.put("baseUrl", props.getLlm().getBaseUrl());
            // Mask api-key: show first 4 + last 2 chars
            String apiKey = props.getLlm().getApiKey();
            if (apiKey != null && apiKey.length() > 6) {
                llm.put("apiKey", apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 2));
            } else if (apiKey != null) {
                llm.put("apiKey", "****");
            } else {
                llm.put("apiKey", null);
            }
            result.put("llm", llm);

            // Pipeline config
            Map<String, Object> pipeline = new LinkedHashMap<>();
            pipeline.put("enabled", props.getPipeline().isEnabled());
            pipeline.put("timeout", props.getPipeline().getTimeout());
            pipeline.put("stagesOrder", props.getPipeline().getStagesOrder());
            result.put("pipeline", pipeline);

            // Tools config
            Map<String, Object> tools = new LinkedHashMap<>();
            tools.put("enabled", props.getTools().isEnabled());
            tools.put("defaultTimeout", props.getTools().getDefaultTimeout());
            result.put("tools", tools);

            // Sandbox config
            Map<String, Object> sandbox = new LinkedHashMap<>();
            sandbox.put("level", props.getSandbox().getLevel());
            sandbox.put("readonlyTools", props.getSandbox().getReadonlyTools());
            result.put("sandbox", sandbox);

            // Agent config
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("active", props.getAgent().getActive());
            agent.put("maxRounds", props.getAgent().getMaxRounds());
            result.put("agent", agent);
        } else {
            result.put("available", false);
            result.put("reason", "No LyClawProperties bean registered");
        }

        return result;
    }
}
