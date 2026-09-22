package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 修改收货地址的请求参数。
 *
 * <p>{@code POST /api/shop/addresses}（新增）和
 * {@code PUT /api/shop/addresses/{id}}（修改）共用这一个 DTO。
 *
 * <h3>★ 为什么新增和修改能共用一个 DTO？</h3>
 *
 * <p>因为这两个操作的<b>字段集合完全一样</b>：
 * 都是「收货人、电话、地区、详细地址、要不要设为默认」。
 * 修改时 id 在 URL 里（{@code /addresses/5}），不需要放进 body ——
 * <b>资源的身份应该由 URL 表达，不该在 body 里再说一遍</b>。
 *
 * <p>如果哪天新增和修改的字段集合分岔了（比如「新增时必须填验证码，
 * 修改时不用」），那就该拆成两个 DTO。判断标准是
 * <b>「两个操作的约束是不是一样」</b>，而不是「字段名看起来差不多」。
 *
 * <h3>★ 但"字段集合一样"有一个前提：修改也是【全量替换】</h3>
 *
 * <p>下面四个字符串字段都是 {@code @NotBlank}，也就是说
 * <b>调 {@code PUT /addresses/{id}} 时它们必须全传，少一个就是 400</b>。
 * 这是 {@code PUT} 这个词本身的意思：用我给的这份数据替换掉那条记录。
 *
 * <p>⚠️ 所以这个 DTO <b>不支持局部更新</b>——
 * 发 {@code {"phone": "138..."}} 想只改电话是不行的。
 * 这不是疏漏，而是因为<b>没有这个需求</b>：
 * 会调修改接口的只有地址表单页，而表单页手上有全部字段。
 * 唯一「只有 id、没有完整数据」的场景是列表页的「设为默认」，
 * 它走的是独立的 {@code PUT /addresses/{id}/default}。
 *
 * <p><b>★ 代价要说清楚：既然做了全量替换，前端就必须保证
 * 「表单里的字段确实是用户看到的那一份」。</b>
 * 如果表单只渲染了部分字段，提交时把没渲染的字段当成空值传上来，
 * 就会把用户原本的数据抹掉。所以全量替换的接口配的表单
 * 必须是完整的表单 —— <b>接口语义和页面形态是要配套的。</b>
 *
 * <h3>★ 这个 DTO 里没有、也不能有的字段</h3>
 *
 * <p>和 {@link MemberRegisterDTO} 同一个道理，这里刻意<b>没有</b>
 * {@code memberId}：
 * <ul>
 *   <li>如果 DTO 里有 {@code memberId}，那么「这个地址属于谁」
 *       就由客户端说了算 —— 攻击者可以给<b>别人</b>加地址，
 *       甚至给一个不存在的会员加地址</li>
 *   <li>归属必须由服务端从 {@code UserContext}（也就是 JWT）里取，
 *       这是本项目反复强调的一条：<b>「身份」和「金额」永远不由客户端提供</b></li>
 * </ul>
 * 同样没有 {@code id}（在 URL 里）和时间字段（数据库维护）。
 *
 * <p>注意这里<b>有</b> {@code isDefault}。它和 {@code memberId} 的区别是：
 * 「要不要设为默认」确实是<b>用户的意图</b>，服务端猜不出来，
 * 所以必须由客户端说。而「这个地址是谁的」服务端自己知道，
 * 就不允许客户端多嘴。
 * <b>判断一个字段该不该由客户端传，就问：这件事是不是只有客户端知道？</b>
 */
@Data
public class AddressSaveDTO {

    /**
     * 收货人姓名。
     *
     * <p>{@code @NotBlank} 而不是 {@code @NotNull} ——
     * 两者的区别是 {@code @NotBlank} 会连空串和纯空格一起拒绝。
     * 收货人是印在快递单上的，空串和纯空格都没有意义。
     *
     * <p><b>{@code @NotBlank}、{@code @NotNull}、{@code @NotEmpty}
     * 这三个注解很容易混，一句话记住：</b>
     * <pre>
     *   @NotNull   →  不能是 null                      （不管 "" 和 "   "）
     *   @NotEmpty  →  不能是 null、不能是空串           （但 "   " 可以过）
     *   @NotBlank  →  不能是 null、不能是空串、不能全是空格  （最严）
     * </pre>
     * 用在<b>字符串</b>字段上时，几乎总是该用 {@code @NotBlank}。
     * 这两个注解看起来像多余的形式主义，但「用户输入了一堆空格」是
     * 真实且高频的情况 —— 表单里敲空格比敲字母容易得多。
     */
    @NotBlank(message = "请填写收货人")
    @Size(max = 50, message = "收货人姓名不能超过 50 个字符")
    private String receiver;

    /**
     * 联系电话。
     *
     * <p>这里用 {@code @NotBlank} + {@code @Pattern}，
     * 而不是像注册那样允许为空 —— 因为快递员必须能打通电话，
     * <b>收货地址没有电话就是一条不可用的数据</b>，
     * 让它存进来只会在发货那天才暴露问题。
     *
     * <p>正则只接受 11 位手机号（{@code 1[3-9]} 开头）。
     * 真实商城通常还要支持固定电话（{@code 0755-12345678}），
     * 但本项目的定位是学习，收紧一点能让「格式校验」这件事讲得更清楚。
     * ⚠️ 这是个刻意的简化，真实项目要放宽。
     */
    @NotBlank(message = "请填写联系电话")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 所在地区（省市区）。
     *
     * <p>⚠️ 本项目没有做省市区三级联动，这里就是一个普通文本框。
     * 真实项目会用行政区划数据字典 + 三级联动选择器，
     * 那样能保证地区是「合法的、可统计的」结构数据。
     * 这里为了把精力集中在下单事务上，做了简化 —— 见 mall.sql 里
     * {@code member_address} 表的注释。
     *
     * <p>虽然简化了，但和 {@code detail} 保持<b>分成两个字段</b>这一点没省 ——
     * 因为「行政区划」和「门牌号」是两种性质的数据，
     * 混成一个字段以后要做地区统计就得正则解析自由文本。
     */
    @NotBlank(message = "请填写所在地区")
    @Size(max = 100, message = "所在地区不能超过 100 个字符")
    private String region;

    /**
     * 详细地址（街道、门牌号等）。
     */
    @NotBlank(message = "请填写详细地址")
    @Size(max = 255, message = "详细地址不能超过 255 个字符")
    private String detail;

    /**
     * 是否设为默认地址。
     *
     * <p>允许为 {@code null} —— 前端不传就表示「不动默认状态」。
     * 注意这里<b>不是</b> {@code boolean} 而是 {@code Boolean}：
     * 基本类型 {@code boolean} 的默认值是 {@code false}，
     * 于是「没传」和「传了 false」会被当成同一件事，
     * 而这两者的语义完全不同（没传 = 不改，false = 取消默认）。
     *
     * <p><b>需要表达「没有值」这个状态时，必须用包装类型。</b>
     * 这是 Java 里一个非常常见的坑：把一个可空的概念写成
     * {@code boolean}/{@code int}，就再也分不清「用户说了 0」
     * 和「用户什么都没说」了。
     */
    private Boolean isDefault;
}
