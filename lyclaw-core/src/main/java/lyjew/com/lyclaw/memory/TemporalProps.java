package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemporalProps {
    private Instant createdAt;
    private Instant expiresAt;
    private Instant lastAccessedAt;
    private double decayFactor;
    private double strength;

    public double computeDecay() {
        long daysSinceCreation = Duration.between(createdAt, Instant.now()).toDays();
        return strength * Math.exp(-decayFactor * daysSinceCreation);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
