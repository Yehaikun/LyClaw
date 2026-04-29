package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

/**
 * 技能抽象接口 —— 比工具更高层次的可复用能力单元。
 *
 * <p>工具（Tool）是原子操作（如搜索、计算、查时间），技能（Skill）是编排后的复合能力。
 * 一个技能内部可能包含多次模型调用和多个工具调用。例如：
 * <ul>
 *   <li>"写周报"技能：读取备忘录 → 分析本周工作 → 调用模型生成 → 格式化输出</li>
 *   <li>"竞品分析"技能：搜索竞品信息 → 调用模型分析 → 生成对比表格</li>
 * </ul>
 * </p>
 *
 * <p><b>与 Tool 的区别</b>：
 * <ul>
 *   <li>Tool 是原子操作，Skill 是复合操作</li>
 *   <li>Tool 由模型调用触发，Skill 由上层业务逻辑触发</li>
 *   <li>Tool 同步执行，Skill 异步执行（CompletableFuture）</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillExecutor
 * @see SkillRegistry
 */
public interface Skill {

    /**
     * 获取技能唯一标识。全局唯一，用于注册和查找。
     *
     * @return 技能 ID（非 null）
     */
    String getSkillId();

    /**
     * 获取技能名称。人类可读，用于展示和管理。
     *
     * @return 技能名称
     */
    String getName();

    /**
     * 获取技能描述。说明技能的用途和适用场景。
     *
     * @return 技能描述
     */
    String getDescription();

    /**
     * 异步执行技能。
     *
     * @param context 当前对话上下文
     * @return CompletableFuture，成功时包含 SkillResult
     */
    CompletableFuture<SkillResult> execute(ChatContext context);
}