package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户端「我的订单」的查询条件。
 *
 * <h3>★ 为什么它只有一个 status，没有 memberId？</h3>
 *
 * <p>因为 {@code memberId} 来自 JWT，不是来自请求参数 ——
 * 参见 {@code OrderServiceImpl.currentMemberId} 的注释。
 * <b>「查谁的订单」这件事，用户说了不算。</b>
 *
 * <p>这个类里<b>没有</b> memberId 字段，所以 Service 想「按前端传的会员查」
 * 都写不出来（类型层面就做不到）。这是本项目的常规手法，
 * 和 {@code ShopProductQueryDTO} 里没有 status 字段是同一个理由：
 * <b>结构上做不到的事，比代码里记得去做的事，可靠得多。</b>
 *
 * <h3>★ 为什么它和管理端的 {@link OrderQueryDTO} 是两个类？</h3>
 *
 * <p>管理端要多两个查询条件（精确订单号、按会员模糊搜索），
 * 用户端一个都不该有：
 * <pre>
 *   OrderQueryDTO（管理端）：status / orderNo / memberKeyword / 分页
 *   ShopOrderQueryDTO（用户端）：status / 分页
 * </pre>
 * 如果图省事共用一个类，用户就能请求
 * {@code /api/shop/orders?memberKeyword=张} —— 虽然后端不会拿它去跨会员查，
 * 但「这个参数存在」本身就是个邀请，迟早会有人让它生效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ShopOrderQueryDTO extends PageQueryDTO {

    /**
     * 状态筛选，为 null 时不筛选（等于「全部」）。
     *
     * <p>取值见 {@code OrderStatus}：0=待付款 1=已付款 2=已发货 3=已完成 4=已取消。
     *
     * <p><b>★ 「全部」用 null 表示，不用 0 或 -1 这类哨兵值。</b>
     * 因为 0 是一个真实的状态（待付款）—— 用 0 表示「全部」的话，
     * 「只看待付款」这个功能就永远做不出来了。
     * 而且哨兵值是个魔法数字，会和真实取值撞车，
     * 撞车的症状是「筛选待付款时列出了全部订单」这种不报错的错。
     *
     * <p><b>★ 为什么不像 {@code ShopProductQueryDTO.sort} 那样做白名单校验？</b>
     *
     * <p>因为这两个字段危险程度完全不同：
     * <ul>
     *   <li>{@code sort} 最终会<b>变成一个 SQL 标识符</b>（{@code ORDER BY ${...}}），
     *       所以必须在 Java 里限死成几个安全值 —— 白名单是防注入的。</li>
     *   <li>{@code status} 是<b>一个值</b>，走的是 {@code #{}} 占位符。
     *       传个 99 进来，最坏后果是 {@code AND o.status = 99} 查不到任何行。</li>
     * </ul>
     * <b>非法值「不可能看到不该看的数据」时，静默返回空结果就好，不必报错。</b>
     * 这和 {@code ShopProductQueryDTO} 注释里那条判据是同一句：
     * 「这个非法输入，会不会让用户看到不该看的东西？」
     *
     * <p>（而且非整数会在这里先被 Spring MVC 的类型转换拦下，
     * 变成 HTTP 400 —— 见项目的异常处理约定，不是 200+500。）
     */
    private Integer status;
}
