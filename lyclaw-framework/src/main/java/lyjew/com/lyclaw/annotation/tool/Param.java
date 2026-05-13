package lyjew.com.lyclaw.annotation.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具参数声明注解，用于标注工具方法中的参数，提供参数的名称、描述、必填性等元数据，
 * 供框架自动生成 AI 模型可理解的 JSON Schema 工具定义。
 *
 * <p>在 LyClaw 框架的工具定义生成流程中，当 AI 模型需要调用某个工具时，它首先需要
 * 了解工具接受哪些参数、每个参数的类型和含义、哪些参数是必填的。本注解就是用于
 * 补充 Java 方法参数的元数据——由于 Java 编译后默认不保留参数名称信息，且参数的
 * 语义描述也无法通过 Java 类型系统表达，因此需要通过此注解显式声明这些信息，
 * 框架在启动时通过反射收集所有标注了此注解的参数信息，自动生成符合 JSON Schema
 * 规范的工具参数定义。
 *
 * <p>本注解不是 Spring Bean 标记（不带有 @Component），仅用于标注方法参数。
 * 它通常与 {@link Tool} 注解配合使用，标注在 tool 类的公开方法参数上。
 * 框架的 ToolParamExtractor 会扫描这些注解，提取参数元数据并生成对应的
 * ToolDefinition 中的 parameters 字段（JSON Schema 格式）。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>name</b>：参数在 JSON Schema 中显示的名称。如果为空字符串，框架尝试从
 *       字节码中提取参数的原始名称（需编译时开启 -parameters 选项），或使用
 *       "arg0"、"arg1" 等占位名。建议显式指定一个有意义的名称，因为 AI 模型
 *       会根据参数名理解参数的用途</li>
 *   <li><b>description</b>：参数的功能描述文本，直接注入到发送给 AI 模型的工具
 *       定义中。描述的清晰度直接影响 AI 模型参数填充的准确性和合理性。应说明
 *       参数的用途、可接受的值范围、格式要求等</li>
 *   <li><b>required</b>：参数是否为必填。默认为 true。AI 模型在调用工具时会根据
 *       此标记决定是否必须提供该参数。对于有合理默认值或可选功能的参数，可设为 false
 *       以提供更好的调用灵活性</li>
 *   <li><b>defaultValue</b>：当 AI 模型未提供该参数时使用的默认值字符串。
 *       框架在解析参数时会根据此值和参数的实际类型进行类型转换。仅在 required=false
 *       时有意义，提供了在 AI 未指定参数时的回退值</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @Tool(name = "web_search", description = "搜索互联网获取信息")
 * public class WebSearchTool {
 *     public String search(
 *         @Param(name = "query", description = "搜索关键词",
 *                required = true) String query,
 *         @Param(name = "max_results", description = "最大返回结果数",
 *                required = false, defaultValue = "10") int maxResults
 *     ) {
 *         // 搜索实现
 *     }
 * }
 * }</pre>
 *
 * @see Tool
 * @see lyjew.com.lyclaw.model.ToolDefinition
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {

    /**
     * 参数在生成的 JSON Schema 中显示的名称。
     *
     * <p>这是 AI 模型在构造工具调用请求时使用的参数名。如果为空字符串，框架将
     * 尝试从字节码中提取原始参数名（需编译时启用 -parameters 选项）。
     *
     * @return 参数的名称字符串，默认为空字符串（使用字节码推断）
     */
    String name() default "";

    /**
     * 参数的功能描述，直接注入到发送给 AI 模型的工具定义中。
     *
     * <p>AI 模型根据此描述理解参数的用途和约束，因此描述应清晰、准确，包含参数
     * 的语义说明、可接受的值范围和格式要求。高质量的 description 能显著提升
     * AI 模型填充参数的准确性。
     *
     * @return 参数的描述字符串，默认为空字符串
     */
    String description() default "";

    /**
     * 指示该参数是否为工具调用的必填参数。
     *
     * <p>AI 模型在调用工具时，会根据此标记决定是否必须提供该参数的值。对于有
     * 合理默认值或属于可选功能的参数，建议设置为 false 以提升调用灵活性。
     * 必填参数缺失时，框架会根据策略拒绝调用或使用默认值填充。
     *
     * @return true 表示参数必填，false 表示可选，默认为 true
     */
    boolean required() default true;

    /**
     * 当 AI 模型在调用时未提供该参数时使用的默认值。
     *
     * <p>框架在解析工具调用参数时会检查 AI 是否提供了该参数，如果未提供且
     * required 为 false，则使用此默认值。默认值是字符串格式，框架会根据参数
     * 的实际 Java 类型执行相应的类型转换（如 "10" -> int 10）。
     *
     * @return 参数的默认值字符串，默认为空字符串
     */
    String defaultValue() default "";
}
