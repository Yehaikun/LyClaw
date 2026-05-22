package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 拦截器链：管理多个拦截器的注册、排序和执行。
 * 作为Spring组件，前置处理按优先级正序执行，后置处理按优先级逆序执行。
 * 使用 CopyOnWriteArrayList 保证线程安全。
 */
@Component
public class InterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(InterceptorChain.class);

    /** 拦截器列表，使用 CopyOnWriteArrayList 保证并发读写的安全性 */
    private final List<Interceptor> interceptors = new CopyOnWriteArrayList<>();

    /**
     * 添加一个拦截器到链中，并按优先级升序重新排序。
     *
     * @param interceptor 要添加的拦截器实例
     */
    public void addInterceptor(Interceptor interceptor) {
        this.interceptors.add(interceptor);
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
        log.info("🔗 [InterceptorChain] 注册拦截器: {} (order={}) 总数={}",
                interceptor.getClass().getSimpleName(), interceptor.getOrder(), interceptors.size());
    }

    /**
     * 从链中移除一个拦截器，并重新排序。
     *
     * @param interceptor 要移除的拦截器实例
     */
    public void removeInterceptor(Interceptor interceptor) {
        this.interceptors.remove(interceptor);
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
        log.info("🔗 [InterceptorChain] 移除拦截器: {} 剩余={}",
                interceptor.getClass().getSimpleName(), interceptors.size());
    }

    /**
     * 按优先级顺序依次执行所有拦截器的前置处理。
     * 任一拦截器返回 false 则立即中断，不再执行后续拦截器。
     *
     * @param context 聊天上下文
     * @return 全部通过返回 true，被拦截返回 false
     */
    public boolean preHandle(ChatContext context) {
        if (interceptors.isEmpty()) {
            log.debug("🔗 [InterceptorChain] preHandle: 无注册拦截器，跳过");
            return true;
        }
        log.info("🔗 [InterceptorChain] preHandle: 执行 {} 个拦截器 (按order升序)", interceptors.size());
        for (Interceptor interceptor : interceptors) {
            log.info("  ├─ {}(order={})", interceptor.getClass().getSimpleName(), interceptor.getOrder());
            if (!interceptor.preHandle(context)) {
                log.warn("  └─ ⛔ 拦截器 {} 中断了请求处理", interceptor.getClass().getSimpleName());
                return false;
            }
        }
        log.info("  └─ preHandle全部通过");
        return true;
    }

    /**
     * 按优先级逆序依次执行所有拦截器的后置处理。
     * 后置处理采用逆序（类似Servlet Filter的出站），即高优先级最后执行后置。
     *
     * @param context 聊天上下文
     * @param result  聊天处理结果
     */
    public void postHandle(ChatContext context, ChatResult result) {
        if (interceptors.isEmpty()) {
            log.debug("🔗 [InterceptorChain] postHandle: 无注册拦截器，跳过");
            return;
        }
        List<Interceptor> reversed = new ArrayList<>(interceptors);
        reversed.sort(Comparator.comparingInt(Interceptor::getOrder).reversed());
        log.info("🔗 [InterceptorChain] postHandle: 执行 {} 个拦截器 (按order降序)", reversed.size());
        for (Interceptor interceptor : reversed) {
            log.info("  ├─ {}(order={})", interceptor.getClass().getSimpleName(), interceptor.getOrder());
            interceptor.postHandle(context, result);
        }
        log.info("  └─ postHandle完成");
    }

    /**
     * 获取当前拦截器链的不可修改视图。
     *
     * @return 拦截器列表（不可修改）
     */
    public List<Interceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }
}
