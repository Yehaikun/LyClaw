package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
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

    /** 拦截器列表，使用 CopyOnWriteArrayList 保证并发读写的安全性 */
    private final List<Interceptor> interceptors = new CopyOnWriteArrayList<>();

    /**
     * 添加一个拦截器到链中，并按优先级升序重新排序。
     *
     * @param interceptor 要添加的拦截器实例
     */
    public void addInterceptor(Interceptor interceptor) {
        this.interceptors.add(interceptor);
        // 按优先级升序排列，order值越小越靠前
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    /**
     * 从链中移除一个拦截器，并重新排序。
     *
     * @param interceptor 要移除的拦截器实例
     */
    public void removeInterceptor(Interceptor interceptor) {
        this.interceptors.remove(interceptor);
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    /**
     * 按优先级顺序依次执行所有拦截器的前置处理。
     * 任一拦截器返回 false 则立即中断，不再执行后续拦截器。
     *
     * @param context 聊天上下文
     * @return 全部通过返回 true，被拦截返回 false
     */
    public boolean preHandle(ChatContext context) {
        for (Interceptor interceptor : interceptors) {
            if (!interceptor.preHandle(context)) {
                return false; // 中断执行，阻止请求继续处理
            }
        }
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
        List<Interceptor> reversed = new ArrayList<>(interceptors);
        // 逆序排列，高优先级的拦截器后执行 postHandle
        reversed.sort(Comparator.comparingInt(Interceptor::getOrder).reversed());
        for (Interceptor interceptor : reversed) {
            interceptor.postHandle(context, result);
        }
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
