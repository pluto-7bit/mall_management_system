package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端售后列表的查询条件。★ 里程碑 17 新增。
 *
 * <pre>
 *   GET /api/admin/after-sales?status=0&amp;afterSaleNo=AS2026...&amp;memberKeyword=张&amp;type=2
 * </pre>
 *
 * <p>四个条件都是可选的，全部为 null 时返回全部（分页）。
 *
 * <h3>★ 它比用户端多了三个条件，而且这三个都是「管理端才该有的」</h3>
 *
 * <p>{@code memberKeyword} 尤其明显：它能跨会员搜。用户端那个
 * {@link AfterSaleQueryDTO} 里连这个字段都不存在 ——
 * 不是因为 Service 会拦住，而是因为<b>类型层面就写不出来</b>。
 * 这条纪律在本项目里已经立过好几次：
 * <b>结构上做不到的事，比代码里记得去做的事，可靠得多。</b>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminAfterSaleQueryDTO extends PageQueryDTO {

    /** 状态筛选，null = 全部。取值见 {@code AfterSaleStatus} */
    private Integer status;

    /**
     * 精确匹配售后单号。
     *
     * <p>★ <b>精确匹配，不是模糊搜索</b>。管理员是拿着用户报的号来查的
     * （「我的 AS20260925… 处理得怎么样了」），那种场景要的是「一条命中」，
     * 不是「列出所有以 AS2026 开头的单」。
     *
     * <p>⚠️ 用 {@code =} 而不是 {@code LIKE} 还有一个实际好处：
     * {@code after_sale_no} 上有唯一索引，等值查询走索引；
     * {@code LIKE '%x%'} 是全表扫 —— 见 {@code OrderAdminMapper.xml}
     * 里关于 {@code memberKeyword} 那段「刻意接受全表扫」的讨论。
     * <b>能走索引的地方就该走索引，这里的实现成本是零。</b>
     */
    private String afterSaleNo;

    /**
     * 按会员模糊搜索（用户名或昵称任一命中）。
     *
     * <p>镜像 {@code OrderQueryDTO.memberKeyword} 的写法：
     * 子查询{@code member} 表，{@code LIKE '%x%'}，<b>刻意接受全表扫</b> ——
     * 管理员记人的时候记的通常是昵称，而昵称没有索引可用。
     * 数据量大了要换全文索引，但那是数据量的问题，不是写法的问题。
     */
    private String memberKeyword;

    /** 按售后类型筛：1=仅退款 2=退货退款。null = 全部 */
    private Integer type;
}
