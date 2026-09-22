package com.example.mall.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 返回给用户端的会员信息。
 *
 * <p>刻意<b>不包含 {@code password}</b>，这就是它作为 VO 存在的唯一理由。
 * {@code status} 也没放进来 —— 用户端不需要知道自己的状态字段，
 * 被禁用的会员在登录时就会被拦住，压根到不了这里。
 *
 * <p><b>为什么这个类和 {@link AdminInfoVO} 长得几乎一样却不合并？</b>
 * 它们的字段碰巧相同（都是 id/username/nickname），但这是巧合。
 * 会员资料以后会加头像、积分、会员等级；管理员会加角色、权限菜单。
 * 现在为了少写 20 行代码把它们合并，三个月后就得再拆开 ——
 * 而那时候已经有一堆接口的返回类型连着它们了。
 *
 * <p><b>判断两个类该不该合并，看的不是「现在字段是否相同」，
 * 而是「以后会不会因为不同的原因而变化」。</b>
 * 这里是两个完全独立的业务概念，答案是不会一起变。
 */
@Data
@Builder
public class MemberInfoVO {

    private Long id;

    private String username;

    private String nickname;

    /**
     * 手机号。
     *
     * <p>返回给「本人」是没问题的 —— 这是他自己的信息。
     *
     * <p>⚠️ 但要留意一个常见的隐私事故：
     * 把手机号返回在<b>别人也能看到</b>的接口里（比如商品评价区显示
     * 「张三 138****8001 评价了...」）。那属于「个人信息对外暴露」，
     * 合规上是要出问题的。
     *
     * <p><b>★ 里程碑 12 已按这条执行。</b>当初这里写的是一句预言
     * （「等做到里程碑 12（商品评价）时，记得只返回昵称」），
     * 现在兑现了：{@link ReviewVO} —— 那个<b>匿名游客也能拉取</b>的评价列表 ——
     * 里面的 {@code memberNickname} 只来自 {@code member.nickname}，
     * <b>没有 {@code username}，也没有 {@code phone}</b>。
     *
     * <p>★ 顺带看一个更有意思的对照：{@link AdminReviewVO}（管理端）
     * <b>给了 {@code username}，仍然不给 {@code phone}</b>。
     * 同一个「评价者是谁」的问题，在两条路径上答案不同 ——
     * <b>判据不是「这个字段敏感吗」，而是「看这个接口的人是谁」</b>：
     * 管理员本来就在管理端订单列表里看得见会员的登录名，
     * 但手机号在任何一端都没有一个真的需要它的读者。
     */
    private String phone;
}
