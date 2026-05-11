package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具定义模型，描述了一个可供 AI 模型调用的工具的完整元数据。
 *
 * 遵循 OpenAI Function Calling 的工具定义格式，包含工具名称、显示名称、
 * 功能描述、参数 JSON Schema 以及来源和超时等运行时配置。
 * 该定义会被序列化后放入 chat completion 请求的 tools 字段中发送给 AI 模型，
 * 模型据此决定何时调用以及如何调用工具。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** 工具的唯一名称（用于 API 调用匹配） */
    private String name;

    /** 工具的显示名称，用于 UI 展示，默认为空 */
    @Builder.Default
    private String displayName = "";

    /** 工具的功能描述，AI 模型据此判断是否调用此工具 */
    private String description;

    /** 工具参数的 JSON Schema 定义（Map 格式） */
    private Map<String, Object> parameters;

    /** 工具来源标识，默认 "builtin" 表示内置工具，也可为 MCP 服务器名等 */
    @Builder.Default
    private String source = "builtin";

    /** 工具所属的服务器名称（MCP 协议场景），默认为空 */
    @Builder.Default
    private String serverName = "";

    /** 工具调用超时时间（毫秒），0 表示无限制 */
    @Builder.Default
    private long timeout = 0;

    /** 是否为只读工具，只读工具不会产生副作用 */
    @Builder.Default
    private boolean readOnly = false;
}
