package com.example.mall.mapper;

import com.example.mall.dto.PageQueryDTO;
import com.example.mall.dto.ReviewQueryDTO;
import com.example.mall.entity.ProductReview;
import com.example.mall.vo.AdminReviewVO;
import com.example.mall.vo.ReviewEligibleVO;
import com.example.mall.vo.ReviewSummaryVO;
import com.example.mall.vo.ReviewVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品评价的数据访问接口。
 *
 * <h3>★ 它同时装着用户端和管理端的查询 —— 为什么不拆成 ReviewMapper / ReviewAdminMapper？</h3>
 *
 * <p>因为 {@code OrderAdminMapper} 拆出去的理由在这里<b>不成立</b>。
 * 那个文件的类注释说得很清楚：它单独成文件是为了<b>把「绕过会员隔离」这个例外隔离成文件边界</b>，
 * 因为 {@code OrderMapper} 的类注释是一份契约 ——
 * 「这里的查询方法全部带着 memberId 条件」。
 *
 * <p>{@link ProductReviewMapper} <b>没有这样一份契约</b>：
 * <pre>
 *   用户端评价列表  selectPageByProduct → 公开数据，【本来】就不按会员隔离
 *   管理端评价列表  selectAdminPage    → 同上，评价从来不是「私有资源」
 * </pre>
 * 评价的内容是<b>给所有人看的</b>，写它的动作才属于某个会员 ——
 * 而那个动作（{@code insert}）就是一条普通的带 memberId 的写，
 * 它不需要「隔离成文件」。
 *
 * <p>★ 所以判据是：<b>拆文件是为了保护一份会被说成假话的契约。</b>
 * 没有那份契约，拆开换来的只是「多一个文件要找」。
 * （这也提醒一件事：{@code OrderAdminMapper} 的注释是一份<b>很好的</b>论证，
 * 但「照抄上一个例外的做法」恰恰是它自己警告过的事 ——
 * 换个场景，理由要重新问一遍。）
 *
 * <h3>⚠️ 一条仍然有效的纪律</h3>
 *
 * <p>和 {@code OrderItemMapper} 一样：<b>不要直接从 Controller 调这个接口。</b>
 * 不是因为安全，是因为「谁有资格评价」这条规则定义在 Service 里
 * （{@code ProductReviewServiceImpl.create}）——
 * 直接调 {@code insert} 就绕过了资格判断，而那条判断是本轮全部的价值所在。
 * <b>规则只该有一个定义处。</b>
 */
public interface ProductReviewMapper {

    /**
     * 插入一条评价。
     *
     * <p>★ XML 里配了 {@code useGeneratedKeys}，插入成功后自增主键会被写回
     * {@code review.id}。晒图（{@code product_review_image}）要靠这个 id 才能插 ——
     * 所以<b>插评价和插晒图的顺序不能反</b>。
     *
     * <p>⚠️ <b>这个方法会抛 {@code DuplicateKeyException}。</b>
     * {@code order_item_id} 上有唯一索引，所以「这条明细已经评过了」在数据库层面
     * 是一条会失败的 INSERT。调用方<b>必须</b> catch 它 ——
     * 那不是防御性编程，那是<b>唯一</b>能接住并发那条路的地方。
     * 详见 {@code ProductReviewServiceImpl.create}。
     *
     * @param review 评价实体。{@code id} / {@code createTime} 由数据库生成，
     *               调用方不用填（填了也会被覆盖）
     * @return 影响行数，正常为 1
     */
    int insert(ProductReview review);

    /**
     * 这条订单明细是不是已经有评价了。
     *
     * <p><b>★ 这个方法的返回值【不能】当安全依据用。</b>
     * 它挡不住并发：两个请求可以同时查到 false，然后一起去插，
     * 一个成功、一个撞唯一索引。真正的闸门是数据库上的
     * {@code uk_order_item}，这个方法存在的意义只是
     * <b>让正常路径给出一句干净的、不依赖异常的错误信息</b>。
     *
     * <p>⚠️ <b>为什么返回 boolean，而 {@code MemberMapper.countByUsername} 返回 long？</b>
     * 那个方法的注释说「保持 Mapper 层『数据库返回什么就映射成什么』，
     * 判断语义留给 Service」—— 那条理由这里是<b>不适用</b>的：
     * 它讲的是「{@code > 1} 说明数据出了问题」这种<b>只有 Service 才懂的语义</b>，
     * 而「有没有」只有一个意思，不存在第二种解读。
     * 方法名已经把这个唯一的意思说出来了，再让每个调用方写一遍
     * {@code countByOrderItemId(...) > 0} 只是把同一句话抄了两遍。
     *
     * <p>（SQL 写成 {@code SELECT COUNT(*) > 0}，让数据库直接给出布尔值，
     * 而不是把 0 交给 JDBC 去强转 —— 少一层「碰巧能用」。）
     *
     * @return 已有评价返回 true
     */
    boolean existsByOrderItemId(@Param("orderItemId") Long orderItemId);

