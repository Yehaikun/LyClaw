package lyjew.com.lyclaw.annotation.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具注册条件注解，用于声明工具（Tool）在 Spring 容器中注册为 Bean 时必须满足的前置条件。
 *
 * <p>在 LyClaw 框架的工具管理体系中，并非所有声明的工具都应在所有运行环境中被激活和注册。
 * 某些工具可能依赖于特定的配置项（如第三方 API 密钥）、特定的运行环境（如 dev/prod 中的
 * 不同工具集合）或特定的第三方类库（如数据库驱动）。ToolCondition 作为 {@link Tool}
 * 的辅助元注解，提供了一种声明式的条件化注册机制：只有在所有声明的条件都满足时，框架
 * 才会将该工具类注册为 Spring Bean 并暴露给 AI 模型调用。
 *
 * <p>本注解不是 Spring Bean 标记（不带有 @Component），不能单独使用。它必须与
 * {@link Tool} 注解配合使用，作为工具类的补充条件声明。框架的 ToolPostProcessor
 * 在扫描到标注了 @Tool 的类后，会额外检查该类是否也标注了 @ToolCondition，
 * 如果有则评估所有声明的条件，只有全部条件满足时才执行注册。
 *
 * <p>三种条件类型及其评估逻辑：
 * <ul>
 *   <li><b>requiresConfig（配置键条件）</b>：要求指定的配置键（如
 *       "lyclaw.tools.brave.api-key"）在 Spring Environment 中存在且值非空。
 *       用于确保工具所需的 API 密钥或外部服务地址已正确配置后再激活该工具</li>
 *   <li><b>requiresProfile（Profile 条件）</b>：要求当前激活的 Spring Profile 列表中
 *       包含指定的 Profile 名称。用于区分不同环境（dev/test/prod）下可用的工具集合，
 *       例如仅在 dev 环境激活模拟工具、仅在 prod 环境激活生产级服务工具</li>
 *   <li><b>requiresClass（类路径条件）</b>：要求指定的类在 Classpath 上存在（即可被
 *       ClassLoader 加载）。用于确保工具所依赖的第三方库（如数据库驱动、HTTP 客户端库）
 *       已引入项目后才激活该工具，避免 ClassNotFoundException</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @Tool(name = "brave_search", description = "通过 Brave Search API 搜索互联网")
 * @ToolCondition(
 *     requiresConfig = {"lyclaw.tools.brave.api-key"},
 *     requiresProfile = {"prod", "staging"},
 *     requiresClass = {BraveSearchClient.class}
 * )
 * public class BraveSearchTool {
 *     // 工具实现
 * }
 * }</pre>
 * 上例中，BraveSearchTool 只有在配置了 api-key、运行在 prod 或 staging Profile 下、
 * 且 BraveSearchClient 类在 classpath 上时才会被注册。
 *
 * @see Tool
 * @see lyjew.com.lyclaw.tool.ToolRegistry
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolCondition {

    /**
     * 要求必须存在且值非空的配置键列表。
     *
     * <p>每个配置键对应 Spring Environment 中的一个属性路径（如
     * "lyclaw.tools.brave.api-key"）。框架通过 Environment.getProperty() 检查
     * 每个键的值是否非 null 且非空字符串。只有所有键都满足条件时，此条件才通过。
     * 空数组表示不检查此条件。
     *
     * @return 需要检查的配置键数组，默认为空数组
     */
    String[] requiresConfig() default {};

    /**
     * 要求当前激活的 Spring Profile 列表中必须包含的 Profile 名称数组。
     *
     * <p>框架通过 Environment.acceptsProfiles() 检查当前激活的 Profile 集合
     * 是否包含数组中列出的所有 Profile。只有所有 Profile 都处于激活状态时，
     * 此条件才通过。空数组表示不检查此条件（所有 Profile 下均可激活）。
     *
     * @return 需要激活的 Spring Profile 名称数组，默认为空数组
     */
    String[] requiresProfile() default {};

    /**
     * 要求在 Classpath 上存在的类数组。
     *
     * <p>框架通过 Class.forName() 尝试加载数组中列出的每个类。只有所有类都能
     * 被 ClassLoader 成功加载时，此条件才通过。空数组表示不检查此条件。
     *
     * @return 需要存在于 Classpath 上的类数组，默认为空数组
     */
    Class<?>[] requiresClass() default {};
}
