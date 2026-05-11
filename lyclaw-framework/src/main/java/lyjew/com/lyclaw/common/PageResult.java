package lyjew.com.lyclaw.common;

import java.util.List;

/**
 * 分页结果通用封装，用于列表查询的分页返回。
 *
 * <p>包含当前页数据项、总记录数、页码、每页大小，
 * 并提供 hasMore 和 totalPages 便捷计算方法。</p>
 *
 * @param <T> 数据项类型
 */
public class PageResult<T> {

    /** 当前页的数据项列表 */
    private final List<T> items;
    /** 总记录数 */
    private final long total;
    /** 当前页码（从 1 开始） */
    private final int page;
    /** 每页大小 */
    private final int size;

    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /** 静态工厂方法 */
    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    public List<T> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }

    /**
     * 判断是否还有下一页。
     *
     * <p>当 page * size < total 时表示还有更多数据。</p>
     */
    public boolean hasMore() {
        return (long) page * size < total;
    }

    /**
     * 计算总页数。
     *
     * <p>向上取整：(total + size - 1) / size。size ≤ 0 时返回 0。</p>
     */
    public long getTotalPages() {
        if (size <= 0) return 0;
        return (total + size - 1) / size; // 向上取整
    }
}
