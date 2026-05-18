package lyjew.com.lyclaw.autoconfigure.processor;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.react.AgentProxyFactory;

/**
 * 自动发现 @Agent 注解的接口并注册为 Spring Bean。
 *
 * <p>这是一个 {@link BeanFactoryPostProcessor}，在 Spring 容器刷新早期运行。
 * 它扫描 classpath 上所有带 {@link Agent @Agent} 注解的接口，
 * 通过 {@link AgentProxyFactory} 为每个接口创建 JDK 动态代理，
 * 然后将代理实例注册为 Spring 单例 Bean。
 *
 * <p>使用 BeanFactoryPostProcessor 而非 BeanPostProcessor 的原因：
 * @Agent 标记的是接口，Spring 无法直接实例化接口。必须在 Bean 实例化之前
 * 将代理 Bean 注册到容器中，替换掉 Spring 扫描到的无效 BeanDefinition。
 *
 * <p>扫描范围由 Spring Boot 的自动配置扫描机制（AutoConfigurationPackages）确定。
 */
public class AgentInterfaceProcessor implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AgentInterfaceProcessor.class);

    private final AgentProxyFactory agentProxyFactory;

    public AgentInterfaceProcessor(AgentProxyFactory agentProxyFactory) {
        this.agentProxyFactory = agentProxyFactory;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        DefaultListableBeanFactory registry = (DefaultListableBeanFactory) beanFactory;

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Agent.class));

        String[] basePackages = registry.getBeanDefinitionNames();
        // Scan from well-known framework/application base packages
        String[] scanPackages = {"lyjew.com.lyclaw", ""};
        Set<String> scanned = new java.util.HashSet<>();

        for (String basePackage : scanPackages) {
            if (basePackage.isEmpty()) continue;
            try {
                for (var bd : scanner.findCandidateComponents(basePackage)) {
                    String className = bd.getBeanClassName();
                    if (scanned.contains(className)) continue;
                    scanned.add(className);

                    try {
                        Class<?> clazz = Class.forName(className);
                        if (!clazz.isInterface()) continue;

                        Object proxy = agentProxyFactory.create(clazz);
                        String beanName = resolveBeanName(clazz);
                        registerSingleton(registry, beanName, clazz.getName(), proxy);
                        log.info("Registered @Agent proxy bean: {} → {}", beanName, className);
                    } catch (ClassNotFoundException e) {
                        log.debug("Cannot load class {}: {}", className, e.getMessage());
                    } catch (Exception e) {
                        log.warn("Failed to create proxy for @Agent interface {}: {}",
                                className, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("Scanning package {} failed: {}", basePackage, e.getMessage());
            }
        }
    }

    private String resolveBeanName(Class<?> clazz) {
        Agent ann = clazz.getAnnotation(Agent.class);
        if (ann != null && !ann.name().isEmpty()) {
            return ann.name();
        }
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private void registerSingleton(DefaultListableBeanFactory registry,
                                    String beanName, String alias, Object instance) {
        // 如果已有同名 Bean，先移除
        if (registry.containsBeanDefinition(beanName)) {
            registry.removeBeanDefinition(beanName);
        }
        registry.registerSingleton(beanName, instance);
    }
}
