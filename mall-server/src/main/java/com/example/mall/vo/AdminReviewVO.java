package com.example.mall.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端评价列表里的一行。
 *
 * <h3>★ 为什么是 {@code extends ReviewVO}，而不是新写一个平铺的类？</h3>
 *
 * <p>理由和 {@link AdminOrderVO} 一字不差：管理端要显示的是用户端的<b>超集</b>。
 * <pre>
 *   用户端看到：这条评价的星级 / 内容 / 晒图 / 时间 / 评价者昵称
 *   管理端看到：完全相同的内容  +  这条评价【挂在哪个商品上】和【是谁写的】
 * </pre>
 * 继承精确地表达了这个「同一个东西，多了几个字段」的事实，
 * 也让 {@code rating} / {@code content} / {@code images} 的定义<b>只有一份</b>。
 *
 * <p>平铺抄一遍的代价在本项目是有先例的：{@code AdminOrderVO} 的注释里写了，
 * 抄出来的第二份迟早会不一致，而因为 {@code non_null} 的存在，
 * 症状是<b>字段静默消失</b>、前端读到 {@code undefined}，构建和 ESLint 都不报。
 *
 * <p>★ 继承还带来一个<b>结构性的</b>好处：Service 里的
 * {@code attachImages(List<? extends ReviewVO>)} 一个方法就能给两个列表
 * 装晒图（{@code ProductReviewServiceImpl}）。平铺的话就要写两遍，
 * 而「写两遍」正是 N+1 和漏改一处的温床。
 *
 * <h3>★★ 注意这里【可以】有 {@code memberUsername}，用户端没有 —— 这是两次判断</h3>
 *
 * <p>判据见 {@link ReviewVO} 的类注释：<b>「看这个接口的人是谁」。</b>
 * <pre>
 *   /api/shop/products/{id}/reviews  → 匿名可调，只给 nickname
 *   /api/admin/reviews               → 必须管理员登录，username 可以给
 * </pre>
 * 管理员在管理端的订单列表里本来就看得到 username（{@code AdminOrderVO.memberUsername}），
 * 所以这里给他并不是「新增了一处泄露」，而是<b>同一个端的一贯做法</b>。
 *
 * <p>⚠️ 但 {@code phone} <b>两个端都不给</b> ——
 * 管理端评价列表没有任何需要手机号的地方，管理端有独立的会员管理页面。
 * 「上一个接口给了所以这个也给」是最容易写出泄露的一种推理。
 *
 * <h3>⚠️ 两个会员字段都可能为 null（{@code LEFT JOIN member}）</h3>
 *
 * <p>和 {@code OrderAdminMapper} 用 LEFT JOIN 的理由一样：
 * <b>会员记录被硬删了，评价不该从管理端列表里消失。</b>
 * 用 INNER JOIN 的话，列表里少一行、而 {@code count} 里不会少
 * （count 不 join），症状是「总数说 10 条、翻到底只有 9 条」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminReviewVO extends ReviewVO {

    /**
     * 被评价的商品 id。
     *
     * <p>★ 给 id 是为了让前端能点进商品；给下面的名字是为了不用再多查一次。
     * （用户端的 {@link ReviewVO} 不给 id —— 调用方本来就知道自己在哪个商品页上，
     * 那是复读。同一条信息给不给，取决于<b>看的人是不是已经知道它</b>。）
     */
    private Long productId;

    /** 商品名。来自 {@code LEFT JOIN product}，商品被删了就是 null */
    private String productName;

    /** 评价者登录名。★ 只在管理端出现，理由见类注释 */
    private String memberUsername;

    /**
     * 评价者昵称。
     *
     * <p>★ 字段名和父类一致，所以 MyBatis 的 {@code AS memberNickname} 直接映射到
     * 父类的字段上 —— 子类不需要重复声明它。这也正是继承在这里很顺的原因：
     * 列表和 count 共用的那段条件不关心字段在哪一层。
     */
    // memberNickname 继承自 ReviewVO
}
