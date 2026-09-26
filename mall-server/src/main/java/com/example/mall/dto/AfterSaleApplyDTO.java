package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 申请售后的请求体。★ 里程碑 17 新增。
 *
 * <pre>
 *   {
 *     "orderNo": "20260925005831482913",
 *     "orderItemIds": [12, 13],
 *     "type": 1,
 *     "reason": 1,
 *     "description": "买重了"
 *   }
 * </pre>
 *
 * <h3>★★ 这里没有 memberId、没有价格、没有商品 id</h3>
 *
 * <p>三条都是刻意的，而且理由各不相同：
 * <table border="1">
 *   <caption>不在请求体里的三个东西</caption>
 *   <tr><th>字段</th><th>为什么不在</th></tr>
 *   <tr><td>{@code memberId}</td>
 *       <td>身份来自 JWT。放进 DTO 会被 Spring MVC <b>自动绑定</b>，
 *           于是 {@code ?memberId=999} 就能给别人的订单申请售后 ——
 *           而且是静默生效的。见 {@code ShopOrderQueryDTO} 里那段完整论证</td></tr>
 *   <tr><td>金额 / {@code refundAmount}</td>
 *       <td>本项目所有金额一律服务端算。客户端提供金额 = 客户端决定退多少钱</td></tr>
 *   <tr><td>{@code orderId} / {@code productId}</td>
 *       <td>都能从 {@code orderItemIds} 反查出来。给两个可以互相推导的字段，
 *           就是给它们分岔的机会（而且分岔时该信哪个没有答案）</td></tr>
 * </table>
 *
 * <h3>★ 为什么是 {@code orderNo} + {@code orderItemIds}，两个都要？</h3>
 *
 * <p>光有明细 id 就够查到订单了，看起来 {@code orderNo} 是多余的。
 * 两个理由：
 * <ol>
 *   <li><b>{@code orderNo} 是安全边界的一部分。</b>查询用它 + 当前会员
 *       一起定位订单（{@code selectByOrderNoAndMember}）——
 *       明细 id 是自增的、可枚举的，不能单独承担「这单是不是你的」这个判断。</li>
 *   <li><b>它能拦住「明细 id 和订单不匹配」这个请求。</b>
 *       没有它，用户传一个<b>别人的</b>明细 id，我们只能靠「这条明细
 *       属于哪个订单」反查，然后才发现不是他的 —— 也是对的，但
 *       错误信息只能是一句含糊的「不存在」。带上 {@code orderNo} 之后，
 *       「这个明细不在你说的那笔订单里」是一句能说清楚的话。</li>
 * </ol>
 */
@Data
public class AfterSaleApplyDTO {

    /**
     * 要申请售后的订单号。
     *
     * <p>★ 它<b>不是</b>安全边界 —— 真正的边界是 JWT 里的 memberId
     * 加上 {@code OrderMapper.selectByOrderNoAndMember} 里的 {@code AND member_id}。
     * 订单号会出现在浏览器历史、分享链接、客服聊天里，
     * <b>它是一个标识符，不是一个密码。</b>
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 要申请售后的明细 id 列表。★ <b>可以有多条 = 整单退</b>。
     *
     * <p>{@code @NotEmpty} 是必需的，而且它挡的不只是一个「空请求」：
     * Service 会拿这个列表逐个建售后单，空列表意味着建 0 张单 ——
     * 一个「成功但什么都没做」的响应，用户会以为申请提交了。
     *
     * <p><b>★ 为什么是一个列表而不是单个 id？</b>
     * 因为「整单退」是真实需求，而它可以在这里被表达成「一次提交 N 条」。
     * 换成 N 次请求的话，用户勾了 3 行、第 2 次失败了，
     * 就会得到「退了 1 行、剩下的不知道退没退」的状态 ——
     * 而这里 Service 用一个事务保证全成或全败。
     */
    @NotEmpty(message = "至少要选择一件商品")
    private List<Long> orderItemIds;

    /**
     * 售后类型：1=仅退款 2=退货退款。取值见 {@code AfterSaleType}。
     *
     * <p>⚠️ {@code @NotNull} 只管「填了没有」，<b>不管「填的是不是 1 或 2」</b>——
     * 白名单校验在 Service 里（{@code AfterSaleType.isValid}）。
     * 判断标准是 {@code BusinessRules} 类注释里那条：
     * <b>DTO 只做协议层的合理性检查，业务上限/取值一律由 Service 判断。</b>
     * 放两个地方会导致「同一个用户动作得到两个不同的错误码」。
     */
    @NotNull(message = "请选择售后类型")
    private Integer type;

    /**
     * 申请原因码。取值见 {@code AfterSaleReason}。同上，白名单在 Service。
     */
    @NotNull(message = "请选择申请原因")
    private Integer reason;

    /**
     * 用户补充说明，可选。
     *
     * <p>{@code @Size(max = 255)} 对齐 {@code after_sale.description} 的列宽 ——
     * 不写它的话，超长会一路走到 INSERT，然后是一条
     * {@code Data too long for column 'description'} 的 SQL 错，
     * 用户看到的是「服务器错误」而不是「写太长了」。
     * 这和 {@code BusinessRules.MAX_SPEC_SCHEMA_LENGTH} 那段是同一个道理。
     */
    @Size(max = 255, message = "补充说明最多 255 个字")
    private String description;
}
