package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员收货地址实体 —— 对应 {@code member_address} 表。
 *
 * <p><b>★ 注意这里没有 memberId 之外的任何「归属」信息。</b>
 * 地址属于谁，只由 {@code memberId} 一个字段决定。
 * 这意味着查询地址列表时 <b>必须带上 {@code WHERE member_id = ?}</b>，
 * 否则就会查出别人的地址 —— 这是本项目里最典型的一类越权漏洞。
 *
 * <p>所以这个类的所有查询方法都强制要求传 memberId
 * （见 {@link com.example.mall.mapper.MemberAddressMapper}），
 * 而且 Service 里的 memberId 一律来自 {@code UserContext}，
 * <b>绝不允许从请求参数里取</b>。
 */
@Data
public class MemberAddress {

    private Long id;

    /** 所属会员 id。所有查询都必须以它为第一过滤条件 */
    private Long memberId;

    /** 收货人姓名 */
    private String receiver;

    /** 联系电话 */
    private String phone;

    /** 所在地区（省市区）。真实项目里由三级联动选择器产生 */
    private String region;

    /** 详细地址（街道门牌） */
    private String detail;

    /**
     * 是否默认地址：1=是 0=否。
     *
     * <p>用 {@code Integer} 而不是 {@code Boolean}，是为了和数据库的
     * {@code TINYINT} 对应 —— MyBatis 能把 TINYINT 映射成 Boolean，
     * 但那样在 XML 里写条件时容易和 0/1 混起来。
     * 全项目统一用 Integer 表达 0/1 状态，保持一致性。
     */
    private Integer isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 拼出可以直接印在快递单上的完整地址。
     *
     * <p>{@code region + detail} 中间为什么要加空格？
     * 因为用户填的时候「广东省深圳市南山区」和「科技园路 1 号」
     * 直接连起来会变成「南山区科技园路 1 号」——
     * 碰巧没问题，但「北京市朝阳区」+「望京 SOHO」连起来就是
     * 「朝阳区望京 SOHO」读起来别扭。加个空格最省事。
     *
     * <p><b>这是个「展示逻辑放在哪」的问题。</b>
     * 放在 Entity 里是因为它只依赖自己的字段，不依赖任何外部状态，
     * 而且下单快照和地址列表两处都要用同一份拼法 ——
     * 抽成方法就保证了两处拼出来的一定一样。
     * 如果哪天要按不同地区用不同的拼法（比如港澳台），
     * 改这一个方法就够了。
     */
    public String fullAddress() {
        return region + " " + detail;
    }
}
