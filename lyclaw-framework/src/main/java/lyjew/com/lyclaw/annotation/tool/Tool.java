package lyjew.com.lyclaw.annotation.tool;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * LyClaw 框架的工具声明注解，用于将一个类或方法标记为可供 AI 大模型调用的外部工具（Tool/Function）。
 *
 * <p>在 LLM Agent 架构中，"工具"（Tool）是指 AI 模型可以调用以完成特定任务的外部功能单元，
 * 例如网络搜索、数据库查询、文件操作、API 调用等。被 {@code @Tool} 注解标记的类或方法会被
 * 框架的组件扫描机制（通过 {@link org.springframework.stereotype.Component} 元注解）自动
 * 发现并注册为 Spring Bean，同时由 {@code ToolRegistry} 提取其元数据（名称、描述、参数等）
 * 生成为 AI 模型可理解的 Tool Definition（工具定义），在 AI 发起工具调用请求时由框架自动
 * 匹配并执行对应的方法或类，然后将执行结果返回给 AI 模型，形成完整的感知-决策-执行闭环。
 *
 * <p>本注解支持标注在两种目标上：
 * <ul>
 *   <li><b>类级别（TYPE）</b>：被标注的类本身就是一个完整的工具实现。类中需要使用
 *       {@code @ToolParam} 注解标记工具的参数，框架通过反射解析类的结构自动生成
 *       工具的参数 Schema（JSON Schema 格式）</li>
 *   <li><b>方法级别（METHOD）</b>：被标注的方法是一个独立的工具函数。框架通过 Java 反射
 *       获取方法的参数名称、类型和注解信息，自动生成工具的函数签名和参数 Schema。
 *       这种方式更简洁，适合大多数单一功能的工具实现</li>
 * </ul>
 *
 * <p>属性说明：
 * <ul>
 *   <li><b>name</b>（必填）：工具的唯一标识名称。框架通过此名称在工具注册表中索引和查找
 *       工具，同时也是 AI 模型 function_call 请求中引用的名称。name 为空字符串时框架会在
 *       启动阶段报错，因此每个工具都必须指定一个全局唯一的有意义的名称，建议使用驼峰或
 *       蛇形命名，如 "web_search"、"database_query"</li>
 *   <li><b>description</b>（建议填写）：工具的功能描述文本，直接注入到发送给 AI 模型的
 *       工具定义中。描述的质量直接影响 AI 模型对工具选择和使用准确性，应清晰说明工具的
 *       功能、适用场景、输入输出格式以及使用注意事项</li>
 *   <li><b>group</b>：工具的分组标签，用于在工具管理界面对工具进行分组展示和批量管理。
 *       同一 group 的工具在逻辑上属于同一业务领域（如 "search"、"database"、"file"）</li>
 *   <li><b>readonly</b>：标记工具是否为只读操作（无副作用）。只读工具不会修改外部系统
 *       的状态，AI 模型可能更倾向于在不确定时调用只读工具进行探索。默认为 false</li>
 *   <li><b>version</b>：工具的版本号，遵循语义化版本规范（SemVer）。用于追踪工具的
 *       变更历史和兼容性管理</li>
 *   <li><b>timeout</b>：工具执行的超时时间（毫秒）。超过此时间未执行完成的工具调用将
 *       被框架强制中断并返回超时错误，防止单个工具长时间阻塞整个对话流程。默认 30000ms</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @Tool(name = "web_search", description = "搜索互联网获取实时信息",
 *       group = "search", readonly = true)
 * public class WebSearchTool {
 *     // 工具实现
 * }
 *
 * @Tool(name = "calculate", description = "执行数学计算")
 * public String calculate(@ToolParam(description = "数学表达式") String expression) {
 *     // 方法实现
 * }
 * }</pre>
 *
 * @see lyjew.com.lyclaw.annotation.tool.ToolParam
 * @see lyjew.com.lyclaw.tool.ToolRegistry
 * @see lyjew.com.lyclaw.tool.ToolExecutionResult
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Tool {

    /** 工具的唯一标识名称，空值将在启动时报错 */
    String name() default "";

    /** 工具的功能描述，直接注入到 LLM 的工具定义中 */
    String description() default "";

    /** 工具的分组标签，用于分类管理和界面展示 */
    String group() default "";

    /** 标记工具是否为只读操作（无副作用），默认为 false */
    boolean readonly() default false;

    /** 工具的语义化版本号 */
    String version() default "1.0.0";

    /** 工具执行超时时间（毫秒），默认 30000ms */
    long timeout() default 30000;
}
