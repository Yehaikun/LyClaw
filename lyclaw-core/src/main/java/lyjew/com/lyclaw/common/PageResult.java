package lyjew.com.lyclaw.common;

import java.util.List;

/**
 * 分页查询返回值 —— 所有需要分页的场景统一使用。
 *
 * <p><b>设计动机</b>：当查询返回大量结果时，需要分页机制来避免一次传输过多数据。
 * PageResult 封装了当前页数据、总数、页码和每页大小，并提供便捷方法
 * hasMore() 和 getTotalPages() 供前端分页组件使用。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>MemoryManager.search() 的分页返回值</li>
 *   <li>TaskLedger.getRecords() 的分页查询</li>
 *   <li>SessionStorage.getAll() 的分页查询</li>
 * </ul>
 * </p>
 *
 * @param <T> 当前页数据的元素类型
 * @since 1.0
 * @author LyClaw Team
 */
public class PageResult<T> {

    /** 当前页的数据列表 */
    private final List<T> items;

    /** 总记录数 —— 满足查询条件的结果总数，不是当前页的数量 */
    private final long total;

    /** 当前页码 —— 从 1 开始 */
    private final int page;

    /** 每页大小 —— 每页最多包含的数据条数 */
    private final int size;

    /**
     * 构造一个 PageResult 实例。
     *
     * @param items 当前页数据
     * @param total 总记录数
     * @param page  当前页码（从 1 开始）
     * @param size  每页大小
     */
    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /**
     * 便捷工厂方法 —— 创建一页结果。
     *
     * @param <T>   元素类型
     * @param items 当前页数据
     * @param total 总记录数
     * @param page  当前页码
     * @param size  每页大小
     * @return PageResult 实例
     */
    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    /** @return 当前页数据 */
    public List<T> getItems() { return items; }

    /** @return 总记录数 */
    public long getTotal() { return total; }

    /** @return 当前页码 */
    public int getPage() { return page; }

    /** @return 每页大小 */
    public int getSize() { return size; }

    /** @return 是否还有更多数据（当前页之后还有数据） */
    public boolean hasMore() {
        return (long) page * size < total;
    }

    /** @return 总页数 */
    public long getTotalPages() {
        if (size <= 0) return 0;
        return (total + size - 1) / size;
    }
}