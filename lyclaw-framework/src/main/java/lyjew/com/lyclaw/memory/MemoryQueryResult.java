package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 记忆检索结果封装对象。
 *
 * 除返回排序后的记忆条目列表外，还携带命中总数、查询耗时和检索方法标识，
 * 用于性能监控和结果展示。使用 Lombok 自动生成 getter/setter/Builder 等样板方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryQueryResult {
    /** 排序后的记忆条目列表，按相关性降序 */
    private List<MemoryEntry> entries;
    /** 检索命中总数（过滤前的候选数量） */
    private int totalHits;
    /** 查询耗时（毫秒） */
    private long queryTimeMs;
    /** 实际使用的检索方法标识（如 "fusion"、"vector"、"keyword"） */
    private String retrievalMethod;
}
