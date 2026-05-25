package lyjew.com.lyclaw.autoconfigure.processor;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.EnvironmentAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.react.AgentProxyFactory;

/**
 * 自动发现 @Agent 注解的接口并注册为 Spring Bean。
 *
 * <p>核心策略：不直接创建代理实例（避免在 BFPP 阶段通过 {@code getBean()}
 * 触发整个依赖树的即时实例化，此时 {@code AutowiredAnnotationBeanPostProcessor}
 * 尚未注册，无参构造器的 Bean 会失败），而是为每个 @Agent 接口注册一个
 * {@link FactoryBean} BeanDefinition。代理在 Bean 首次被请求时才创建，
 * 此时所有 BeanPostProcessor 已就绪。
 *
 * <p>独立的 classpath 扫描是必要的，因为 Spring 自身的组件扫描会跳过接口
 * （{@code isConcrete()} 返回 false），不会为其创建 BeanDefinition。
 */
public class AgentInterfaceProcessor implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(AgentInterfaceProcessor.class);

    private List<String> scanPackages;
    private Environment environment;

    /** 默认构造器：包路径由 BFPP 阶段从 Environment 解析。 */
    public AgentInterfaceProcessor() {
        this.scanPackages = null;
    }

    /**
     * 构造器注入扫描包列表（直接指定，不使用 Environment）。
     *
     * @param scanPackages 要扫描 @Agent 接口的基础包路径
     */
    public AgentInterfaceProcessor(List<String> scanPackages) {
        this.scanPackages = scanPackages != null && !scanPackages.isEmpty()
                ? List.copyOf(scanPackages)
                : null;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        DefaultListableBeanFactory registry = (DefaultListableBeanFactory) beanFactory;
        LazyBeanFactoryHolder.setBeanFactory(registry);

        // 优先级：构造器参数 > Environment 配置 > 默认值
        List<String> packages = this.scanPackages;
        if (packages == null && environment != null) {
            String raw = environment.getProperty("lyclaw.scan.base-packages");
            if (raw != null && !raw.isBlank()) {
                packages = List.of(raw.split("\\s*,\\s*"));
            }
        }
        if (packages == null) {
            packages = List.of("lyjew.com.lyclaw");
        }

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition bd) {
                        return bd.getMetadata().isInterface()
                                || super.isCandidateComponent(bd);
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Agent.class));
        Set<String> scanned = new java.util.HashSet<>();

        for (String basePackage : packages) {
            try {
                for (var bd : scanner.findCandidateComponents(basePackage)) {
                    String className = bd.getBeanClassName();
                    if (className == null || !scanned.add(className)) continue;

                    try {
                        Class<?> clazz = Class.forName(className);
                        if (!clazz.isInterface()) continue;

                        String beanName = resolveBeanName(clazz);
                        // 注册 FactoryBean 定义——代理在首次 getBean 时才创建
                        RootBeanDefinition factoryDef =
                                new RootBeanDefinition(AgentProxyFactoryBean.class);
                        factoryDef.getConstructorArgumentValues()
                                .addGenericArgumentValue(clazz);
                        factoryDef.setTargetType(clazz);
                        factoryDef.setPrimary(true);

                        if (registry.containsBeanDefinition(beanName)) {
                            registry.removeBeanDefinition(beanName);
                        }
                        registry.registerBeanDefinition(beanName, factoryDef);
                        log.info("Registered @Agent proxy factory bean: {} → {}", beanName, className);
                    } catch (ClassNotFoundException e) {
                        log.debug("Cannot load class {}: {}", className, e.getMessage());
                    } catch (Exception e) {
                        log.warn("Failed to register @Agent interface {}: {}",
                                className, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Scanning package '{}' for @Agent interfaces failed: {}",
                        basePackage, e.getMessage());
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

    /**
     * 延迟创建 @Agent 代理的 FactoryBean。
     * 代理只在 Spring 首次请求 Bean 时创建，此时所有 BeanPostProcessor 已就绪。
     */
    public static class AgentProxyFactoryBean implements FactoryBean<Object> {

        private final Class<?> agentInterface;

        public AgentProxyFactoryBean(Class<?> agentInterface) {
            this.agentInterface = agentInterface;
        }

        @Override
        public Object getObject() {
            DefaultListableBeanFactory registry =
                    (DefaultListableBeanFactory) LazyBeanFactoryHolder.getBeanFactory();
            if (registry == null) {
                throw new IllegalStateException(
                        "BeanFactory not available for @Agent proxy: " + agentInterface.getName());
            }
            AgentProxyFactory factory = registry.getBean(AgentProxyFactory.class);
            // 替换自身为已创建的代理实例（避免每次调用都走 FactoryBean）
            Object proxy = factory.create(agentInterface);
            String beanName = resolveBeanName();
            registry.destroySingleton(beanName);
            registry.registerSingleton(beanName, proxy);
            return proxy;
        }

        @Override
        public Class<?> getObjectType() {
            return agentInterface;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }

        private String resolveBeanName() {
            Agent ann = agentInterface.getAnnotation(Agent.class);
            if (ann != null && !ann.name().isEmpty()) {
                return ann.name();
            }
            String simpleName = agentInterface.getSimpleName();
            return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        }
    }

    /**
     * 持有 BeanFactory 引用的静态工具。由任意一个实现了
     * {@code BeanFactoryAware} 的 Bean 设置，或由该 BFPP 在
     * {@code postProcessBeanFactory} 中直接设置。
     */
    static final class LazyBeanFactoryHolder {
        private static volatile ConfigurableListableBeanFactory beanFactory;

        static ConfigurableListableBeanFactory getBeanFactory() {
            return beanFactory;
        }

        static void setBeanFactory(ConfigurableListableBeanFactory bf) {
            beanFactory = bf;
        }
    }
}
