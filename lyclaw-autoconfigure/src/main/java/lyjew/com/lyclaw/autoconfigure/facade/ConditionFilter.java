package lyjew.com.lyclaw.autoconfigure.facade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * 条件过滤器，基于 {@code @ToolCondition} 注解创建 {@link java.util.function.Predicate}
 * 过滤断言，将条件激活规则与 Spring {@link org.springframework.core.env.Environment}
 * 环境对象绑定。
 *
 * <p><b>设计目的：</b>在 LyClaw 框架中，工具类的加载和注册受到多种环境条件的约束——
 * 某些工具只在特定配置项存在时启用、某些工具只在特定 Spring Profile 激活时可用、
 * 某些工具依赖特定的第三方类库。ConditionFilter 将这些零散的条件判断逻辑统一封装
 * 为标准的 {@link Predicate} 接口，供 {@link ExtensionFacade} 在扩展注册流水线中调用。</p>
 *
 * <p><b>三种过滤条件：</b></p>
 * <ul>
 *   <li><b>requiresConfig（配置项条件）：</b>检查 Spring Environment 中是否存在指定的
 *       配置属性（如 {@code lyclaw.tools.enabled=true}）。如果任意一个指定的配置项不存在
 *       或为 null，则该候选工具被过滤掉。日志级别为 INFO，因为这通常是有意的配置决定。</li>
 *   <li><b>requiresProfile（Profile 条件）：</b>检查指定的 Spring Profile 是否处于激活
 *       状态（如 {@code prod}、{@code dev}）。如果指定的 Profile 不在当前激活的 Profile
 *       列表中，则过滤该候选工具。日志级别为 DEBUG，因为 Profile 切换是常规运维操作。</li>
 *   <li><b>requiresClass（类路径条件）：</b>检查指定的 Java 类是否存在于 classpath 中
 *       （通过 {@code Class.forName()} 尝试加载）。如果类不存在（抛出 ClassNotFoundException），
 *       则过滤该候选工具。这用于处理可选依赖场景——工具依赖的第三方 SDK 未引入时自动禁用。</li>
 * </ul>
 *
 * <p><b>反射实现：</b>与 ToolAnnotationProcessor 的设计一致，本类通过反射 API 读取
 * {@code @ToolCondition} 注解的属性，避免了对注解模块的编译期依赖。如果反射访问失败
 * （如注解类版本不兼容），会静默处理——该候选工具被视为不受条件约束而通过过滤。</p>
 *
 * <p><b>性能考虑：</b>过滤器的 {@code test()} 方法每次被调用时都会执行反射和配置查询，
 * 但由于工具扫描仅在应用启动阶段执行一次（通过 BeanPostProcessor 机制），这不会对
 * 运行时性能产生影响。</p>
 */
public class ConditionFilter {

    private static final Logger log = LoggerFactory.getLogger(ConditionFilter.class);
    private final Environment env;

    public ConditionFilter(Environment env) {
        this.env = env;
    }

    /**
     * Creates a {@link Predicate} that filters candidates based on
     * {@code @ToolCondition} annotation attributes:
     * <ul>
     *   <li>{@code requiresConfig} — property must be present</li>
     *   <li>{@code requiresProfile} — profile must be active</li>
     *   <li>{@code requiresClass} — class must be on the classpath</li>
     * </ul>
     */
    public Predicate<Object> toolConditionFilter() {
        return candidate -> {
            Class<?> clazz = candidate.getClass();
            for (var ann : clazz.getAnnotations()) {
                if (!ann.annotationType().getSimpleName().equals("ToolCondition")) {
                    continue;
                }
                try {
                    String[] requiresConfig = (String[]) ann.getClass()
                            .getMethod("requiresConfig").invoke(ann);
                    String[] requiresProfile = (String[]) ann.getClass()
                            .getMethod("requiresProfile").invoke(ann);
                    Class<?>[] requiresClass = (Class<?>[]) ann.getClass()
                            .getMethod("requiresClass").invoke(ann);

                    for (String config : requiresConfig) {
                        if (env.getProperty(config) == null) {
                            log.info("[ConditionFilter] {} disabled: missing config '{}'",
                                    clazz.getSimpleName(), config);
                            return false;
                        }
                    }
                    for (String profile : requiresProfile) {
                        if (!Arrays.asList(env.getActiveProfiles()).contains(profile)) {
                            log.debug("[ConditionFilter] {} disabled: profile '{}' not active",
                                    clazz.getSimpleName(), profile);
                            return false;
                        }
                    }
                    for (Class<?> req : requiresClass) {
                        try {
                            Class.forName(req.getName());
                        } catch (ClassNotFoundException e) {
                            log.info("[ConditionFilter] {} disabled: class '{}' not on classpath",
                                    clazz.getSimpleName(), req.getName());
                            return false;
                        }
                    }
                } catch (Exception ignored) {
                    // reflective access failed — treat as not condition-constrained
                }
            }
            return true;
        };
    }
}
