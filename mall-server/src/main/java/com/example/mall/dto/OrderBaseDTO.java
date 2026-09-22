package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 下单请求的公共字段。
 *
 * <p>被两个 DTO 继承：
 * <pre>
 *   {@link CartOrderDTO}  购物车结算 —— 商品和数量由服务端从 Redis 里读
 *   {@link BuyNowDTO}     立即购买   —— 商品和数量由前端指定
 * </pre>
 *
 * <h3>★ 什么情况下 DTO 才该用继承？</h3>
 *
 * <p>DTO 通常是不该有继承关系的 —— 每个接口的入参应该是一个独立的、
 * 一眼能看全的类。把它们搞成继承树，读代码时要来回跳，
 * 而且基类一改会影响所有子类，很危险。
 *
 * <p>这里之所以例外，是因为这三个字段满足两个条件：
 * <ol>
 *   <li><b>语义完全相同</b>：两个接口要的「收货地址」「备注」「幂等键」
 *       就是同一个东西，不是碰巧同名</li>
 *   <li><b>约束完全相同</b>：校验规则一字不差。将来如果「立即购买」
 *       要求必填备注而「购物车结算」不用，那就该把这个字段从基类挪下去 ——
 *       <b>约束一分岔，共用的理由就没了。</b></li>
 * </ol>
 *
 * <p>反过来，两个 DTO 的<b>差异部分</b>（商品怎么来、数量谁说了算）
 * 各留在自己类里，这部分才是理解这两个接口的关键。
 *
 * <h3>★ 这里没有、也不能有的字段：金额</h3>
 *
 * <p>注意这三个字段里<b>没有 {@code totalAmount}</b>，也不会有
 * {@code price}、{@code subtotal}。
 *
 * <p>这是一个必须说清楚的规矩：<b>订单金额永远由服务端算。</b>
 * 如果 DTO 里有一个 {@code totalAmount} 字段，那么「这单多少钱」
 * 就成了客户端说了算 —— 用户把 100 改成 1 就能一块钱买走。
 *
 * <p>所以下单请求里只描述<b>「买什么、买几件、寄到哪」</b>，
 * 至于「多少钱」，服务端自己去查商品现价、自己乘、自己加。
 *
 * <p><b>「身份」和「金额」这两类信息，永远不由客户端提供。</b>
 * 这是本项目的第二条铁律（第一条见 {@code MemberRegisterDTO} 里对 memberId 的讨论）。
 */
@Data
public class OrderBaseDTO {

    /**
     * 收货地址 id。
     *
     * <p>{@code @NotNull} 而不是 {@code @NotBlank} ——
     * 这是数字字段，不是字符串。{@code @NotBlank} 只能用在 CharSequence 上。
     *
     * <p>⚠️ 注意这个 id 是<b>客户端能随便填的</b>。所以服务端拿到它之后
     * 必须用 {@code selectByIdAndMember(id, 当前会员id)} 去查 ——
     * 只按 id 查的话，会员 A 下单时填一个 B 的地址 id，
     * 就会把货寄到 B 家去（而且 B 的姓名电话会出现在 A 的订单里）。
     * 这是比「改别人的地址」更直接的信息泄漏。见 {@code OrderServiceImpl}。
     */
    @NotNull(message = "请选择收货地址")
    private Long addressId;

    /**
     * 订单备注。可以为空。
     *
     * <p>约束和数据库列 {@code VARCHAR(255)} 对齐。
     * <b>DTO 的长度上限应该和数据库列宽一致</b> ——
     * 不一致的话，前端能通过校验、数据库却插不进去，
     * 用户会看到一个 500 级的系统错误，而不是「备注太长了」。
     */
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;

    /**
     * 幂等键，防重复提交。
     *
     * <p><b>★ 前端要在「进入结算页时」生成它，而不是「点提交时」。</b>
     * 这是这个字段能起作用的前提：
     * <pre>
     *   进结算页   → 生成 key = "a1b2c3"
     *   用户点提交 → 带 key = "a1b2c3"  → 服务端创建订单
     *   网络超时   → 前端重试，还是 key = "a1b2c3" → 服务端发现这个 key
     *                已经建过单了，直接返回那一单，不会建第二单 ✅
     *
     *   如果改成「点提交时生成」：
     *   第一次点   → key = "a1b2c3" → 建单
     *   重试再点   → key = "x9y8z7" → 服务端一看是新 key，再建一单 ❌
     * </pre>
     * <b>幂等键标识的是「这一次下单意图」，不是「这一次网络请求」。</b>
     * 把它的生命周期和请求绑在一起，它就完全失效了。
     *
     * <p>约束 {@code @Size(max = 64)} 和数据库列对齐。
     * 加 {@code @Pattern} 是为了让存进库的值是可预期的字符集 ——
     * 这个值将来可能要出现在日志、URL、甚至客服系统里，
     * 放开任意的 Unicode 只会带来麻烦。
     */
    @NotBlank(message = "缺少幂等键")
    @Size(max = 64, message = "幂等键过长")
    @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$",
            message = "幂等键格式不合法（只能由字母、数字、下划线、连字符组成，长度 8~64）")
    private String idempotencyKey;
}
