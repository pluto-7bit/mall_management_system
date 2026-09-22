package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会员注册请求参数。
 *
 * <p><b>★ 这个类是用户端第一个「外部输入」入口，值得仔细看。</b>
 *
 * <p>管理端的所有接口都要先登录，能调用它们的人是自己人。
 * 而注册接口是<b>全网任何人都能调</b>的 —— 它是整个系统唯一一个
 * 「匿名用户可以往数据库里写数据」的地方。所以这里每个字段的约束
 * 都不是形式主义，而是真的在防脏数据。
 *
 * <p><b>参数白名单原则</b>：这个类里只有四个字段，而 {@code member} 表有八个列。
 * 缺的那四个（{@code id}、{@code status}、{@code createTime}、{@code updateTime}）
 * 是<b>刻意</b>不在里面的：
 * <ul>
 *   <li>没有 {@code id} —— 主键由数据库生成</li>
 *   <li>没有 {@code status} —— 新注册的会员状态必须是「正常」，
 *       绝对不能由客户端指定。如果这里放个 status 字段，
 *       攻击者就能注册一个 {@code status: 0}（禁用）的账号，
 *       或者更糟 —— 如果哪天加了 {@code status: 2} 表示「超级会员」，
 *       这个字段就成了提权入口</li>
 *   <li>没有时间字段 —— 由数据库默认值维护</li>
 * </ul>
 *
 * <p>这就是为什么「Entity 和 DTO 要分开」：<b>Entity 描述数据长什么样，
 * DTO 描述「这一次请求允许你改哪些东西」。</b>
 * 直接把 Entity 当请求参数接，等于把整张表的所有列都开放给了客户端，
 * 上面说的提权场景就会真实发生。
 */
@Data
public class MemberRegisterDTO {

    /**
     * 登录账号。
     *
     * <p><b>为什么要限制字符集，而不是「只要非空就行」？</b>
     * <pre>
     *   ^[a-zA-Z0-9_]{4,20}$
     * </pre>
     * <ul>
     *   <li><b>禁止空格和中文标点</b>：账号里带空格是最经典的支持灾难 ——
     *       用户输入 "abc " 注册成功，登录时输入 "abc" 却失败，
     *       而且他盯着屏幕看半天也看不出差别。<b>从源头禁掉比事后 trim 更彻底</b></li>
     *   <li><b>禁止控制字符和表情</b>：这些字符在日志、URL、
     *       甚至 SQL 客户端里都可能显示异常，排查问题时非常痛苦</li>
     *   <li><b>限定长度</b>：表定义是 {@code VARCHAR(50)}，
     *       但这里限 20 —— 不让用户填满字段宽度，
     *       给以后可能加的后缀（比如 {@code _deleted_20260921}）留余地</li>
     * </ul>
     *
     * <p>注意正则里<b>没有允许下划线以外的符号</b>，这是刻意的保守选择。
     * 宽松的字符集意味着以后做「@提及用户」「账号作为 URL 的一部分」
     * 这类功能时，要处理一堆转义问题。
     */
    @NotBlank(message = "请输入账号")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,20}$",
            message = "账号只能是 4-20 位的字母、数字或下划线")
    private String username;

    /**
     * 密码明文。
     *
     * <p>★ 这个字段从浏览器传过来时是<b>明文</b>的（HTTP body 里），
     * 到了 Service 层会被立刻 BCrypt 加密，明文不会落库、不会被记日志。
     *
     * <p>那传输过程中怎么办？靠 HTTPS。
     * <b>「密码加密存储」和「密码加密传输」是两件事</b>，
     * 前者是 BCrypt（数据库泄露时保护用户），
     * 后者是 TLS（链路上被窃听时保护用户）。
     * 有很多项目只做了前者就以为安全了 —— 那只能防「数据库被拖库」，
     * 防不了「同一个 WiFi 下有人抓包」。本项目本地跑 HTTP，
     * 部署时必须上 HTTPS。
     *
     * <p>下限 6 位是妥协：更严的规则（大小写+数字+符号）实际上
     * 会把人逼去用 {@code Password1!} 这种可预测的组合。
     * 现代建议是「长度优先」，等以后有兴趣可以了解 NIST 的密码指南。
     */
    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 100, message = "密码长度需在 6-100 位之间")
    private String password;

    /**
     * 昵称，选填。
     *
     * <p>{@code @Size} 在值为 {@code null} 时<b>不会</b>校验失败，
     * 所以「选填」不需要额外处理 —— 这正是 Bean Validation 的设计：
     * {@code @NotNull} 和 {@code @Size} 是两件事，
     * 前者管「有没有」，后者管「多长」。
     */
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /**
     * 手机号，选填。
     *
     * <p>★ 注意正则写成 {@code ^$|^1[3-9]\d{9}$} 而不是只写后半段。
     *
     * <p>原因是 {@code @Pattern} 的行为：<b>值为 null 时跳过校验，
     * 但值为空字符串 {@code ""} 时会老老实实拿去匹配。</b>
     * 前端表单里没填的输入框 v-model 绑定的是 {@code ""} 而不是 {@code null}，
     * 如果正则只写 {@code ^1[3-9]\d{9}$}，
     * 用户什么都没填反而会收到「手机号格式不正确」—— 一个纯粹的假报错。
     *
     * <p>所以这里显式允许空串，Service 层再把空串规范化成 {@code null} 入库
     * （见 {@code MemberAuthServiceImpl.register}）。
     * <b>「空串」和「NULL」在数据库里语义不同</b>，
     * 统一成 NULL 才不会出现「有些会员的手机号是 ''，有些是 NULL」这种
     * 让后续查询必须写 {@code WHERE phone != '' AND phone IS NOT NULL} 的烂摊子。
     */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
