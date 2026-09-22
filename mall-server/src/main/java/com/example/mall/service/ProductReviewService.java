package com.example.mall.service;

import com.example.mall.common.PageResult;
import com.example.mall.dto.PageQueryDTO;
import com.example.mall.dto.ReviewQueryDTO;
import com.example.mall.dto.ReviewSaveDTO;
import com.example.mall.vo.AdminReviewVO;
import com.example.mall.vo.ReviewSummaryVO;
import com.example.mall.vo.ReviewVO;

/**
 * 商品评价业务接口。
 *
 * <h3>★ 一个接口同时装着用户端和管理端的方法 —— 为什么不拆成两个？</h3>
 *
 * <p>因为 {@code OrderService} 已经立了先例：它既有用户端的
 * {@code pageMyOrders}（我的订单），又有管理端的 {@code pageForAdmin}。
 *
 * <p>拆开的理由通常是「这两边的规则会各走各的」。但评价这里恰好相反 ——
 * <b>用户端和管理端操作的是同一批数据、同一条业务规则</b>：
 * 「一条订单明细只能评一次」「确认收货才能评」「评价不能改不能删」，
 * 这些对谁提出的请求都一样。
 * 管理端多出来的只有「看得见全部」和「能删」两件事，它们各自就是一个方法。
 *
 * <p>★ 什么时候才该拆？<b>当两边的规则真的开始分岔的时候</b>，
 * 而不是「因为一边要登录、一边不要」。权限是<b>路径</b>的属性
 * （{@code WebMvcConfig} 的排除列表），不是业务规则的属性 ——
 * 拿它当拆分类的理由，就等于把两个不同层面的东西混在一起。
 *
 * <p>⚠️ 反过来，本接口里<b>有一个方法不该被 Controller 调</b>：
 * {@link #summary}。它是给 {@code ShopProductServiceImpl} 用的内部协作，
 * 没有对应的接口。见那个方法的注释。
 */
public interface ProductReviewService {

    /**
     * 发表一条评价（用户端）。
     *
     * <p>这是本轮唯一一个真正新的业务规则所在，也是整个功能的入口。
     * <b>规则不是「填得对不对」，而是「这个人有没有资格评这个商品」</b> ——
     * 而那个资格<b>不是从请求里读出来的，是从订单域推导出来的</b>：
     * 这条明细必须是当前登录会员的，且它所属订单必须已确认收货。
     *
     * @param dto 评价内容。⚠️ 里面<b>没有</b> productId / memberId，
     *            两个都由服务端推导 —— 客户端报什么一概不信
     * @return 新评价的 id
     * @throws com.example.mall.common.BusinessException
     *         1003（明细不存在 / 不是你的）、1002（还没确认收货）、
     *         400（已评价过 / 晒图地址不合法）
     */
    Long create(ReviewSaveDTO dto);

    /**
     * 分页查某个商品的评价（用户端，<b>匿名可调</b>）。
     *
     * <p>⚠️ 返回的 {@link ReviewVO} 里<b>只有昵称</b> —— 没有 username、
     * 没有 phone。这是个不需要登录就能调的公开接口，
     * 返回什么就等于向全世界公开什么。
     *
     * <p>★ <b>不校验商品是否存在</b>：商品不存在就返回一个空页。
     * 理由见实现类（这个接口的读者是详情页，而商品不存在时详情页自己会 404）。
     *
     * @param productId 商品 id
     * @param query     只有分页参数，所以直接用基类 {@code PageQueryDTO}
     */
    PageResult<ReviewVO> pageByProduct(Long productId, PageQueryDTO query);

    /**
     * 某个商品的评价聚合：平均分 + 星级分布 + 总条数。
     *
     * <p>⚠️ <b>这个方法没有对应的 HTTP 接口</b>，它只被
     * {@code ShopProductServiceImpl.detail} 调用，把结果 set 进
     * {@code ShopProductDetailVO.reviewSummary}。
     *
     * <p>★ 为什么不干脆给前端一个 {@code GET /api/shop/products/{id}/reviews/summary}？
     * 因为详情页<b>一定会</b>要这个数据（它就在页面顶部），
     * 拆成两个请求等于让前端为了渲染一个页面发两次请求，
     * 而其中一个的数据是另一个页面必然会用到的。
     * <b>能被一次请求拿全的、而且总是一起被用到的数据，就放在一次请求里。</b>
     * （对照：评价列表就是独立接口，因为它是分页的、会被单独翻页。）
     *
     * @return <b>永远非 null</b>（零评价时是一个各字段为 0 的对象）——
     *         它是聚合查询，不带 GROUP BY，永远返回一行
     */
    ReviewSummaryVO summary(Long productId);

    /**
     * 分页查全部会员的评价（管理端）。
     *
     * <p>★ 返回的 {@link AdminReviewVO} 里<b>可以</b>有 username ——
     * 判据是「看这个接口的人是谁」。详见那个类的注释。
     */
    PageResult<AdminReviewVO> pageForAdmin(ReviewQueryDTO query);

    /**
     * 删除一条评价（管理端）。
     *
     * <p>⚠️ 这是<b>物理删除</b>，而且它有一个必须说清楚的后果：
     * 删掉之后，这条订单明细的唯一索引槽位<b>就空出来了</b>，
     * 那个会员可以<b>重新评价</b>。这个语义是合理的
     * （被删掉的违规评价不该永久剥夺他重写的权利），
     * 但它是「删除」这个动作的一个真实后果，不是附带小事。
     *
     * @param id 评价 id
     * @throws com.example.mall.common.BusinessException 1003 评价不存在
     */
    void delete(Long id);
}
