package lyjew.com.lyclaw.skill;

/**
 * 技能类型枚举 —— 区分不同来源和复杂度的技能。
 *
 * <p><b>设计动机</b>：不同类型的技能在注册、执行、展示时可能有不同的处理逻辑。
 * 用枚举固化所有技能类型，避免 String 类型的硬编码。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public enum SkillType {

    /** 系统内置技能，不可删除不可修改。例如系统帮助、默认行为 */
    BUILTIN,

    /** 用户自定义技能，用户可以通过 API 或 UI 创建。存储在后端的技能仓库中 */
    USER_DEFINED,

    /** 复合技能，由多个子技能按 DAG 编排而成。通过 SkillGraph 定义依赖关系 */
    COMPOSITE
}