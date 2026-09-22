package com.example.mall.common;

/**
 * 当前登录用户的身份信息（从 JWT 里解出来的）。
 *
 * <p>用 Java 16+ 的 {@code record} 而不是普通类，因为它就是个
 * 「装几个不可变字段」的容器：
 * <ul>
 *   <li>字段自动 {@code private final}，天然不可变，线程安全</li>
 *   <li>自动生成构造器、getter、{@code equals}、{@code hashCode}、{@code toString}</li>
 *   <li>不会被误改 —— 拦截器设进去之后，业务代码只能读，不能改</li>
 * </ul>
 *
 * <p>比起 Lombok 的 {@code @Data}，record 是<b>语言层面的</b>不可变，
 * 不需要额外的库。装「值对象」时优先用它。
 *
 * <p><b>注意它和 {@code AdminUser} 实体的区别</b>：
 * AdminUser 是数据库里的一行，字段会随表结构变；
 * LoginUser 是「这次请求的身份」，只装鉴权需要的三个字段。
 * 业务代码需要昵称、手机号时，应该拿这里的 id 去查库，
 * 而不是指望这个对象里什么都有。
 */
public record LoginUser(Long id, String username, String type) {

    /** 是不是管理员 —— 给以后可能出现的「两种身份都能访问」的接口用 */
    public boolean isAdmin() {
        return UserType.ADMIN.equals(type);
    }
}
