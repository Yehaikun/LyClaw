package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

/**
 * 时间属性 —— 控制记忆条目的生命周期和衰减。
 *
 * @since 2.0
 */
@Data
@Builder
public class TemporalProps {

    /** 创建时间 */
    private Instant createdAt;

    /** 过期时间 (null表示不过期) */
    private Instant expiresAt;

    /** 上次访问时间 */
    private Instant lastAccessedAt;

    /** 衰减因子 [0.0, 1.0], 越大衰减越快 */
    private double decayFactor;

    /** 记忆强度初始值, 每次访问+1, 随衰减递减 */
    private double strength;

    public double computeDecay() {
        long daysSinceCreation = java.time.Duration.between(createdAt, Instant.now()).toDays();
        return strength * Math.exp(-decayFactor * daysSinceCreation);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
