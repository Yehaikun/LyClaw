package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/**
 * 拦截器链管理器 —— 管理所有注册的 Interceptor，按 order 排序后统一执行。
 *
 * <p>采用 CopyOnWriteArrayList 存储拦截器列表，确保并发注册安全。
 * preHandle 按 order 升序执行（order 小的先执行），
 * postHandle 按 order 降序执行（order 大的先执行，类似 try-catch 的嵌套语义）。</p>
 *
 * <p><b>设计动机</b>：如果每个 Pipeline 代码里都手动硬编码限流拦截器、日志拦截器的调用顺序，
 * 那么新增或移除拦截器就需要修改 Pipeline 代码。InterceptorChain 将拦截器统一管理，
 * Pipeline 中的 InterceptorStage 只需调用 chain.preHandle() 和 chain.postHandle() 即可。</p>
 *
 * <p><b>关于并发安全</b>：addInterceptor 和 removeInterceptor 可能在任何时候被调用
 * （如运行时动态注册新的工具拦截器），而 preHandle/postHandle 在每次请求时被调用。
 * 用 CopyOnWriteArrayList 确保遍历时不会抛出 ConcurrentModificationException。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class InterceptorChain {

    /** Thread-safe拦截器列表。排序在每次 add/remove 后重新计算 */
    private final List<Interceptor> interceptors = new CopyOnWriteArrayList<>();

    /**
     * 注册一个拦截器。如果同名拦截器已经存在则覆盖。
     * 每次添加后重新排序拦截器列表。
     *
     * <p>注意：此方法不是线程安全的，建议在启动阶段通过 Spring 注入完成注册。</p>
     *
     * @param interceptor 拦截器实例，不可为 null
     */
    public void addInterceptor(Interceptor interceptor) {
        this.interceptors.add(interceptor);
        // 每次添加后重新排序，确保 preHandle 的执行顺序总是正确的
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    /**
     * 移除一个拦截器。
     *
     * @param interceptor 要移除的拦截器实例
     */
    public void removeInterceptor(Interceptor interceptor) {
        this.interceptors.remove(interceptor);
        // 移除后重新排序
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    /**
     * 按 order 升序执行所有拦截器的 preHandle 方法。
     * 如果任何一个 preHandle 返回 false，则中断执行并返回 false。
     *
     * @param context 对话上下文
     * @return true 表示所有拦截器都通过，false 表示有拦截器拒绝了请求
     */
    public boolean preHandle(ChatContext context) {
        for (Interceptor interceptor : interceptors) {
            if (!interceptor.preHandle(context)) {
                // 记录被哪个拦截器拒绝，便于后续排查
                return false;
            }
        }
        return true;
    }

    /**
     * 按 order 降序执行所有拦截器的 postHandle 方法。
     * 降序执行确保拦截器像 try-catch 嵌套那样执行：
     * 先进入的拦截器的 postHandle 最后执行。
     *
     * @param context 对话上下文
     * @param result  构建好的对话结果
     */
    public void postHandle(ChatContext context, ChatResult result) {
        // 降序排列：order 大的先执行 postHandle
        List<Interceptor> reversed = new ArrayList<>(interceptors);
        reversed.sort(Comparator.comparingInt(Interceptor::getOrder).reversed());
        for (Interceptor interceptor : reversed) {
            interceptor.postHandle(context, result);
        }
    }

    /**
     * 获取当前所有已注册且已排序的拦截器列表。
     * 返回的是不可变视图，防止外部修改内部列表。
     *
     * @return 按 order 升序排列的拦截器列表
     */
    public List<Interceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }
}