    /**
     * 查一条订单明细，判断它<b>能不能被这个会员评价</b>。
     *
     * <p>这是本轮唯一一个真正的新查询，也是整个功能的入口。
     * 一次同时拿到「这条明细是什么商品」和「它属于的订单是什么状态」。
     *
     * <p>★★ <b>注意 {@code memberId} 是 SQL 的 {@code WHERE} 条件的一部分，
     * 不是返回值里的一个字段。</b>它在这里的角色是<b>授权边界</b>：
     * 判错就等于「能评价别人的订单」，是越权。
     * 所以它必须绑死在查询里，永远不能靠 Java 里的 if 兜 ——
     * 这也意味着<b>这个方法不可能有「查出来一看不是自己的」这种分支</b>，
     * 查不到就是查不到。
     *
     * <p>⚠️ {@code JOIN orders}（INNER）而不是 {@code LEFT JOIN}，
     * 和 {@code OrderAdminMapper} 的选择相反，理由见 {@link ReviewEligibleVO} 的类注释：
     * <b>这一行是用来授权的，不是用来展示的</b>，
     * 所以「订单不存在」时我们宁可查不到、也不放行。
     *
     * <p>⚠️ <b>不要从 Controller 直接调它。</b>
     * 「查得到 = 能评价」这个推论是错的 —— 还要判订单状态，
     * 而那一步在 Service 里。这个方法的交付物是<b>事实</b>，不是<b>结论</b>。
     *
     * @param orderItemId 客户端提交的订单明细 id
     * @param memberId    当前登录会员 id，取自 JWT，
     *                    <b>永远不从请求参数里取</b>
     * @return 查不到（明细不存在 / 不属于该会员 / 订单不存在）时返回 <b>null</b>
     */
    ReviewEligibleVO selectOrderItemForReview(@Param("orderItemId") Long orderItemId,
                                              @Param("memberId") Long memberId);

    /**
     * 某个商品的评价聚合：总条数、平均分、五档分布。
     *
     * <p>★★ 这是个<b>不带 {@code GROUP BY} 的聚合查询</b>，所以它
     * <b>永远返回且只返回一行</b> —— 即使这个商品一条评价都没有。
     * 于是返回类型不是 {@code List}，Service 里也不需要判空。
     * 详见 {@link ReviewSummaryVO} 的类注释（那里还讲了
     * {@code COUNT} / {@code AVG} / {@code SUM} 在零行上返回值不一样这件事）。
     *
     * @param productId 商品 id
     * @return 永远非 null。零评价时各字段都是 0
     */
    ReviewSummaryVO selectSummary(@Param("productId") Long productId);

    /**
     * 分页查某个商品的评价（用户端详情页用）。
     *
     * <p>★ 返回 {@code ReviewVO} —— <b>只有昵称，没有 username、没有 phone</b>。
     * 这是个匿名可调的公开接口，「返回什么就等于向全世界公开什么」。
     * 详见 {@link ReviewVO} 的类注释。
     *
     * <p>⚠️ 它<b>不查晒图</b>。晒图由 Service 用一次
     * {@code ProductReviewImageMapper.selectByReviewIds} 批量装上，
     * 避免一页 10 条查 10 次（N+1）。
     *
     * <p>★ 这里<b>不校验商品是否存在</b>，也不需要 —— 商品不存在就返回一个空页。
     * 理由见 {@code ProductReviewServiceImpl.pageByProduct}：
     * 这个接口的读者是商品详情页，而商品不存在时详情页自己会 404，
     * 根本走不到这个请求。<b>为一个走不到的分支多查一次 product 是白花钱。</b>
     *
     * @param productId 商品 id
     * @param query     分页参数，用基类 {@code PageQueryDTO}
     *                  （用户端列表只有分页，没有别的筛选条件）
     * @return 不会为 null（没有数据时是空列表）
     */
    List<ReviewVO> selectPageByProduct(@Param("productId") Long productId,
                                       @Param("query") PageQueryDTO query);

