package lyjew.com.lyclaw.security;

/**
 * 沙箱隔离级别枚举，定义代码执行时的安全隔离程度。
 *
 * <p>沙箱级别越高，隔离越严格，可访问的系统资源越少。在审批通过后，
 * 根据操作的风险程度分配不同的沙箱级别：</p>
 *
 * <ul>
 *   <li>{@code NONE} - 无沙箱，直接在当前进程环境中执行（仅限完全可信操作）</li>
 *   <li>{@code READ_ONLY} - 只读沙箱，可读取文件系统但不可写入</li>
 *   <li>{@code RESTRICTED} - 受限沙箱，仅能访问指定的目录和网络资源</li>
 *   <li>{@code CONTAINER} - 容器沙箱，在独立容器（如 Docker）中运行</li>
 *   <li>{@code ISOLATED} - 完全隔离，在独立的虚拟机或严格限制的容器中运行</li>
 * </ul>
 */
public enum SandboxLevel {
    /** 无沙箱 */
    NONE,
    /** 只读沙箱 */
    READ_ONLY,
    /** 受限沙箱 */
    RESTRICTED,
    /** 容器沙箱 */
    CONTAINER,
    /** 完全隔离 */
    ISOLATED
}
