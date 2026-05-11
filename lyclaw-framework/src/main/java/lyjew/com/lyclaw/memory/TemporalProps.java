package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * 记忆条目的时间属性封装，控制记忆的时效性衰减与过期判定。
 *
 * 采用指数衰减模型：记忆强度随距创建时间的天数呈指数下降。
 * 衰减速度由 decayFactor 控制，同时支持硬过期时间 expiresAt。
 * 使用 Lombok 自动生成 getter/setter/Builder 等样板方法。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemporalProps {
    /** 记忆创建时间 */
    private Instant createdAt;
    /** 硬过期时间，超过此时间强制标记为过期，null 表示永不过期 */
    private Instant expiresAt;
    /** 最近一次被访问的时间，用于 LRU 等淘汰策略 */
    private Instant lastAccessedAt;
    /** 衰减因子，值越大衰减越快 */
    private double decayFactor;
    /** 初始记忆强度 [0, 1]，1 为最强 */
    private double strength;

    /**
     * 计算当前时间点的衰减后记忆强度。
     *
     * 使用指数衰减公式：strength * e^(-decayFactor * daysSinceCreation)。
     * 衰减因子越大、距创建时间越远，强度越低。
     *
     * @return 当前记忆强度，范围 [0, 1]
     */
    public double computeDecay() {
        long daysSinceCreation = Duration.between(createdAt, Instant.now()).toDays();
        return strength * Math.exp(-decayFactor * daysSinceCreation);
    }

    /**
     * 判断记忆是否已超过硬过期时间。
     *
     * 仅当 expiresAt 非空且当前时间已超过时才返回 true，
     * expiresAt 为 null 表示该记忆无硬过期限制（仅靠衰减淘汰）。
     *
     * @return true 表示已过期，应被清理
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
