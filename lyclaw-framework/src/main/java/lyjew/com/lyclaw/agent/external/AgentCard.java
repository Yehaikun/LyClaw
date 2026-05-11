package lyjew.com.lyclaw.agent.external;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 外部代理名片，描述一个外部（第三方）代理的公开元信息。
 *
 * AgentCard 类似于服务发现中的服务描述符，LyClaw 通过 ExternalAgentAdapter
 * 的 discover 方法从外部代理的标准化端点获取 AgentCard。名片包含代理的
 * 标识、名称、描述、访问 URL、版本号、能力列表和 API 端点列表，LyClaw
 * 系统凭此信息决定是否以及如何调用该外部代理。capabilities 用于能力匹配，
 * endpoints 提供具体的 HTTP/REST 接口路径，version 确保 API 兼容性。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class AgentCard {
    /** 外部代理的唯一标识 */
    private String agentId;
    /** 外部代理的名称 */
    private String name;
    /** 外部代理的功能描述 */
    private String description;
    /** 外部代理的访问 URL */
    private String url;
    /** 外部代理的版本号，用于 API 兼容性检查 */
    private String version;
    /** 外部代理的能力列表 */
    private List<String> capabilities;
    /** 外部代理暴露的 API 端点列表 */
    private List<String> endpoints;
}
