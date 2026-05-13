package lyjew.com.lyclaw.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 流水线阶段（Pipeline Stage）声明注解，用于将一个类标记为 LyClaw 响应式流水线中的处理阶段，
 * 并通过 after/before 属性声明阶段之间的执行顺序约束。
 *
 * <p>在 LyClaw 框架的流水线架构中，一次完整的对话处理被分解为多个独立但有序的执行阶段。
 * 每个阶段负责特定的处理职责（如请求前置校验、记忆注入、内容审核、模型调用、工具执行、
 * 响应格式化等）。被 {@code @PipelineStage} 注解标记的类通过
 * {@link org.springframework.stereotype.Component} 元注解自动被 Spring 容器发现并注册，
 * 框架的 PipelineBuilder 根据各阶段的顺序约束（after 和 before 属性）自动计算拓扑排序，
 * 构建出正确的阶段执行顺序。
 *
 * <p>阶段排序机制：框架使用拓扑排序（Topological Sort）算法处理所有阶段的 after/before
 * 声明，自动推导出满足所有约束的执行顺序。如果约束声明形成循环依赖，框架在启动阶段
 * 会抛出异常并给出详细的循环依赖链路信息。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>name</b>：阶段的名称，用于在日志、追踪和监控中标识该阶段。如果为空，
 *       框架使用类名作为默认的阶段名称</li>
 *   <li><b>after</b>：声明该阶段必须在指定的阶段类执行完毕之后才能执行。接受一个
 *       Class 数组，每个元素应是标注了 {@code @PipelineStage} 的类。例如，模型调用
 *       阶段应声明 {@code after = {MemoryInjectionStage.class}} 以确保记忆内容
 *       已注入到请求中</li>
 *   <li><b>before</b>：声明该阶段必须在指定的阶段类执行之前完成。接受一个 Class 数组，
 *       与 after 互为反向声明。例如，请求校验阶段可声明
 *       {@code before = {ModelCallStage.class}} 以确保校验先于模型调用</li>
 *   <li><b>group</b>：阶段所属的分组标签，用于在运维面板中对阶段进行分组展示和
 *       批量管理。常见的分组包括 "PREPROCESSING"（预处理）、"CORE"（核心处理）、
 *       "POSTPROCESSING"（后处理）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @PipelineStage(name = "memory", after = {ValidationStage.class},
 *                before = {ModelCallStage.class}, group = "PREPROCESSING")
 * public class MemoryInjectionStage implements PipelineStageHandler {
 *     // 阶段实现
 * }
 * }</pre>
 *
 * @see lyjew.com.lyclaw.pipeline.PipelineContext
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface PipelineStage {

    /**
     * 阶段的名称，用于在日志、追踪信息和监控面板中标识该阶段。
     *
     * <p>阶段名称应简短、有意义，便于在日志搜索和性能分析时快速定位。如果为空字符串，
     * 框架将使用类的简单名称（SimpleName）作为默认的阶段名称。
     *
     * @return 阶段名称字符串，默认为空字符串（使用类名推断）
     */
    String name() default "";

    /**
     * 声明当前阶段必须在指定的阶段类执行完毕之后才能开始执行。
     *
     * <p>此属性定义了阶段间的"后序依赖"关系。框架在构建流水线时收集所有阶段的 after
     * 声明，通过拓扑排序算法计算全局的阶段执行顺序。数组中每个元素必须是标注了
     * {@code @PipelineStage} 的类的 Class 对象。
     *
     * @return 必须在当前阶段之前执行的阶段类数组，默认为空数组（无前序依赖）
     */
    Class<?>[] after() default {};

    /**
     * 声明当前阶段必须在指定的阶段类开始执行之前完成。
     *
     * <p>此属性定义了阶段间的"前序约束"关系，与 after 互为反向声明。从语义上，
     * A.before = {B} 等价于 B.after = {A}。建议优先使用 after 声明依赖关系以保持
     * 统一的声明风格，before 适用于不方便修改目标阶段类的场景。
     *
     * @return 必须在当前阶段之后才能开始的阶段类数组，默认为空数组
     */
    Class<?>[] before() default {};

    /**
     * 阶段所属的分组标签，用于在运维面板中对阶段进行分类展示和批量管理。
     *
     * <p>常见的分组包括 "PREPROCESSING"（请求预处理阶段）、"CORE"（核心处理阶段）、
     * "POSTPROCESSING"（响应后处理阶段）。分组不影响阶段的执行顺序，仅用于
     * 组织和展示目的。
     *
     * @return 分组标签字符串，默认为空字符串（无分组）
     */
    String group() default "";
}
