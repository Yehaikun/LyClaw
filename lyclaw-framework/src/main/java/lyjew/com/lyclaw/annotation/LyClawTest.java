package lyjew.com.lyclaw.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试环境声明注解，用于在测试执行期间控制 LyClaw 框架的运行时行为和环境配置。
 *
 * <p>本注解用于标记测试类或测试配置类，在测试执行期间影响框架的特定行为开关。
 * 与标记了 {@link org.springframework.stereotype.Component} 的注解不同，本注解
 * 不是 Spring Bean 标记，不会导致被标注的类被自动注册到 Spring 容器中。它仅作为
 * 测试运行时的一个配置信号，被框架的测试支持组件读取和解析。
 *
 * <p>设计定位：作为 Phase 5（测试阶段）的骨架注解，当前提供基础的测试环境控制能力，
 * 后续版本将扩展更多测试相关的配置选项，如模拟 AI 模型响应、注入预定义的测试数据、
 * 控制时间流逝（用于测试超时和重试逻辑）等。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>enableNetwork</b>：控制测试期间是否允许真实的网络访问（如调用外部 AI API）。
 *       默认为 false，表示测试应在离线模式下运行，使用模拟的 AI 响应。设置为 true 时，
 *       框架允许测试代码发起真实的 HTTP 请求到外部 AI 服务，适用于集成测试和
 *       端到端测试场景，但会消耗 API 配额并增加测试执行时间</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @LyClawTest(enableNetwork = true)
 * class DeepSeekIntegrationTest {
 *     // 集成测试代码
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LyClawTest {

    /**
     * 控制测试期间是否启用真实的网络访问（如调用外部 AI Provider 的 API）。
     *
     * <p>当设置为 true 时，框架在测试中允许真实的 HTTP 网络请求；当设置为 false
     * （默认值）时，框架自动切换到离线/模拟模式，避免消耗 API 配额和网络延迟。
     *
     * @return true 表示允许网络访问，false（默认）表示使用离线模拟模式
     */
    boolean enableNetwork() default false;
}
