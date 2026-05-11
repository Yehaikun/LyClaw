package lyjew.com.lyclaw.protocol.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP 协议中工具描述符实体。
 *
 * <p>ToolDescriptor 定义了 MCP 服务器暴露的工具的完整元信息。包括：
 * 工具的唯一名称、人类可读的描述、符合 JSON Schema 规范的输入参数定义
 * （inputSchema），以及该工具所属服务器的名称。</p>
 *
 * <p>AI 模型通过 MCP 客户端获取 ToolDescriptor 列表后，可以根据
 * inputSchema 来构造合法的工具调用参数。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpToolDescriptor {
    /** 工具的唯一名称，如 "read_file"、"search" 等 */
    private String name;
    /** 工具的功能描述，说明该工具的用途 */
    private String description;
    /** 工具的输入 JSON Schema，定义了调用时所需的参数结构 */
    private Map<String, Object> inputSchema;
    /** 该工具所属的 MCP 服务器名称 */
    private String serverName;
}
