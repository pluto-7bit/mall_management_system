
package com.example.mall.common;

/**
 * 登录身份类型。
 *
 * <p><b>★ 这个类是干什么的？为什么必须有它？</b>
 *
 * <p>本系统有两张独立的用户表：{@code admin_user}（管理员）和
 * {@code member}（会员）。它们的主键都是各自表里自增的，
 * 所以 <b>{@code admin_user.id = 1} 和 {@code member.id = 1} 会同时存在</b>。
 *
 * <p>JWT 里只放一个 id 的话，就分不清这个 token 到底是谁签发的。
 * 后果非常严重：
 * <pre>
 *   张三注册了一个会员账号，他的 member.id 恰好是 1
 *   他拿着这个 token 去访问 /api/admin/products
 *   拦截器查出 id=1 的管理员，校验通过 —— 他成了管理员
 * </pre>
 * 这个漏洞在自增主键从 1 开始的系统里<b>必然会被触发</b>，
 * 不是「理论上可能」。
 *
 * <p>解决办法就是往 JWT 里塞一个 {@code type} 声明，
 * 校验时先看类型对不对，再看权限。
 * <b>「认证」和「授权」是两个独立的步骤，缺一不可。</b>
 *
 * <p><b>为什么用常量而不是枚举？</b>
 * 因为这两个值要写进 JWT 的 payload 里（本质是 JSON 字符串），
 * 也要和数据库/前端传递。用 String 常量最省事，
 * 不会在序列化上引入额外的心智负担。
 * 如果将来身份类型多到需要遍历（比如做权限分配界面），再换成枚举。
 */
public final class UserType {

    private UserType() {
    }

    /** 管理员（后台管理端） */
    public static final String ADMIN = "ADMIN";

    /** 会员（用户端商城） */
    public static final String MEMBER = "MEMBER";
}
