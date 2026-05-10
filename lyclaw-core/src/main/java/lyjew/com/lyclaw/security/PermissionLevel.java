package lyjew.com.lyclaw.security;

public enum PermissionLevel {

    DENY(0),
    READ(1),
    EXECUTE_SAFE(2),
    EXECUTE_MODIFY(3),
    EXECUTE_DESTRUCTIVE(4),
    ADMIN(5);

    private final int level;

    PermissionLevel(int level) {
        this.level = level;
    }

    public int getLevel() { return level; }

    public boolean satisfies(PermissionLevel required) {
        return this.level >= required.level;
    }
}
