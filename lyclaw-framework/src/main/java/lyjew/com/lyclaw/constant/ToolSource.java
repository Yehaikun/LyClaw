package lyjew.com.lyclaw.constant;

/**
 * 工具来源枚举，标识工具的提供方类型。
 */
public enum ToolSource {
    /** 内置工具，框架自带的工具 */
    BUILTIN,
    /** MCP 工具，通过 Model Context Protocol 集成的外部工具 */
    MCP,
    /** 插件工具，由框架插件提供的工具 */
    PLUGIN,
    /** 外部工具，通过其他方式集成的第三方工具 */
    EXTERNAL
}
