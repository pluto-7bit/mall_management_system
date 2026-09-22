package com.example.mall.dto;

import lombok.Data;

/**
 * 分页查询条件的<b>基类</b>。
 *
 * <p>商品、分类、订单、会员……每个列表都要分页，都要 {@code pageNum}/{@code pageSize}
 * 和 {@code getOffset()}。如果每个查询 DTO 都抄一遍，改一次规则要改 N 个文件。
 * 抽成基类后，「页码 ↔ 偏移量」的换算式只存在这一份。
 *
 * <p><b>什么时候该抽基类？</b>这里有个经验：
 * <ul>
 *   <li>两个子类就抽，属于「有点早但不过分」——因为第三个子类马上就来</li>
 *   <li>等到五个子类再抽，改起来就痛苦了</li>
 *   <li>只有一个子类的「基类」，那不是抽象，是绕路</li>
 * </ul>
 * 现在正好是两个（Product/Category），后面还有 Order/Member，
 * 就是最合适的时机。
 *
 * <p><b>注意 {@code normalize()} 被设计成可以被子类覆盖的</b>：
 * 基类只管分页参数，子类自己加的条件（比如商品名）由子类自己规范化。
 * 见 {@link ProductQueryDTO#normalize()}。
 */
@Data
public class PageQueryDTO {

    /** 页码，从 1 开始 */
    protected Integer pageNum = 1;

    /** 每页条数 */
    protected Integer pageSize = 10;

    /** 每页条数的上限。不设上限的话，前端传 pageSize=999999 就能把整库拉走 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 前端没传或者传了脏值时的兜底条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 计算 SQL 的 OFFSET。
     *
     * <p>MySQL 的分页写法是 {@code LIMIT offset, pageSize}，
     * 而 offset 表示「跳过前多少条」，不是页码。两者要换算：
     * <pre>
     *   offset = (pageNum - 1) * pageSize
     *
     *   第 1 页：跳过 0 条   LIMIT 0, 10
     *   第 2 页：跳过 10 条  LIMIT 10, 10
     *   第 3 页：跳过 20 条  LIMIT 20, 10
     * </pre>
     *
     * <p>这个方法没有对应的数据库字段，MyBatis 里用 {@code #{offset}} 取值时
     * 会自动调它（走的是 getter）。用 getter 而不是让每个 Service 自己算，
     * 是为了把这个换算式只写一遍。
     */
    public Integer getOffset() {
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : pageSize;
        return (num - 1) * size;
    }

    /**
     * 规范化分页参数，防止前端传脏值。
     *
     * <p>Service 在查询前会调一次。因为前端可能传 {@code pageSize=100000}
     * 或者 {@code pageNum=-1}，不拦的话轻则查出巨量数据拖垮数据库，
     * 重则 SQL 报错。
     *
     * <p>这类「不信任前端输入」的防御，是后端必须做的事 ——
     * 前端做的所有校验都只是为了用户体验，用户完全可以绕过页面直接调接口。
     *
     * <p><b>子类如果加了查询条件字段，要覆盖这个方法并先调 {@code super.normalize()}</b>。
     */
    public void normalize() {
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
    }
}
