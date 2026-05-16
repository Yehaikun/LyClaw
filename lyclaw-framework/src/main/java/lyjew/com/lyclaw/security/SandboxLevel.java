package lyjew.com.lyclaw.security;

/**
 * 沙箱隔离级别枚举，定义代码执行时的安全隔离程度。
 *
 * <p>定义工具执行时的三种隔离方式：</p>
 *
 * <ul>
 *   <li>{@code DIRECT} - 当前线程直接执行，无隔离（计算、查时间等只读工具）</li>
 *   <li>{@code SANDBOX} - 守护线程 + 临时工作目录隔离（可能写文件的工具）</li>
 *   <li>{@code PROCESS} - 独立 OS 进程执行，通过 CommandExecutor 启动子进程（command/script 工具）</li>
 * </ul>
 */
public enum SandboxLevel {
    /** 直接执行 */
    DIRECT,
    /** 沙箱执行 */
    SANDBOX,
    /** 进程执行 */
    PROCESS
}
