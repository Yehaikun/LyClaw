package lyjew.com.lyclaw.annotation.skill;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 技能（Skill）声明注解，用于将一个类标记为 LyClaw 框架中的可复用技能模块。
 *
 * <p>在 LyClaw 框架的技能体系中，"技能"（Skill）是一种封装了特定领域知识、操作流程
 * 和工具组合的高级组件。与单一的工具（Tool）不同，技能通常包含多步骤的执行逻辑，
 * 可能组合多个工具调用来完成一个更复杂的任务（如"PDF 文档解析"技能包含文件读取、
 * 文本提取、格式转换等多个步骤）。被 {@code @Skill} 注解标记的类通过
 * {@link org.springframework.stereotype.Component} 元注解自动被 Spring 容器发现并
 * 注册为 Bean，框架的 SkillRegistry 负责管理所有已注册的技能实例。
 *
 * <p>技能与工具的区别：工具是原子性的、单一功能的操作单元（如"搜索"、"计算"），
 * 由 AI 模型直接通过 function_call 调用；技能则是更高级的抽象，封装了工具组合和
 * 业务逻辑，通常由 Agent 或流水线阶段调用。技能可以包含状态管理、错误处理策略和
 * 执行上下文，具备更强的领域建模能力。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>name</b>：技能的名称，用于在框架中唯一标识和查找该技能。建议使用
 *       简短有意义的英文名称，如 "pdf-parser"、"code-review"</li>
 *   <li><b>description</b>：技能的功能描述，说明该技能的用途、适用场景和输入输出
 *       格式，用于运维面板展示和自动生成 AI 可理解的技能元数据</li>
 * </ul>
 *
 * @see lyjew.com.lyclaw.annotation.tool.Tool
 * @see lyjew.com.lyclaw.annotation.Agent
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Skill {

    /**
     * 技能的名称，用于在框架的 SkillRegistry 中唯一标识该技能。
     *
     * <p>建议使用简短、描述性强的英文标识。如果为空字符串，框架使用类的简单名称
     * 作为默认的技能名称。
     *
     * @return 技能名称字符串，默认为空字符串（使用类名推断）
     */
    String name() default "";

    /**
     * 技能的功能描述，说明该技能的用途、能力和适用场景。
     *
     * <p>该描述将展示在运维面板和 Actuator 端点中，也可能被注入到 AI 模型的
     * 上下文中作为技能选择决策的参考信息。
     *
     * @return 技能的功能描述字符串，默认为空字符串
     */
    String description() default "";
}
