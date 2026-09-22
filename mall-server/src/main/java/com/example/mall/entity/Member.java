package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员实体 —— 对应 {@code member} 表。也就是用户端下单的那些人。
 *
 * <p><b>★ 它和 {@link AdminUser} 的关系，是本项目最容易搞错的地方。</b>
 *
 * <p>两张表结构几乎一样（都有账号、密码、昵称、状态），
 * 很容易让人想「合并成一张 user 表，加个 role 字段不就行了」。
 * 这里刻意<b>不合并</b>，理由：
 *
 * <ol>
 *   <li><b>数据完全独立</b>：会员是「在店里买东西的人」，管理员是「店里的员工」。
 *       现实中他们来自完全不同的注册渠道，没有任何一条记录会同时属于两者。
 *       合并之后，一张几百万人和一张十几个人的表混在一起，
 *       每次查管理员都要带上 {@code WHERE role = 'ADMIN'}，
 *       一旦漏写就会把会员当成管理员查出来 —— 而这类 bug 极其危险</li>
 *   <li><b>字段会各自长开</b>：会员以后要加收货地址、积分、会员等级；
 *       管理员要加角色、可操作的菜单权限。
 *       硬塞进一张表，会得到一堆「对一半记录永远为 NULL」的列</li>
 *   <li><b>爆炸半径</b>：用户端注册接口是全网可调的，
 *       管理端账号是内部开通的。让这两个东西共用一张表，
 *       等于把「公开注册」和「员工账号」放在了同一个爆炸半径内</li>
 * </ol>
 *
 * <p><b>代价</b>：正如 {@link com.example.mall.common.UserType} 里说的，
 * 两边的 id 都从 1 开始，所以 JWT 里<b>必须</b>带 type 声明来区分。
 * 这是分开建表要付的税，但比起上面三条，它便宜得多。
 *
 * <p>和 {@code AdminUser} 一样，{@code password} 字段<b>绝不能返回给前端</b>，
 * 所以用户端也需要 VO（{@link com.example.mall.vo.MemberInfoVO}）。
 */
@Data
public class Member {

    private Long id;

    /** 登录账号，注册时用户自己填。数据库里有唯一索引兜底 */
    private String username;

    /**
     * 密码的 BCrypt 哈希值。
     *
     * <p>★ 和管理员用的是<b>同一个</b> {@code PasswordEncoder} Bean，
     * 因此两边的哈希格式一致（都是 {@code $2b$10$...}）。
     *
     * <p>这是刻意的：都交给 Spring 的 {@code BCryptPasswordEncoder}，
     * 参数（代价因子 10）统一在一处配置。
     * 如果用户端自己 new 一个 {@code BCryptPasswordEncoder}，
     * 哪天要调代价因子就会漏掉一边，而且这种不一致几乎不会被发现 ——
     * 直到有人去做安全审计。
     */
    private String password;

    private String nickname;

    /** 手机号。注册时选填，所以可能为 null */
    private String phone;

    /** 状态：1=正常 0=禁用。管理端可以把恶意注册的会员禁掉 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
