package lyjew.com.lyclaw.transaction;

/**
 * 事务状态枚举 — 替代原先的字符串常量 "ACTIVE"/"COMMITTED"/"ROLLED_BACK"。
 */
public enum TransactionStatus {
    ACTIVE,
    COMMITTED,
    ROLLED_BACK
}
