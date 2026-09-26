package com.example.mall.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端售后列表里的一行。★ 里程碑 17 新增。
 *
 * <h3>★ 为什么是 {@code extends AfterSaleVO}，而不是新写一个平铺的类？</h3>
 *
 * <p>理由和 {@code AdminOrderVO extends OrderVO} 完全一样，
 * 这里只说这个场景特有的那一面：<b>售后单没有「不能给用户看」的字段</b>
 * （对比订单里的收货人姓名电话住址）。所以管理端要的东西是
 * 用户端的<b>真超集</b>，继承精确地表达了这个事实，
 * 也让 {@code AfterSaleVO} 的字段定义<b>只有一份</b>。
 *
 * <p>平铺一个类就要把那二十来个字段再抄一遍，而抄出来的第二份
 * 迟早会和第一份不一致 —— 症状是「管理端能看到的售后信息比用户端少一个字段」，
 * 因为 {@code non_null} 的存在，它表现为<b>字段静默消失</b>，
 * 前端读到 {@code undefined}，构建和 ESLint 都不会报。
 * （{@code AdminOrderVO} 的类注释把这一段讲得更完整。）
 *
 * <p>⚠️ <b>那什么情况下该平铺？</b>当子类要<b>藏掉</b>父类字段的时候。
 * 这里要加字段，不是藏 —— 所以继承是对的。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminAfterSaleVO extends AfterSaleVO {

    /**
     * 申请人的登录名。
     *
     * <p>⚠️ 它来自 {@code LEFT JOIN member}，所以<b>可能是 null</b> ——
     * 如果那条会员记录被硬删了。用 LEFT JOIN 而不是 INNER JOIN 是刻意的：
     * <b>售后单不该因为会员没了就从管理端消失</b>，
     * 那样运营会「处理着处理着少了几条」而不知道少了。
     * 同 {@code OrderAdminMapper.xml} 里 {@code selectAdminPage} 那段。
     *
     * <p>★ 这也是 {@code AfterSaleMapper.xml} 里那个 {@code <sql id="adminJoin">}
     * 存在的理由（用户端的查询不加这个 join）——
     * 两个查询共用条件片段，但 join 的部分一个有一个没有，
     * 所以 join 也必须是片段的一部分，不能只抽条件。
     */
    private String memberUsername;

    /** 申请人的昵称。同样来自 LEFT JOIN，可能为 null */
    private String memberNickname;
}
