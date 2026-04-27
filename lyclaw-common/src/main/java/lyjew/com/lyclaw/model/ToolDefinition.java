package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具定义——告诉模型"有哪些工具可用、怎么调用"
 * 这个对象会被序列化到模型请求中，作为 function calling / tool use 的工具声明
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /**
     * 全局唯一的工具名称，用于内部标识。
     * 内置工具：如 "web_search"、"calculator"
     * MCP 工具：如 "mcp_filesystem_read_file"
     */
    private String name;

    /**
     * 展示给模型看的工具名称。
     * 通常与 name 相同，MCP 工具时去掉 server 前缀，如 "read_file"
     */
    @Builder.Default
    private String displayName = "";

    /** 工具功能描述，供 AI 判断什么时候应该调用这个工具 */
    private String description;

    /** 参数定义的 JSON Schema，描述工具需要哪些参数及其类型 */
    private Map<String, Object> parameters;

    /**
     * 工具来源标识：
     * "builtin" — 内置工具（web_search / calculator / current_time）
     * "mcp"     — 来自 MCP Server 的外部工具
     */
    @Builder.Default
    private String source = "builtin";

    /** MCP Server 名称，仅 source="mcp" 时有值，如 "filesystem" */
    @Builder.Default
    private String serverName = "";

    /** 工具执行超时时间，单位毫秒。0 表示使用默认超时 */
    @Builder.Default
    private long timeout = 0;
}