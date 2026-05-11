package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A2A（Agent-to-Agent）协议中的代理名片实体。
 *
 * <p>AgentCard 是 Google A2A 协议的核心数据结构，用于描述一个 AI 代理的身份、
 * 能力、通信端点等元信息。当一个代理要向另一个代理发起调用时，首先需要获取
 * 目标代理的 AgentCard，以了解其支持的能力和可用的通信方式。</p>
 *
 * <p>该实体包含代理的基本标识（ID、名称、版本），以及描述性信息（描述、URL），
 * 同时还携带能力列表（capabilities）和端点列表（endpoints），
 * 以及可扩展的元数据映射（metadata）。</p>
 */
@Data
@Builder
public class A2aAgentCard {
    /** 代理的唯一标识符 */
    private String agentId;
    /** 代理的人类可读名称 */
    private String name;
    /** 代理的功能描述 */
    private String description;
    /** 代理的访问 URL */
    private String url;
    /** 代理的版本号 */
    private String version;
    /** 代理支持的能力列表，如文本生成、工具调用、代码执行等 */
    private List<AgentCapability> capabilities;
    /** 代理暴露的通信端点列表，每个端点定义了传输类型和地址 */
    private List<AgentEndpoint> endpoints;
    /** 扩展元数据，用于携带协议未定义的额外信息 */
    private Map<String, String> metadata;
}
