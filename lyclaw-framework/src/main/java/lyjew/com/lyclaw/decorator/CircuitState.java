package lyjew.com.lyclaw.decorator;

/**
 * 断路器状态枚举 — 替代原先的字符串常量 "CLOSED"/"OPEN"/"HALF_OPEN"。
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