    /**
     * 数某个商品有多少条评价（条件必须和 {@link #selectPageByProduct} 完全一致）。
     *
     * <p>★ 两者共用 XML 里同一个 {@code <sql>} 片段，所以「筛选条件分叉」
     * 这件事在结构上就不可能发生。分叉的症状是
     * 「页面显示 5 条、分页器说总共 3 条」，极难查。
     *
     * @return 条数，没有时返回 0
     */
    long countByProduct(@Param("productId") Long productId);

    /**
     * 分页查全部会员的评价（管理端）。
     *
     * <p>★ 返回 {@code AdminReviewVO}，比用户端那个多了商品名和会员登录名。
     * <b>username 这里可以给，用户端不给</b> —— 判据是「看这个接口的人是谁」，
     * 详见 {@link AdminReviewVO} 的类注释。
     *
     * <p>⚠️ 两个 {@code LEFT JOIN}（product / member）都是刻意的：
     * <b>商品或会员被硬删了，评价不该从管理端列表里消失。</b>
     * 用 INNER JOIN 的话列表会少行、而 {@code count} 不会少（count 不 join），
     * 症状是「总数说 10 条、翻到底只有 9 条」。
     *
     * <p>⚠️ <b>参数没有 {@code @Param}</b>，和 {@code OrderAdminMapper.selectAdminPage}
     * 逐字相同 —— 单个参数对象时 MyBatis 允许省掉它，XML 里直接写
     * {@code #{productKeyword}} 而不是 {@code #{query.productKeyword}}。
     * 两处保持一致比「哪个更显式」重要。
     *
     * <p>⚠️ 它<b>不查晒图</b>，理由同 {@link #selectPageByProduct}。
     *
     * @return 不会为 null（没有数据时是空列表）
     */
    List<AdminReviewVO> selectAdminPage(ReviewQueryDTO query);

    /**
     * 数一共有多少条评价（条件和 {@link #selectAdminPage} 完全一致）。
     *
     * <p>⚠️ 它<b>不 join 任何表</b>，即使 {@link #selectAdminPage} 两个表都 join 了。
     * 商品/会员筛选写成子查询（{@code r.member_id IN (SELECT id FROM member WHERE ...)}）
     * 而不是 {@code m.nickname LIKE ...}，就是为了让同一份条件片段
     * 能同时用在「有 join 的列表查询」和「没 join 的 count 查询」里。
     * 这是 {@code OrderAdminMapper.xml} 已经确立的范式。
     *
     * @return 条数，没有时返回 0
     */
    long countAdminQuery(ReviewQueryDTO query);

    /**
     * 按 id 删一条评价（管理端删除）。
     *
     * <p>⚠️⚠️ <b>调用方必须先删晒图行</b>（{@code ProductReviewImageMapper.deleteByReviewId}），
     * 再调这个方法。全库没有外键，顺序反了会留下一堆
     * {@code product_review_image} 里 {@code review_id} 指向不存在评价的孤儿行，
     * 而<b>没有任何东西会报错</b>。
     *
     * <p>⚠️ <b>这是物理删除，它有一个必须说清楚的后果</b>：
     * 删掉之后，{@code order_item_id} 上那个唯一索引的槽位<b>就空出来了</b>，
     * 那个会员可以<b>重新评价</b>这条明细。
     *
     * <p>这个语义是合理的（被删掉的违规评价不该永久剥夺他重写的权利），
     * 但它是「删除」这个动作的一个真实后果，不是附带的小事 ——
     * 测试脚本里有一条用例专门锁住它。详见 {@code ProductReviewServiceImpl.delete}。
     *
     * @return 影响行数。<b>1 = 删掉了；0 = 这条评价不存在</b>。
     *         返回 0 时调用方要报「评价不存在」而不是当成成功
     */
    int deleteById(@Param("id") Long id);

    /**
     * 删掉某个商品的全部评价 —— 服务于「删商品」。
     *
     * <p>★ 它是「删商品」四级级联里的第二级：
     * <pre>
     *   晒图 → 评价 → 图集 → 商品
     * </pre>
     * 顺序一层都不能反，而这条链上<b>没有任何数据库约束帮你守</b>（全库没有外键）。
     * 见 {@code ProductServiceImpl.delete}。
     *
     * <p>⚠️ 影响 0 行<b>不是错误</b> ——「这个商品本来就没人评价过」是常态。
     * 所以返回值不参与任何判断。
     *
     * @return 影响行数
     */
    int deleteByProductId(@Param("productId") Long productId);
}
