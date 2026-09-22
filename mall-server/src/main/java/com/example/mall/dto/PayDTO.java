package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 「支付订单」请求。
 *
 * <p>{@code POST /api/shop/orders/{orderNo}/pay}
 *
 * <h3>★ 这个 DTO 里只有一个字段，而且它【不是】必填的业务事实</h3>
 *
 * <p>订单号在 URL 路径里（{@code {orderNo}}），金额在数据库里，
 * 支付主体在 JWT 里。<b>客户端能提供的、而且服务端愿意采信的，只有这一个字段：
 * 「我想用哪种方式付款」。</b>
 *
 * <p>对比一下 {@code BuyNowDTO}：那个 DTO 里有 {@code productId} 和 {@code quantity}，
 * 因为服务端没有别的真相来源（没经过购物车）。而这里恰恰相反 ——
 * 服务端什么都知道，只有「用户偏好」这件事只存在于用户的点击里。
 *
 * <p><b>★ 提炼成一条规则：DTO 里该有哪些字段，
 * 取决于「这件事服务端自己知不知道」，而不是「这个接口看起来该不该有」。</b>
 * 凡是服务端知道的（金额、状态、归属），一律不接受客户端提供 ——
 * 一个不该存在的字段被人填了，就是漏洞。
 *
 * <h3>★ 为什么只是 {@code @NotBlank}，不写死 {@code @Pattern} 限定三个取值？</h3>
 *
 * <p>两种写法都能拦住非法值，区别在于<b>谁来维护那份合法值清单</b>：
 * <pre>
 *   写 @Pattern(regexp = "ALIPAY|WECHAT|BANK")  →  清单在这个 DTO 里
 *   在 Service 里查 PayMethod.isValid()          →  清单在 PayMethod 里（本项目选的）
 * </pre>
 *
 * <p>本项目选后者的理由：<b>合法值清单只该有一份。</b>
 * 将来加一种支付方式（比如「余额支付」），如果清单在 DTO 上，
 * 就要去改正则 —— 而正则里的 {@code |} 拼接是很容易改错、也很难一眼看懂的。
 * 更重要的是：<b>这个清单是业务规则，而 DTO 是协议层。</b>
 * 项目里已经反复确立过这条分工（见 {@code BuyNowDTO} 关于 99 和 999 的那段注释）：
 * <pre>
 *   DTO     → 协议层合理性（这个字段是不是空的、长不长得了）
 *   Service → 业务规则（这个取值合不合法、状态允不允许）
 * </pre>
 *
 * <p>⚠️ 这里有一处<b>刻意的重复</b>，必须说清楚：{@code @Size(max = 16)}
 * 和后端 {@code pay_method VARCHAR(16)} 是对应的。
 * 这个重复是<b>必要的</b>，而且方向是安全的：
 * DTO 只做「别让明显超长的垃圾进到 Service」这一层粗筛，
 * <b>真正判断「这个值合不合法」的仍然是 {@code PayMethod.isValid()}</b>。
 * 如果这里写成了枚举的完整清单，那就变成了「校验在两处各定义一遍」，
 * 那才是真正危险的重复。
 */
@Data
public class PayDTO {

    /**
     * 支付方式码，取值见 {@code PayMethod}：{@code ALIPAY} / {@code WECHAT} / {@code BANK}。
     *
     * <p><b>存码不存中文</b>（「支付宝」），理由见 {@code PayMethod} 的类注释 ——
     * 这个值是要写进数据库的，中文一变历史数据就对不上了。
     */
    @NotBlank(message = "请选择支付方式")
    @Size(max = 16, message = "支付方式不合法")
    private String payMethod;
}
