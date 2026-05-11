package lyjew.com.lyclaw.common;

import java.util.List;

public class PageResult<T> {

    private final List<T> items;
    private final long total;
    private final int page;
    private final int size;

    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    public List<T> getItems() { return items; }

    public long getTotal() { return total; }

    public int getPage() { return page; }

    public int getSize() { return size; }

    public boolean hasMore() {
        return (long) page * size < total;
    }

    public long getTotalPages() {
        if (size <= 0) return 0;
        return (total + size - 1) / size;
    }
}
