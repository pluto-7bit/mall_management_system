package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端评价列表的查询条件。
 *
 * <p>命名跟随既有惯例：<b>不带前缀的是管理端</b>（{@code OrderQueryDTO}），
 * 带 {@code Shop} 前缀的是用户端（{@code ShopOrderQueryDTO}）。
 * 所以这里是 {@code ReviewQueryDTO}，用户端那个……<b>不存在</b>，见下。
 *
 * <h3>★ 为什么用户端评价列表没有自己的 QueryDTO</h3>
 *
 * <p>因为它<b>只有分页参数</b>（{@code pageNum} / {@code pageSize}），
 * 而且「查哪个商品」是路径参数（{@code /api/shop/products/{id}/reviews}），
 * 不是查询条件。
 *
 * <p>于是它直接用基类 {@link PageQueryDTO} 就够 ——
 * 一个字段都没有的子类不是抽象，是绕路
 * （{@code PageQueryDTO} 的类注释里写着同样的判断，只是方向相反：
 * 那里讲的是「只有一个子类的基类不该存在」，这里讲的是
 * <b>「一个字段都没有的子类同样不该存在」</b>）。
 *
 * <p>而管理端这两个筛选是<b>真的会变化的业务条件</b>，所以它有自己的类。
 *
 * <h3>★ 为什么两个筛选都是「模糊」的，没有精确匹配</h3>
 *
 * <p>和 {@code OrderQueryDTO} 那里「订单号精确、会员模糊」的拆分对照着看：
 * <pre>
 *   订单号  → 是一个【标识符】，用户是复制粘贴来的 → 精确匹配
 *   商品名  → 是一段【文本】，管理员只记得大概        → 模糊匹配
 *   会员    → 同上（而且昵称和用户名都要命中）        → 模糊匹配
 * </pre>
 * 评价列表里没有「标识符」类的筛选条件 —— 评价 id 自己不会有人拿它搜。
 * <b>匹配方式跟着「输入是从哪来的」走，不跟着「字段类型」走。</b>
 * （两个都是 VARCHAR，但订单号是对着抄的、商品名是想起来的。）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReviewQueryDTO extends PageQueryDTO {

    /**
     * 商品名，<b>模糊匹配</b>。
     *
     * <p>⚠️ {@code LIKE '%x%'} <b>用不上 product.name 上的索引</b>，是一次全表扫。
     * 这是刻意接受的 —— 和项目里其余 LIKE 搜索一致（见 {@code OrderQueryDTO}）。
     * 真实项目里商品量大了要换全文索引，那是数据量的问题，不是写法的问题。
     */
    private String productKeyword;

    /**
     * 会员用户名<b>或昵称</b>，模糊匹配（任一命中即可）。
     *
     * <p>带上昵称的理由和 {@code OrderQueryDTO.memberKeyword} 一样：
     * 管理员记人的时候记的通常是昵称（「张三」），而不是登录名（{@code zhangsan}）。
     *
     * <p>★ 这个筛选在 SQL 里写成子查询
     * （{@code r.member_id IN (SELECT id FROM member WHERE ...)}）而不是
     * {@code m.nickname LIKE ...} —— 为了让列表查询和 {@code count} 查询
     * <b>共用同一份条件片段</b>（count 不 join member 表）。
     * 这是 {@code OrderAdminMapper.xml} 已经确立的范式，理由那边写得很详细。
     */
    private String memberKeyword;

    /**
     * {@inheritDoc}
     *
     * <p>先处理分页，再把空白串统一成 null ——
     * 这样 XML 里的 {@code <if test="productKeyword != null">} 才会准确。
     *
     * <p>⚠️ <b>不清理的话，前端传 {@code productKeyword=}（空串）会拼出
     * {@code AND r.product_id IN (SELECT id FROM product WHERE name LIKE '%%')}</b>——
     * 而 {@code '%%'} 匹配<b>所有</b>商品名，等于「悄悄筛了一下，结果和没筛一样」。
     * 那种症状最误导人：用户点了「重置」却看到列表没变化，
     * 会以为是按钮坏了，而不是「空串进了 SQL」。
     *
     * <p>（对比 {@code OrderQueryDTO} 里 {@code orderNo} 空串的症状：
     * 那边是精确匹配，空串变成「一条都查不到」；
     * 这边是模糊匹配，空串变成「一条都不筛」。<b>同一个 bug，两种截然相反的症状</b> ——
     * 这也是为什么这一层清理对【每个】字符串条件都必须做，而不是「看情况」。）
     */
    @Override
    public void normalize() {
        super.normalize();

        // ★ trim 放在判空之前：全是空格的串 trim 之后就是空串，会被下面的判断清成 null
        if (productKeyword != null) {
            productKeyword = productKeyword.trim();
        }
        if (productKeyword != null && productKeyword.isEmpty()) {
            productKeyword = null;
        }

        if (memberKeyword != null) {
            memberKeyword = memberKeyword.trim();
        }
        if (memberKeyword != null && memberKeyword.isEmpty()) {
            memberKeyword = null;
        }
    }
}
