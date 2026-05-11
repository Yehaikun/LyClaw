package lyjew.com.lyclaw.framework.constant;

/**
 * 沙箱安全级别枚举，定义工具执行的隔离与安全控制级别。
 */
public enum SandboxLevel {
    /** 无隔离，工具在无限制的环境中执行 */
    NONE,
    /** 只读模式，仅允许读取操作，禁止写入 */
    READ_ONLY,
    /** 受限模式，允许读写但有明确的权限限制 */
    RESTRICTED,
    /** 容器模式，工具在独立的容器中执行 */
    CONTAINER,
    /** 完全隔离，工具在高度隔离的安全环境中执行 */
    ISOLATED
}
