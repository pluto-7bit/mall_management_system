package com.example.mall.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端订单列表里的一行。
 *
 * <h3>★ 为什么是 {@code extends OrderVO}，而不是新写一个平铺的类？</h3>
 *
 * <p>因为管理端要显示的东西是用户端的<b>超集</b>：
 * <pre>
 *   用户端看到：自己那笔订单的全部信息
 *   管理端看到：完全相同的订单信息  +  这笔订单【是谁的】
 * </pre>
 * 继承精确地表达了「同一个东西，多了两个字段」这个事实，
 * 也让 {@code OrderVO} 的字段定义<b>只有一份</b>。
 *
 * <p>如果新写一个平铺的类，就要把那十来个字段再抄一遍 ——
 * 而它们的类型、名字、含义完全一样。抄出来的第二份迟早会和第一份不一致
 * （加了字段改了一边忘了另一边），而这类不一致的症状是
 * 「管理端能看到的订单信息比用户端少一个字段」，
 * 因为 {@code non_null} 的存在，它表现为<b>字段静默消失</b>，
 * 前端读到 {@code undefined}，构建和 ESLint 都不会报。
 *
 * <p>⚠️ <b>那什么情况下该平铺、不该继承？</b>
 * 当子类要<b>藏掉</b>父类字段，或者两者字段含义有分歧的时候。
 * 继承带来的耦合是「父类加字段子类也跟着有」，那在这个场景里
 * 恰恰是我们要的。{@code payDeadline} 也可以当例子：
 * 管理端不显示倒计时，但它跟着继承过来<b>并不是问题</b> ——
 * 它是个正确的派生值，多给一个正确的字段无害
 * （真要藏字段，就该考虑组合而不是继承）。
 *
 * <h3>⚠️ 注意它没有 MemberVO 那种「嵌套对象」</h3>
 *
 * <p>会员信息只带了用户名和昵称两个字段，而不是嵌一个完整的会员对象。
 * 因为管理端订单列表要显示的就是「这单是谁的」，
 * 而完整的会员对象里有手机号、状态等一堆列表里根本用不到的东西。
 * <b>接口返回什么，取决于消费它的那个界面需要什么，而不是「库里有什么」。</b>
 * （真要查会员详情，管理端有会员管理页面。）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminOrderVO extends OrderVO {

    /**
     * 下单会员的登录名。
     *
     * <p>⚠️ 它来自 {@code LEFT JOIN member}，所以<b>可能是 null</b> ——
     * 如果那条会员记录被硬删了。用 LEFT JOIN 而不是 INNER JOIN 是刻意的，
     * 见 {@code OrderAdminMapper.xml} 的注释：订单不该因为会员没了就从管理端消失。
     */
    private String memberUsername;

    /** 下单会员的昵称。同样来自 LEFT JOIN，可能为 null */
    private String memberNickname;
}
