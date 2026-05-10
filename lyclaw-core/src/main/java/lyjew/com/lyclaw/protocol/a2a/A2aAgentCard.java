package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card —— 遵循 Google A2A 协议规范的 Agent 名片。
 *
 * @since 2.0
 */
@Data
@Builder
public class A2aAgentCard {

    /** 全局唯一ID */
    private String agentId;

    /** 人类可读名称 */
    private String name;

    /** 能力描述 */
    private String description;

    /** Agent 服务端点 URL */
    private String url;

    /** 版本号 */
    private String version;

    /** 能力列表: TEXT_GEN, TOOL_USE, CODE_EXEC, RAG */
    private List<AgentCapability> capabilities;

    /** 支持的传输方式 */
    private List<AgentEndpoint> endpoints;

    /** 扩展元数据 */
    private Map<String, String> metadata;
}
