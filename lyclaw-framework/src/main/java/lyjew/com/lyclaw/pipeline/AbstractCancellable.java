package lyjew.com.lyclaw.pipeline;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可取消操作的抽象基类，提供线程安全的取消标记和检查方法。
 *
 * <p>适用于长时间运行的任务（如管线阶段、工具执行、模型调用），
 * 在执行循环或关键检查点调用 {@link #isCancelled()} 以支持优雅中断。
 */
public abstract class AbstractCancellable {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 请求取消操作。 */
    public void cancel() {
        cancelled.set(true);
    }

    /** @return 是否已被取消 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 重置取消状态（用于对象复用）。 */
    public void reset() {
        cancelled.set(false);
    }
}
