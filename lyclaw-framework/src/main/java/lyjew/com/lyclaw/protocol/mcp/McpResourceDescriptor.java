package lyjew.com.lyclaw.protocol.mcp;

import lombok.Builder;
import lombok.Data;

/**
 * MCP 协议中资源描述符实体。
 *
 * <p>在 MCP 协议中，Resource 表示服务器暴露的静态或动态资源（如文件、
 * 数据库记录、API 输出等），AI 模型可以读取这些资源来获取上下文信息。
 * 每个资源通过 URI 唯一标识，并声明其 MIME 类型以便模型正确解析。</p>
 *
 * <p>该实体通过 {@link McpServer#registerResource(McpResourceDescriptor)}
 * 注册到服务器。</p>
 */
@Data
@Builder
public class McpResourceDescriptor {
    /** 资源的唯一 URI 标识 */
    private String uri;
    /** 资源的人类可读名称 */
    private String name;
    /** 资源的功能描述 */
    private String description;
    /** 资源的 MIME 类型，如 "text/markdown"、"application/json" 等 */
    private String mimeType;
}
