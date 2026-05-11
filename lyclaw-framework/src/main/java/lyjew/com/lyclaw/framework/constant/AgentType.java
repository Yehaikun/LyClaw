package lyjew.com.lyclaw.framework.constant;

/**
 * Agent 类型枚举，定义框架支持的智能体推理执行策略。
 *
 * <p>不同类型的 Agent 采用不同的推理与行动模式：
 * REACT 交替推理与工具调用，PLAN_EXECUTE 先规划后执行，
 * ROUTER 按输入分发到子 Agent，REFLECTIVE 具备自我评估修正能力，
 * MULTIMODAL 支持文本、图像等多种输入形式。</p>
 */
public enum AgentType {
    /** 思考-行动循环模式，交替进行推理与工具调用 */
    REACT,
    /** 先规划后执行模式，先生成完整计划再逐步执行 */
    PLAN_EXECUTE,
    /** 路由模式，根据输入请求将任务分发到不同的子 Agent */
    ROUTER,
    /** 反思模式，具备自我评估与修正能力 */
    REFLECTIVE,
    /** 多模态模式，支持文本、图像等多种输入形式 */
    MULTIMODAL
}
