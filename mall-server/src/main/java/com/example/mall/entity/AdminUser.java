package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员实体 —— 对应 {@code admin_user} 表。
 *
 * <p><b>★ 这个类里有一个字段绝对不能返回给前端：{@code password}。</b>
 *
 * <p>它存的是 BCrypt 加密后的哈希值（形如
 * {@code $2b$10$U96OyMu7hsN9...}），严格来说不是明文密码，
 * 但泄露出去一样危险 —— 攻击者可以离线暴力破解它，
 * 而且同一个哈希值在任何地方都是通用的。
 *
 * <p>这就是本项目<b>第一次真正需要 VO 的场景</b>：
 * <pre>
 *   AdminUser（Entity）  → 只在 Service 和 Mapper 之间流转，可以带密码
 *   LoginVO / AdminInfoVO（VO） → 返回给前端，绝对不能带密码
 * </pre>
 *
 * <p>对比一下 Category：它的字段全都能公开，所以直接当 VO 用没问题。
 * <b>要不要 VO，取决于有没有「不该给前端看的东西」，
 * 而不是取决于「规范怎么写」。</b>
 */
@Data
public class AdminUser {

    private Long id;

    /** 登录账号 */
    private String username;

    /**
     * 密码的 BCrypt 哈希值。
     *
     * <p><b>BCrypt 哈希和 MD5/SHA 有什么区别？为什么不能用 MD5 存密码？</b>
     * <ol>
     *   <li><b>自带盐值</b>：同一个密码每次加密结果都不同，
     *       攻击者没法用「彩虹表」（预先算好的哈希字典）直接反查。
     *       MD5 不加盐的话，网上随便就能搜到明文</li>
     *   <li><b>故意很慢</b>：BCrypt 可以调「代价因子」（这里是 10，即 2^10 轮），
     *       单次加密约几十毫秒。对登录来说无感，
     *       但攻击者想暴力枚举就要慢上几百万倍。
     *       而 MD5 快到每秒能算几十亿次 —— 这正是它不适合存密码的原因</li>
     * </ol>
     *
     * <p><b>一个容易忽略的细节</b>：BCrypt 只使用密码的前 72 个字节，
     * 超出的部分会被静默忽略。所以「超长密码更安全」在 BCrypt 这里不成立 ——
     * 第 73 个字节开始就无效了。想支持长密码得先做一次 SHA-256 再交给 BCrypt。
     */
    private String password;

    private String nickname;

    /** 状态：1=启用 0=禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
