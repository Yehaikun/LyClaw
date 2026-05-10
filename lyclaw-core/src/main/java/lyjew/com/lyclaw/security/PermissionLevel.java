package lyjew.com.lyclaw.security;

/**
 * 权限级别 —— RBAC + 工具级权限 + 会话级权限的基础枚举。
 *
 * <p>级别递进: DENY < READ < EXECUTE_SAFE < EXECUTE_MODIFY < EXECUTE_DESTRUCTIVE < ADMIN</p>
 *
 * @since 2.0
 */
public enum PermissionLevel {

    /** 无权限 */
    DENY(0),

    /** 只读: 可读取文件、查询数据库 */
    READ(1),

    /** 安全执行: 只读+白名单工具 */
    EXECUTE_SAFE(2),

    /** 修改执行: 可写入临时目录 */
    EXECUTE_MODIFY(3),

    /** 破坏性执行: 可删除、可修改系统配置 */
    EXECUTE_DESTRUCTIVE(4),

    /** 管理员: 完全权限 */
    ADMIN(5);

    private final int level;

    PermissionLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean satisfies(PermissionLevel required) {
        return this.level >= required.level;
    }
}
