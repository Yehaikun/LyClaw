package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting interceptor -- limits requests per time window using a simple token bucket.
 *
 * <p>Each session gets an independent bucket. Tokens refill at a fixed rate.
 * Requests without tokens are rejected (preHandle returns false).</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Slf4j
@Component
public class RateLimitInterceptor implements Interceptor {

    /** Default permits per second */
    private int permitsPerSecond = 10;

    /** Nanoseconds per permit */
    private static final long NANOS_PER_PERMIT = 1_000_000_000L;

    /** Token bucket state per session: next available time (nanos) */
    private final Map<String, Long> sessionBuckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(ChatContext context) {
        String sessionId = context.getSession() != null ? context.getSession().getId() : null;
        if (sessionId == null) {
            return true; // no session, allow
        }

        long now = System.nanoTime();
        long intervalNs = NANOS_PER_PERMIT / permitsPerSecond;

        Long nextAvailable = sessionBuckets.get(sessionId);
        if (nextAvailable == null || now >= nextAvailable) {
            // First request or bucket has refilled
            sessionBuckets.put(sessionId, now + intervalNs);
            return true;
        }

        // Check if within rate limit
        if (now >= nextAvailable) {
            sessionBuckets.put(sessionId, now + intervalNs);
            return true;
        }

        // Rate limited
        long waitMs = (nextAvailable - now) / 1_000_000;
        log.warn("Rate limit hit for session {}: need to wait {}ms", sessionId, waitMs);
        return false;
    }

    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        // Could log rate limit stats here
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }
}
