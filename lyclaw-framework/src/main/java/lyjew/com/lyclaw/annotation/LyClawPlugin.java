package lyjew.com.lyclaw.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * LyClaw 插件（Plugin）声明注解，用于将一个类标记为框架的扩展插件组件，支持框架功能的模块化扩展。
 *
 * <p>在 LyClaw 框架的插件架构中，插件是一种轻量级的扩展机制，允许开发者和第三方以
 * 非侵入式的方式向框架添加新功能或覆盖现有行为，而无需修改框架核心代码。插件可以
 * 提供新的存储后端实现、自定义的模型适配器、额外的工具集合、定制化的路由策略等。
 * 被 {@code @LyClawPlugin} 注解标记的类通过
 * {@link org.springframework.stereotype.Component} 元注解自动被 Spring 容器发现并
 * 注册为 Bean，框架的插件管理器（PluginManager）负责加载、初始化和卸载插件。
 *
 * <p>插件的生命周期由框架管理：Spring 容器启动时自动发现并初始化所有标记了此注解
 * 的插件类，按照声明的依赖关系（通过 Spring 的 @DependsOn 或 @Order 注解）确定
 * 加载顺序。插件可以通过实现特定的框架接口来声明自己的能力，框架根据接口类型
 * 将插件集成到对应的管道中。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>name</b>：插件的名称，用于在框架中唯一标识和查找该插件。建议使用
 *       简短有意义的英文名称，如 "brave-search"、"slack-notifier"</li>
 *   <li><b>version</b>：插件的语义化版本号（SemVer），用于追踪插件的迭代历史和
 *       兼容性管理，也用于插件市场中的版本比对和更新提示</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @LyClawPlugin(name = "brave-search", version = "1.2.0")
 * public class BraveSearchPlugin implements SearchProvider {
 *     // 插件实现
 * }
 * }</pre>
 *
 * @see lyjew.com.lyclaw.annotation.tool.Tool
 * @see lyjew.com.lyclaw.annotation.Adapter
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface LyClawPlugin {

    /**
     * 插件的名称，用于在框架的 PluginManager 和配置中唯一标识该插件。
     *
     * <p>建议使用简短、描述性强的英文标识。如果为空字符串，框架使用类的简单名称
     * 作为默认的插件名称。
     *
     * @return 插件名称字符串，默认为空字符串（使用类名推断）
     */
    String name() default "";

    /**
     * 插件的语义化版本号，遵循 SemVer（主版本.次版本.修订号）规范。
     *
     * <p>版本号用于插件管理和兼容性检测。框架在加载插件时记录版本信息，支持
     * 在日志和 Actuator 端点中查看所有已加载插件的版本。
     *
     * @return 插件的版本号字符串，默认为 "1.0.0"
     */
    String version() default "1.0.0";
}
