package lyjew.com.lyclaw.security;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class TimeRange {
    private Instant start;
    private Instant end;

    public boolean contains(Instant time) {
        return !time.isBefore(start) && !time.isAfter(end);
    }
}
