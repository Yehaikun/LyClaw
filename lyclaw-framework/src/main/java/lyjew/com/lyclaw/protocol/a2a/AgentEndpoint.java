package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

/**
 * 代理通信端点实体，描述代理提供的一个通信入口。
 *
 * <p>每个代理可以暴露多个端点（Endpoint），每个端点通过 URL 和传输类型
 * （如 HTTP、gRPC、WebSocket）来定义一种通信方式。多个端点中可标记一个
 * 为 primary（主端点），作为默认的通信入口。</p>
 *
 * <p>该实体存在于 {@link A2aAgentCard#endpoints} 列表中，供调用方
 * 选择合适的通信方式与代理交互。</p>
 */
@Data
@Builder
public class AgentEndpoint {
    /** 端点的访问 URL */
    private String url;
    /** 传输类型，如 "http"、"grpc"、"websocket" 等 */
    private String transportType;
    /** 是否为主端点，通常只有一个端点标记为 true */
    private boolean primary;
}
