package lyjew.com.lyclaw.constant;

/**
 * 插件状态枚举，描述插件在框架中的生命周期状态。
 */
public enum PluginStatus {
    /** 已加载，插件代码已被框架加载 */
    LOADED,
    /** 已激活，插件正在正常运行 */
    ACTIVE,
    /** 未激活，插件已加载但被暂时禁用 */
    INACTIVE,
    /** 异常，插件运行中出现错误 */
    ERROR,
    /** 已卸载，插件已从框架中移除 */
    UNLOADED
}
