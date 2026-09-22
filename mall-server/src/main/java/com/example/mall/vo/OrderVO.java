package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单（返回给前端的结构）。
 *
 * <p>下单接口的返回值就是这个 —— 前端拿到之后直接跳转到订单结果页，
 * 不需要再发一次请求去查详情。
 *
 * <h3>★ 为什么这里【没有】statusText（状态中文）？</h3>
 *
 * <p>这是个刻意的决定，和 {@code OrderStatus.text()} 那条注释是同一件事：
 * <b>状态码给前端（用于判断），中文给日志（用于人看），两者不要混用。</b>
 *
 * <p>如果这里返回 {@code statusText: "待付款"}，前端会很自然地写：
 * <pre>
 *   if (order.statusText === '待付款') { 显示「去支付」按钮 }
 * </pre>
 * 然后后端某天把文案改成「待支付」，前端就<b>静默地失效</b>了 ——
 * 按钮消失，而且没有任何报错。
 * 这个坑里程碑 7 在 {@code mall-shop/src/views/Register.vue} 里踩过一次
 * （当时是用错误提示文字判断错误类型），教训是同一个。
 *
 * <p>所以：
 * <pre>
 *   后端给 code（status: 0）  → 前端用它判断「做什么」
 *   前端自己维护标签字典      → { 0: '待付款', 1: '已付款', ... } 用来显示
 * </pre>
 * <b>「界面上显示什么字」是展示层的职责，本来就该由前端决定。</b>
 * 后端一旦开始返回展示文案，就等于把展示逻辑拽到了服务端，
 * 结果两边都会有一份，而且会不一致。
 *
 * <h3>★ 为什么返回的是 receiverAddress 全文，而不是 addressId？</h3>
 *
 * <p>因为订单页要显示的就是<b>当时那个地址</b>。
 * 让前端拿 addressId 再去查一次地址簿是错的 ——
 * 那个地址可能已经被改了或删了，查回来的是<b>现在</b>的地址，
 * 不是这笔订单下单时的地址。
 *
 * <p>所以后端直接把下单时存的快照给前端。
 * <b>快照字段要在接口响应里出现，来源字段（addressId）不用给。</b>
 * 给了反而会诱导前端去用它查东西，把已经解决的问题重新引回来。
 */
@Data
public class OrderVO {

    /** 订单 id（自增主键） */
    private Long id;

    /** 订单号（给用户看、给客服查的那个） */
    private String orderNo;

    /**
     * 订单状态。取值见 {@code OrderStatus}：
     * 0=待付款 1=已付款 2=已发货 3=已完成 4=已取消
     *
     * <p><b>这是前端唯一该用来判断状态的字段。</b>
     */
    private Integer status;

    private BigDecimal totalAmount;

    // ---- 收货信息快照（下单时定下来的，不会随地址簿变化）----

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String remark;

    private LocalDateTime createTime;

    // ---- 支付 / 取消相关（里程碑 9 加的）----

    /** 支付时间，未支付时<b>这个字段会从 JSON 里消失</b>（原因见下） */
    private LocalDateTime payTime;

    /** 取消时间，未取消时同上 */
    private LocalDateTime cancelTime;

    /**
     * 支付方式码（ALIPAY / WECHAT / BANK），未支付时为 null。
     *
     * <p><b>给的是码不是中文</b>，理由和 {@link #status} 完全一样 ——
     * 前端拿码去查自己的展示字典。
     */
    private String payMethod;

    /**
     * 支付截止时刻（= 下单时间 + 支付时限），<b>只有待付款的订单才有值</b>。
     *
     * <p>收银台拿它显示「剩余 12:34」的倒计时。
     *
     * <h4>★ 为什么由服务端算好给前端，而不是让前端拿 createTime 自己加 30 分钟？</h4>
     *
     * <p>因为那样前端就要再抄一遍「30 分钟」这个数。
     * {@code mall-shop/src/utils/constants.js} 里已经立过一条规矩：
     * <b>从后端抄来的常量必须留一条「抄错了会怎样」的退路。</b>
     * 而这里的退路是现成的、而且非常干净：
     * <pre>
     *   倒计时【纯粹是显示】。能不能付款一律由服务端说了算 ——
     *   连「create_time &gt; deadline」这条判断都在 SQL 里
     *   （见 OrderMapper.markPaid）。
     * </pre>
     * 所以前端这个数字哪怕算错了一分钟，最坏后果也只是「屏幕上显示的时间不准」，
     * 不会出现「界面上说还能付、实际上付不了」或者反过来的情况。
     * <b>能这样设计的前提是：这个字段只影响显示，不影响判断。</b>
     * 如果一个抄来的常量会影响「做什么」，那就不能抄，得让服务端每次都告诉前端。
     *
     * <h4>★ 为什么已付款/已取消的订单没有这个字段？</h4>
     *
     * <p>因为它对那两个状态没有意义 —— 已经付过款的订单，
     * 「还能付多久」是个不存在的问题。
     *
     * <p>而 null 字段在 JSON 里会被<b>直接省略</b>（{@code JacksonConfig} 里配了
     * {@code default-property-inclusion: non_null}），
     * 所以前端拿到待付款订单时这个字段存在、拿到其他状态时它<b>不存在</b>。
     * 前端可以靠「有没有这个字段」来决定要不要显示倒计时区 ——
     * 但这只是顺带的好处，<b>判断状态的唯一依据仍然是 {@link #status}</b>。
     */
    private LocalDateTime payDeadline;

    // ---- 发货 / 完成相关（里程碑 10 加的）----

    /**
     * 发货时间，未发货时<b>这个字段会从 JSON 里消失</b>。
     *
     * <p>前端拿它显示「已发货 2026-09-22」。同样是 null 就省略，
     * 所以「有没有这个字段」恰好等价于「发了没发」——
     * 但这只是顺带的好处，<b>判断状态的唯一依据仍然是 {@link #status}</b>。
     */
    private LocalDateTime shipTime;

    /** 完成时间（买家确认收货），未确认时同上 */
    private LocalDateTime completeTime;

    /** 订单里的商品明细 */
    private List<OrderItemVO> items;
}
