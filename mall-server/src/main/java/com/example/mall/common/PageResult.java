package com.example.mall.common;

import lombok.Data;

import java.util.List;

/**
 * 分页结果包装。
 *
 * <p>列表接口不能只返回一个数组，因为前端还要画分页器，需要知道
 * 「总共多少条、当前第几页、每页几条」。所以把数据和分页信息一起返回：
 *
 * <pre>
 * {
 *   "list":     [ {...}, {...} ],
 *   "total":    37,
 *   "pageNum":  2,
 *   "pageSize": 10,
 *   "pages":    4
 * }
 * </pre>
 *
 * <p><b>关于 pages 字段</b>：它其实是 {@code Math.ceil(total / pageSize)}，
 * 前端自己也能算。但算这个要考虑整数除法的坑（{@code 37/10 = 3} 而不是 4），
 * 放在后端算一次，前端就不用每个列表页都重复踩一遍。
 *
 * @param <T> 列表里元素的类型
 */
@Data
public class PageResult<T> {

    /** 当前页的数据 */
    private List<T> list;

    /** 总记录数（不是当前页条数） */
    private long total;

    /** 当前页码，从 1 开始 */
    private int pageNum;

    /** 每页条数 */
    private int pageSize;

    /** 总页数 */
    private int pages;

    private PageResult(List<T> list, long total, int pageNum, int pageSize) {
        this.list = list;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        // 向上取整：37 条、每页 10 条 → 4 页
        // 用 (total + pageSize - 1) / pageSize 而不是 Math.ceil，
        // 因为整数除法直接算更精确，也避免 double 转换
        this.pages = (int) ((total + pageSize - 1) / pageSize);
    }

    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        return new PageResult<>(list, total, pageNum, pageSize);
    }

    /** 空结果，用于查询条件不匹配任何数据时 */
    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return new PageResult<>(List.of(), 0L, pageNum, pageSize);
    }
}
