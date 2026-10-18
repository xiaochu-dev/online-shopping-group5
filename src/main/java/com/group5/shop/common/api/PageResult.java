package com.group5.shop.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页结果包装（T2-01）。
 *
 * <p>MyBatis-Plus 分页插件返回 {@link IPage}，本类把它裁剪成只含前端需要的字段，
 * 避免把 MP 的内部结构直接暴露给页面/接口。</p>
 *
 * @param <T> 列表元素类型
 * @author 胡成溯（组长）
 */
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private long page;

    /** 每页条数 */
    private long size;

    /** 总页数（至少为 1，便于页面显示「第 1 / 1 页」） */
    private long totalPages;

    private boolean hasPrev;

    private boolean hasNext;

    public PageResult() {
        this.records = new ArrayList<>();
    }

    /**
     * 由 MyBatis-Plus 的分页对象构造。
     *
     * @param page MP 分页结果
     * @param <T>  元素类型
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.records = page.getRecords() == null ? new ArrayList<>() : page.getRecords();
        result.total = page.getTotal();
        result.page = page.getCurrent();
        result.size = page.getSize();
        result.totalPages = Math.max(page.getPages(), 1L);
        result.hasPrev = page.getCurrent() > 1;
        result.hasNext = page.getCurrent() < page.getPages();
        return result;
    }

    /** 手工构造（不经过 MP 分页插件时使用）。 */
    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> result = new PageResult<>();
        result.records = records == null ? new ArrayList<>() : records;
        result.total = total;
        result.page = Math.max(page, 1);
        result.size = Math.max(size, 1);
        result.totalPages = Math.max((total + result.size - 1) / result.size, 1);
        result.hasPrev = result.page > 1;
        result.hasNext = result.page < result.totalPages;
        return result;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(long totalPages) {
        this.totalPages = totalPages;
    }

    public boolean isHasPrev() {
        return hasPrev;
    }

    public void setHasPrev(boolean hasPrev) {
        this.hasPrev = hasPrev;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }
}
