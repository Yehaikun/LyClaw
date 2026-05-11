package lyjew.com.lyclaw.protocol.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * MCP 协议中提示模板的描述符实体。
 *
 * <p>在 MCP 协议中，Prompt 表示预定义的提示模板，AI 模型可以使用这些模板
 * 来生成结构化的请求。每个 Prompt 有名称、描述，以及一组参数定义
 * （{@link McpPromptArgument}）。调用方通过提供参数值来实例化模板。</p>
 *
 * <p>该实体由 MCP 服务器暴露，客户端通过 {@link McpClient#discoverTools()}
 * 发现可用 Prompt 列表。</p>
 */
@Data
@Builder
public class McpPromptDescriptor {

    /** 提示模板的名称 */
    private String name;
    /** 提示模板的功能描述 */
    private String description;
    /** 提示模板的参数定义列表 */
    private List<McpPromptArgument> arguments;

    /**
     * MCP 提示参数定义，描述提示模板的一个参数。
     */
    @Data
    @Builder
    public static class McpPromptArgument {
        /** 参数名称 */
        private String name;
        /** 参数描述 */
        private String description;
        /** 是否为必填参数 */
        private boolean required;
    }
}
