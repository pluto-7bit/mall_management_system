package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端订单列表的查询条件。
 *
 * <p>命名跟随既有惯例：<b>不带前缀的是管理端</b>（{@code ProductQueryDTO}），
 * 带 {@code Shop} 前缀的是用户端（{@code ShopProductQueryDTO}）。
 * 所以这里是 {@code OrderQueryDTO}，用户端那个叫 {@code ShopOrderQueryDTO}。
 *
 * <p>前端请求
 * {@code GET /api/admin/orders?pageNum=1&status=1&orderNo=2026...&memberKeyword=张}
 * 时，Spring MVC 会按名字自动填进同名字段。
 *
 * <h3>★ 为什么搜索分成两个字段，而不是合成一个 keyword？</h3>
 *
 * <p>因为这两件事的<b>匹配方式不一样</b>：
 * <pre>
 *   orderNo       → 精确匹配（订单号是一个标识符，不是一段文本）
 *   memberKeyword → 模糊匹配（记不住完整用户名，只能记得大概）
 * </pre>
 *
 * <p>合成一个输入框就得靠「这串字符看起来像不像订单号」去猜该用哪种匹配。
 * 那种启发式判断<b>正是本项目一路在避免的东西</b> ——
 * 参见 {@code utils/query.js} 开头那段：同一个语义判断有两个实现、
 * 或者靠"看起来像"来分支，产生的是<b>不报错的不一致</b>，
 * 排查成本最高。宁可界面上多一个输入框。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQueryDTO extends PageQueryDTO {

    /** 状态筛选，为 null 时不筛选（「全部」）。取值见 {@code OrderStatus} */
    private Integer status;

    /**
     * 订单号，<b>精确匹配</b>。
     *
     * <p>⚠️ {@link #normalize()} 里会 {@code trim()} 它，这一步是<b>承重的</b>，
     * 不是顺手加的：
     *
     * <p>用户是从订单详情页/客服记录里<b>复制粘贴</b>订单号的，
     * 粘进来带一个尾随空格是常事。而 LIKE 模糊匹配天然不怕这个
     * （{@code '%abc %'} 照样能匹配到 {@code abc}），
     * <b>精确匹配怕</b> —— {@code 'abc ' != 'abc'}，
     * 结果是查不到任何行，而界面上看不出任何异常，
     * 用户只会以为「这单不存在」。
     *
     * <p><b>越精确的匹配，对输入里看不见的字符越敏感。</b>
     */
    private String orderNo;

    /**
     * 会员用户名/昵称，<b>模糊匹配</b>（两个字段任一命中即可）。
     *
     * <p>为什么要把昵称也带上：管理员记人的时候记的通常是昵称
     * （「张三」），而不是登录名（{@code zhangsan}）。
     *
     * <p>⚠️ {@code LIKE '%x%'} <b>用不上 member 表的 uk_username 索引</b>，
     * 是一次全表扫。这是刻意接受的 —— 和项目里其余 LIKE 搜索一致。
     * 真实项目里会员量大了要换成全文索引或搜索引擎，
     * 但那是数据量的问题，不是写法的问题。
     */
    private String memberKeyword;

    /**
     * {@inheritDoc}
     *
     * <p>先处理分页，再把空白串统一成 null ——
     * 这样 XML 里的 {@code <if test="orderNo != null">} 才会准确。
     * <b>不清理的话，前端传 {@code orderNo=}（空串）会拼出
     * {@code AND o.order_no = ''}，一条都查不到</b>，
     * 而用户以为自己什么都没填。
     */
    @Override
    public void normalize() {
        super.normalize();

        // ★ trim 放在判空之前：全是空格的串 trim 之后就是空串，会被下面的判断清成 null
        if (orderNo != null) {
            orderNo = orderNo.trim();
        }
        if (orderNo != null && orderNo.isEmpty()) {
            orderNo = null;
        }

        if (memberKeyword != null) {
            memberKeyword = memberKeyword.trim();
        }
        if (memberKeyword != null && memberKeyword.isEmpty()) {
            memberKeyword = null;
        }
    }
}
