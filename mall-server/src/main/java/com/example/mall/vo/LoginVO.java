package com.example.mall.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录成功后的返回内容。
 *
 * <p><b>★ 这个类存在的唯一理由，就是「不能让 password 泄漏出去」。</b>
 *
 * <p>如果偷懒直接返回 {@code AdminUser} 实体，JSON 里就会带上
 * {@code "password": "$2b$10$U96OyMu..."}。虽然那是哈希不是明文，
 * 但泄露出去一样是事故 —— 攻击者可以离线慢慢爆破，
 * 而且这个哈希在任何系统里都是同一个值。
 *
 * <p>用 VO 的好处是<b>白名单机制</b>：
 * 只有这里声明的字段才会出现在响应里，实体上将来新增什么字段
 * 都不会自动泄漏出去。反过来，如果直接返回实体，
 * 就成了黑名单 —— 你得记住「这个字段不能加」「那个字段要排除」，
 * 迟早会漏。
 *
 * <p><b>这类事故真实发生过很多次</b>：某次需求给用户表加了个
 * {@code id_card}（身份证号）字段，接口直接返回实体的那种写法，
 * 立刻就跟着泄漏了，因为没人想到要去改那个「跟本次需求无关」的接口。
 *
 * <p>用 {@code @Builder} 而不是一堆 setter，是因为这是个纯输出对象，
 * 在 Service 里一次性构造完就不该再改。Builder 写法一眼能看出
 * 每个字段取了什么值，比连着写五行 {@code vo.setXxx(...)} 清楚。
 */
@Data
@Builder
public class LoginVO {

    /**
     * JWT 凭证。
     *
     * <p>前端拿到后存在 localStorage，之后每个请求都放在
     * {@code Authorization: Bearer <token>} 请求头里带回来。
     */
    private String token;

    private Long id;

    private String username;

    private String nickname;
}
