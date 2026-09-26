package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员拒绝售后申请的请求体。★ 里程碑 17 新增。
 *
 * <pre>
 *   { "rejectReason": "商品已使用，不支持无理由退款" }
 * </pre>
 *
 * <p>对应状态机上的 <b>T4（0 待审核 或 1 待买家寄回 → 4 已拒绝）</b>。
 *
 * <h3>★★ 为什么拒绝的理由是 {@code @NotBlank}（必填）？</h3>
 *
 * <p>因为拒绝是<b>唯一一个会让用户不满、且他无从申诉</b>的动作。
 * 一个「已拒绝」而没有任何理由的售后单，用户能做只有两件事：
 * 再申请一次（然后大概率再被拒），或者打电话给客服 ——
 * 而客服看着这条记录也只能说「我也不知道为什么」。
 *
 * <p><b>必填的理由字段是把「决策」逼成「可以说清楚的决策」。</b>
 * 这条约束很便宜（一个注解），而它挡掉的是一种真实的、反复发生的沟通成本。
 *
 * <p>⚠️ 对比一下「同意」那三个接口：它们<b>没有任何请求体</b> ——
 * 同意不需要解释。参数只在「需要说点什么」的时候存在，
 * 这不是不对称，是不需要。
 *
 * <h3>★ 它是自由文本，不是码</h3>
 *
 * <p>和 {@code AfterSaleReason} 相反。原因码要统计（「这批退货因为什么」），
 * 而拒绝理由是一句给<b>这一个用户</b>看的话。做成码表的话，
 * 管理员会遇到一句想要说的、码表里没有的话。
 */
@Data
public class AfterSaleRejectDTO {

    @NotBlank(message = "请填写拒绝理由")
    @Size(max = 255, message = "拒绝理由最多 255 个字")
    private String rejectReason;
}
