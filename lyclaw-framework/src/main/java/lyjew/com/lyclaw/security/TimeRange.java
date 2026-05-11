package lyjew.com.lyclaw.security;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 时间范围实体，用于安全策略中的时间窗口限制。
 *
 * <p>安全策略可以定义时间窗口（如「仅在工作时间内允许」），
 * 通过该实体表示一个闭合的时间区间 [start, end]。
 * {@link #contains(Instant)} 方法用于判断某个时间点是否落在该区间内。</p>
 */
@Data
@Builder
public class TimeRange {
    /** 时间范围的起始时刻（包含） */
    private Instant start;
    /** 时间范围的结束时刻（包含） */
    private Instant end;

    /**
     * 判断指定时间点是否在当前时间范围内。
     *
     * @param time 要检查的时间点
     * @return true 表示 time 在 [start, end] 区间内（包含边界）
     */
    public boolean contains(Instant time) {
        return !time.isBefore(start) && !time.isAfter(end);
    }
}
