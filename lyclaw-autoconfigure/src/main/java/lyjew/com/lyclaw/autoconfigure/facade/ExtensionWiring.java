package lyjew.com.lyclaw.autoconfigure.facade;

import lyjew.com.lyclaw.autoconfigure.config.LyClawConfigurationProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 扩展系统装配器——在所有单例就绪后将 ConditionFilter、ExtensionProperties
 * 与 ExtensionFacade 串成完整的过滤链，并驱动所有 DeferredRegistrar 完成
 * 收集→过滤→注册三阶段流程。
 *
 * <p>执行时机：SmartInitializingSingleton 回调在所有 Bean（含 BeanPostProcessor）
 * 完成后触发，确保各处理器的 pending 列表已完全填充。
 *
 * <p>设计要点：
 * <ul>
 *   <li>读 ExtensionProperties 配置过滤开关和快速失败策略</li>
 *   <li>将 ConditionFilter 生成的断言注入 ExtensionFacade 过滤链</li>
 *   <li>自动发现所有 DeferredRegistrar Bean，逐类别执行批量过滤</li>
 *   <li>通过 {@code @Autowired(required=false)} 优雅降级——缺少某组件时
 *       对应能力自动跳过而非崩溃</li>
 * </ul>
 */
@Configuration
public class ExtensionWiring implements SmartInitializingSingleton, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ExtensionWiring.class);

    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private ExtensionFacade extensionFacade;

    @Autowired(required = false)
    private ConditionFilter conditionFilter;

    @Autowired(required = false)
    @Qualifier("lyClawProperties")
    private LyClawConfigurationProperties lyClawConfig;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    //实现工具注册三阶段核心方法
    @Override
    public void afterSingletonsInstantiated() {
        // ── 1. 装配 ExtensionFacade ──
        if (extensionFacade == null) {
            log.debug("ExtensionFacade 未就绪，跳过扩展过滤链装配");
            driveRegistrars();
            return;
        }

        ExtensionProperties ext = extensionProperties();
        extensionFacade
                .filteringEnabled(ext.isFilteringEnabled())
                .failFast(ext.isFailFast());

        if (conditionFilter != null) {
            extensionFacade.addFilter(conditionFilter.toolConditionFilter());
            log.info("ExtensionFacade 过滤链已装配: filteringEnabled={}, failFast={}",
                    ext.isFilteringEnabled(), ext.isFailFast());
        } else {
            log.debug("ConditionFilter 未就绪，ExtensionFacade 以空过滤器链运行");
        }

        // ── 2. 驱动所有 DeferredRegistrar ──
        driveRegistrars();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void driveRegistrars() {
        Map<String, DeferredRegistrar> registrars =
                applicationContext.getBeansOfType(DeferredRegistrar.class);

        if (registrars.isEmpty()) {
            log.debug("未发现任何 DeferredRegistrar，跳过批量过滤注册");
            return;
        }

        for (DeferredRegistrar<?> registrar : registrars.values()) {
            if (registrar.getPending().isEmpty()) {
                log.debug("[{}] 无待过滤候选者，跳过", registrar.category());
                continue;
            }

            if (extensionFacade != null) {
                // process() 返回 List<T>，T 由 registrar 的泛型决定，运行时类型擦除是安全的
                List<?> accepted = extensionFacade.process(registrar.getPending(), registrar.category());
                ((DeferredRegistrar) registrar).applyFiltered(accepted);
                log.info("[{}] 过滤后注册: {}/{} 个通过",
                        registrar.category(), accepted.size(), registrar.getPending().size());
            } else {
                List<?> pending = registrar.getPending();
                ((DeferredRegistrar) registrar).applyFiltered(pending);
                log.info("[{}] ExtensionFacade 不可用，全量注册 {} 个候选者",
                        registrar.category(), pending.size());
            }
        }
    }

    private ExtensionProperties extensionProperties() {
        if (lyClawConfig != null && lyClawConfig.getExtension() != null) {
            return lyClawConfig.getExtension();
        }
        return new ExtensionProperties(); // 全部默认值
    }
}
