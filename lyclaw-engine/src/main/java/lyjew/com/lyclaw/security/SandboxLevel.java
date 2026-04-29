package lyjew.com.lyclaw.security;

/**
 * 安全沙箱级别枚举 —— 从无限制到 Docker 容器到完全隔离，共 5 个级别。
 *
 * <p>沙箱级别决定了 Tool 在执行时的安全约束。SecurityManager 审批时返回
 * 一个沙箱级别，ToolCallLoop 根据该级别决定工具的执行环境：
 * <ul>
 *   <li>{@link #NONE}：无沙箱，直接执行（仅限白名单工具）</li>
 *   <li>{@link #READ_ONLY}：只读沙箱，可以读文件/查数据库，不能写</li>
 *   <li>{@link #RESTRICTED}：受限沙箱，只能操作临时目录，有内存/CPU 限制</li>
 *   <li>{@link #CONTAINER}：容器沙箱，Docker 容器中执行，独立的文件系统和网络命名空间</li>
 *   <li>{@link #ISOLATED}：完全隔离沙箱，子进程/虚拟机中执行，网络隔离</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ApprovalResult
 */
public enum SandboxLevel {

    /** 无沙箱，直接执行。仅限白名单中的安全工具使用（如计算器、查时间） */
    NONE,

    /** 只读沙箱。可以读取文件、查询数据库，但不能执行写操作 */
    READ_ONLY,

    /** 受限沙箱。只能操作临时目录，有内存和 CPU 时间限制 */
    RESTRICTED,

    /** 容器沙箱。在 Docker 容器中执行，独立的文件系统和网络命名空间，宿主文件系统隔离 */
    CONTAINER,

    /** 完全隔离沙箱。在子进程或虚拟机中执行，网络隔离，资源严格限制 */
    ISOLATED
}