package lyjew.com.lyclaw.annotation;

/**
 * 监督者 Agent 的协作模式。
 *
 * <ul>
 *   <li><b>HANDOFF</b> — 转交模式：将对话直接转交给最合适的子 Agent，
 *       监督者不再参与后续交互。适合明确属于某个子 Agent 领域的问题。</li>
 *   <li><b>HIERARCHICAL</b> — 层级模式：监督者分解复杂任务，分派给多个
 *       子 Agent 并行或串行执行，最后聚合结果。适合需要多领域协作的复杂问题。</li>
 * </ul>
 */
public enum SupervisorMode {
    HANDOFF,
    HIERARCHICAL
}
