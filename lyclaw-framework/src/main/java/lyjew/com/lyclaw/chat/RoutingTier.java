package lyjew.com.lyclaw.chat;

/** 路由层级枚举，按请求复杂度从低到高划分。 */
public enum RoutingTier {
    /** 简单问候/闲聊——派给低成本小模型 */
    SIMPLE,
    /** 标准请求——派给默认主力模型 */
    STANDARD,
    /** 复杂分析/推理——派给高能力大模型 */
    COMPLEX,
    /** 代码生成/分析——派给代码专长模型 */
    CODE
}
