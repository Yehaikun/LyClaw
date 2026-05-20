package lyjew.com.lyclaw.react;

/**
 * Agent运行时类型 —— 对标 OpenClaw 的 AgentRuntimeConfig 联合类型。
 */
public enum AgentRuntimeType {
    /** 默认 —— LyClaw 内置的 ReAct 引擎 */
    EMBEDDED,
    /** Agent Communication Protocol —— 外部 agent 后端 */
    ACP
}
