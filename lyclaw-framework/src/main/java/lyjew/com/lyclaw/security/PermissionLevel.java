package lyjew.com.lyclaw.security;

/**
 * 权限级别枚举，定义了从低到高的安全权限层级。
 *
 * <p>每个级别有对应的整数值，数值越大权限越高。权限检查采用「满足即通过」
 * 的模型：当前权限级别 >= 所需权限级别时，操作被允许。</p>
 *
 * <ul>
 *   <li>{@code DENY(0)} - 拒绝所有操作</li>
 *   <li>{@code READ(1)} - 只读权限，允许查看文件、日志等非修改操作</li>
 *   <li>{@code EXECUTE_SAFE(2)} - 安全执行权限，允许运行不会产生副作用的命令</li>
 *   <li>{@code EXECUTE_MODIFY(3)} - 修改执行权限，允许运行会修改文件系统的命令</li>
 *   <li>{@code EXECUTE_DESTRUCTIVE(4)} - 破坏性执行权限，允许运行可能删除数据或影响系统的命令</li>
 *   <li>{@code ADMIN(5)} - 管理员权限，允许所有操作包括修改安全配置</li>
 * </ul>
 */
public enum PermissionLevel {

    /** 拒绝所有操作 */
    DENY(0),
    /** 只读 */
    READ(1),
    /** 安全执行 */
    EXECUTE_SAFE(2),
    /** 修改执行 */
    EXECUTE_MODIFY(3),
    /** 破坏性执行 */
    EXECUTE_DESTRUCTIVE(4),
    /** 管理员 */
    ADMIN(5);

    /** 权限级别的整数值，数值越大权限越高 */
    private final int level;

    PermissionLevel(int level) {
        this.level = level;
    }

    /** @return 权限级别的整数值 */
    public int getLevel() { return level; }

    /**
     * 检查当前权限级别是否满足所需级别。
     *
     * @param required 所需的权限级别
     * @return true 表示当前权限满足所需级别（当前级别 >= 所需级别）
     */
    public boolean satisfies(PermissionLevel required) {
        return this.level >= required.level;
    }
}
