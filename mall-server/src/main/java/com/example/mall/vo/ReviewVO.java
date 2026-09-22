package com.example.mall.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户端商品详情页里的一条评价。
 *
 * <h3>★★★ 这个类最重要的一件事：它只有 {@code memberNickname}，没有 username、没有 phone</h3>
 *
 * <p>商品详情页的评价区是<b>任何人都能看到</b>的接口 ——
 * 不需要登录（它挂在 {@code /api/shop/products/**} 这条匿名可浏览的路径树下）。
 * 所以这个 VO 里出现的每一个字段，都等于<b>向全世界公开</b>。
 *
 * <p>这正好兑现了 {@link MemberInfoVO#phone} 那段 javadoc 在里程碑 10 写下的预言：
 * <blockquote>
 *   「等做到里程碑 12（商品评价）时，如果要显示评价者昵称，
 *     记得<b>只返回昵称，不要带上手机号</b>。」
 * </blockquote>
 * 那段注释列出的错误示范是「张三 138****8001 评价了……」——
 * 注意<b>打码也不够</b>：手机号打码之后仍然是一个人的标识符，
 * 而这里根本不需要标识任何人，只需要一个显示用的名字。
 *
 * <p><b>★ 判据不是「这个字段敏不敏感」，而是「看这个接口的人是谁」。</b>
 * 同一份会员数据：
 * <pre>
 *   用户端（本类，陌生人也能看）  → 只给 nickname
 *   管理端（{@link AdminReviewVO}，管理员已登录） → username 可以给
 * </pre>
 * 管理员在管理端的订单列表里本来就看得到 username，给他不算泄露。
 * <b>同一个字段，两个端一个给一个不给 —— 这就是两次判断，不是一次判断加一个例外。</b>
 *
 * <p>⚠️ 刻意<b>没有做昵称打码</b>（{@code 张*三}）。那需要一套「什么算敏感昵称」
 * 的规则，而现在的结论是：只要不给 phone / username，昵称本身就是一个
 * 用户自己选择公开的显示名。打码是另一个话题（本项目「不做的事」里列了）。
 *
 * <h3>★ 为什么没有 {@code productId} / {@code orderItemId}？</h3>
 *
 * <p>{@code productId} 是调用方自己传进来的（它就在路径里），返回它纯属复读。
 * {@code orderItemId} 更不能给 —— 那是<b>这条评价的身份</b>，
 * 暴露出去等于把「哪条订单明细是这个会员的」这条信息也一并说出去。
 *
 * <h3>⚠️ {@code memberNickname} 可能为 null</h3>
 *
 * <p>两个原因，都会真实发生：
 * <ol>
 *   <li>{@code member.nickname} 这一列本身就允许为空（注册时昵称是可选的）。</li>
 *   <li>查询用的是 {@code LEFT JOIN member}，会员记录被硬删了就是 null。</li>
 * </ol>
 * 所以前端必须写 {@code memberNickname || '匿名用户'} ——
 * <b>一个显示字段的兜底应该在展示层做，不该在服务端编一个假昵称。</b>
 */
@Data
public class ReviewVO {

    private Long id;

    /**
     * 评分：1~5 星。
     *
     * <p>★ 给的是<b>数字</b>，不是「五星好评」这种中文 ——
     * 理由和 {@link OrderVO#status} 完全一样：<b>码给前端（用于判断和渲染），
     * 中文交给展示层。</b>前端用 {@code el-rate} 直接渲染成星星。
     */
    private Integer rating;

    private String content;

    /**
     * 评价者昵称。★ <b>只有昵称</b>，理由见类注释。
     *
     * <p>⚠️ 可能为 null（昵称没填 / 会员被删）。前端用 {@code || '匿名用户'} 兜底。
     */
    private String memberNickname;

    /**
     * 晒图地址列表，按上传顺序排列。
     *
     * <p>⚠️ <b>它不由那条列表 SQL 查出来，是 Service 事后批量装填的</b>
     * （{@code ProductReviewServiceImpl.attachImages}）。
     * 和 {@link ShopProductDetailVO#images} 是同一种做法，理由也一样：
     * 一条查评价的 SQL 里塞 {@code <collection>} 就要把 {@code resultType}
     * 改成 {@code resultMap}，而 {@code count} 那条语句根本不需要它 ——
     * 分开查一次，代价是 1 条 SQL，收益是列表和 count 的形状完全不受影响。
     *
     * <p>★ 是<b>一次查完再分组</b>，不是循环里查 —— 后者就是 N+1。
     *
     * <p>⚠️ 没有晒图时是<b>空列表</b>，不是 null（Service 保证）。
     * 所以前端可以直接写 {@code review.images.length}。
     */
    private List<String> images;

    /**
     * 评价时间。
     *
     * <p>★ 类型是 {@code LocalDateTime}，和 {@link OrderVO#createTime} 一致 ——
     * 跨端时间格式由 {@code JacksonConfig} 统一成 {@code yyyy-MM-dd HH:mm:ss}。
     * <b>不要自己新引入第三种时间类型</b>，那等于多出第三个格式来源。
     */
    private LocalDateTime createTime;
}
