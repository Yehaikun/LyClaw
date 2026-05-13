package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.interceptor.Interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.*;

/**
 * 拦截器发现处理器，作为 Spring {@link BeanPostProcessor} 在 Bean 初始化完成后自动发现
 * 实现了 {@link lyjew.com.lyclaw.interceptor.Interceptor} 接口的 Bean，并解析可选的
 * {@code @Interceptor} 注解中的排序约束信息。
 *
 * <p><b>拦截器发现与收集：</b>在 {@link #postProcessAfterInitialization(Object, String)}
 * 方法中，每发现一个实现了 Interceptor 接口的 Bean，就将其加入 {@code discoveredInterceptors}
 * 列表中以备后续排序和查询。同时，处理器通过反射读取 {@code @Interceptor} 注解中的
 * name、after、before 属性，将排序约束信息分别存储在 {@code afterConstraints} 和
 * {@code beforeConstraints} 两个 Map 中。</p>
 *
 * <p><b>排序约束机制：</b>@Interceptor 注解提供了两种依赖声明方式——after 属性声明当前
 * 拦截器必须在哪些拦截器之后执行，before 属性声明当前拦截器必须在哪些拦截器之前执行。
 * 这些约束信息会被 {@link #getSortedInterceptors()} 方法用于计算拦截器的最终执行顺序。
 * 当前实现使用简单的数值排序（按 getOrder() 返回值升序），约束 Map 的信息可供未来的
 * 拓扑排序实现使用。</p>
 *
 * <p><b>拦截器链在管道中的应用：</b>拦截器链在 LyClaw Pipeline 的每个阶段执行前后被
 * 遍历调用，可以实现诸如请求日志记录、安全鉴权、参数校验、性能监控、结果缓存、
 * 数据脱敏等横切关注点。拦截器的 order 值越小，越先执行。</p>
 *
 * <p><b>线程安全：</b>discoveredInterceptors 列表在启动阶段的 Bean 后处理过程中被
 * 单线程填充（Spring 容器初始化是单线程的），初始化完成后变为只读访问（通过
 * {@link #getDiscoveredInterceptors()} 返回不可修改的视图），因此不需要额外的同步控制。</p>
 *
 * @see Interceptor 拦截器接口，定义了 before/after 生命周期方法和 getOrder 排序方法
 */
public class InterceptorProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(InterceptorProcessor.class);

    private final List<Interceptor> discoveredInterceptors = new ArrayList<>();
    private final Map<String, Class<?>[]> afterConstraints = new LinkedHashMap<>();
    private final Map<String, Class<?>[]> beforeConstraints = new LinkedHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            if (bean instanceof Interceptor interceptor) {
                discoveredInterceptors.add(interceptor);
                Class<?> clazz = bean.getClass();

                Object interceptorAnn = findAnnotation(clazz, "Interceptor");
                if (interceptorAnn != null) {
                    String name = getAttr(interceptorAnn, "name", "");
                    Class<?>[] after = getAttr(interceptorAnn, "after", new Class<?>[0]);
                    Class<?>[] before = getAttr(interceptorAnn, "before", new Class<?>[0]);
                    String key = (name != null && !name.isEmpty()) ? name : clazz.getSimpleName();
                    if (after != null && after.length > 0) {
                        afterConstraints.put(key, after);
                    }
                    if (before != null && before.length > 0) {
                        beforeConstraints.put(key, before);
                    }
                }
                log.debug("Discovered interceptor: {}", interceptor.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("Failed to process @Interceptor bean '{}': {}", beanName, e.getMessage());
        }
        return bean;
    }

    /**
     * Returns discovered interceptors sorted by their declared order.
     */
    public List<Interceptor> getSortedInterceptors() {
        List<Interceptor> sorted = new ArrayList<>(discoveredInterceptors);
        sorted.sort(Comparator.comparingInt(Interceptor::getOrder));
        return sorted;
    }

    public List<Interceptor> getDiscoveredInterceptors() {
        return Collections.unmodifiableList(discoveredInterceptors);
    }

    public int getInterceptorCount() {
        return discoveredInterceptors.size();
    }

    // --- reflection helpers ----------------------------------------------------

    private Object findAnnotation(Class<?> clazz, String simpleName) {
        for (var a : clazz.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals(simpleName)) {
                return a;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T getAttr(Object ann, String attr, T defaultValue) {
        try {
            return (T) ann.getClass().getMethod(attr).invoke(ann);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
