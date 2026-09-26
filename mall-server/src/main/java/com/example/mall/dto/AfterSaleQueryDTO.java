package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户端「我的售后」的查询条件。★ 里程碑 17 新增。
 *
 * <h3>★ 为什么它没有 memberId？</h3>
 *
 * <p>和 {@link ShopOrderQueryDTO} 一字不差的理由：{@code memberId} 来自 JWT，
 * 不是来自请求参数。<b>这个类里没有这个字段，所以 Service 想「按前端传的会员查」
 * 都写不出来（类型层面就做不到）。</b>
 *
 * <p>而且这个 Service 方法会调 {@code AfterSaleMapper.selectPageByMember}
 * 和 {@code countByMember}，它们各自把 {@code memberId} 收成<b>独立参数</b>——
 * 调用方必须显式写出「用谁的 id 来查」，那个 id 从哪来一眼可见。
 * 理由见 {@code OrderMapper.selectPageByMember} 的 javadoc。
 *
 * <h3>★ 和管理端的 {@link AdminAfterSaleQueryDTO} 为什么是两个类</h3>
 *
 * <p>管理端要多三个条件（精确售后单号、按会员模糊搜、按类型筛），
 * 用户端一个都不该有。如果图省事共用一个，
 * 用户就能请求 {@code /api/shop/after-sales?memberKeyword=张} ——
 * 虽然后端不会拿它去跨会员查，但「这个参数存在」本身就是个邀请。
 * 和 {@code ShopOrderQueryDTO} / {@code OrderQueryDTO} 的分法完全一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AfterSaleQueryDTO extends PageQueryDTO {

    /**
     * 状态筛选，为 null 时不筛选（等于「全部」）。
     *
     * <p>取值见 {@code AfterSaleStatus}：0=待审核 1=待买家寄回 2=待卖家收货
     * 3=退款完成 4=已拒绝 5=已撤销。
     *
     * <p><b>★ 和订单那边一样，「全部」用 null 表示，不用 0 或 -1 这类哨兵值</b>——
     * 因为 0 是一个真实状态（待审核）。用 0 表示「全部」的话，
     * 「只看待审核」这个功能就永远做不出来，而且症状是
     * 「筛选待审核时列出了全部售后」这种不报错的错。
     *
     * <p>非法值（比如 99）不做白名单校验，理由同 {@code ShopOrderQueryDTO.status}：
     * 它走 {@code #{}} 占位符，最坏后果是查不到任何行 ——
     * <b>非法值「不可能看到不该看的数据」时，静默返回空结果就好。</b>
     */
    private Integer status;
}
