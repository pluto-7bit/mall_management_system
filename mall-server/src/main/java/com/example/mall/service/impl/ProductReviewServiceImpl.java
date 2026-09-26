package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.OrderStatus;
import com.example.mall.common.PageResult;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.dto.PageQueryDTO;
import com.example.mall.dto.ReviewQueryDTO;
import com.example.mall.dto.ReviewSaveDTO;
import com.example.mall.entity.ProductReview;
import com.example.mall.entity.ProductReviewImage;
import com.example.mall.mapper.ProductReviewImageMapper;
import com.example.mall.mapper.ProductReviewMapper;
import com.example.mall.service.FileStorageService;
import com.example.mall.service.ProductReviewService;
import com.example.mall.vo.AdminReviewVO;
import com.example.mall.vo.ReviewEligibleVO;
import com.example.mall.vo.ReviewSummaryVO;
import com.example.mall.vo.ReviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品评价业务实现。
 *
 * <h3>★★★ 本类的核心是 {@link #create}，而它的核心是「资格」</h3>
 *
 * <p>{@code create} 一共四步，但真正需要想清楚的只有第二步：
 * <pre>
 *   1. 取当前会员 id（从 JWT，不从请求）
 *   2. ★ 查这条明细，判断【这个人有没有资格评它】
 *   3. 查重（给正常路径一句干净的错误信息）
 *   4. 落库（★ 并发那条路由数据库的唯一索引兜底）
 * </pre>
 *
 * <p>第 2 步特别的地方在于：<b>它的输入不在这个请求里</b>。
 * 前 11 个里程碑的每一次校验，判的都是「用户填的东西对不对」；
 * 这一次判的是「用户和另一个域（订单）的关系」——
 * 而那个关系是<b>推导</b>出来的，不是<b>提交</b>上来的。
 * 客户端只报一个 {@code orderItemId}，其余全靠服务端查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReviewServiceImpl implements ProductReviewService {

    private final ProductReviewMapper reviewMapper;
    private final ProductReviewImageMapper reviewImageMapper;

    /**
     * 用来校验晒图地址（{@code /uploads/} 前缀）。
     *
     * <p>★ 这条规则和商品图集共用一个定义 —— 里程碑 12 把它从
     * {@code ProductServiceImpl} 搬到了 {@code FileStorageService}。
     * 详见那个方法上的注释（<b>一条安全边界不能有两处定义</b>）。
     */
    private final FileStorageService fileStorageService;

    // ======================================================================
    //  写评价
    // ======================================================================

    /**
     * 发表一条评价。
     *
     * <h4>★★ 第 2 步：资格怎么判 —— 为什么一半在 SQL 里、一半在 Java 里</h4>
     *
     * <p>{@code selectOrderItemForReview} 的 SQL 里带了
     * {@code o.member_id = #{memberId}}，但<b>没有</b>带 {@code o.status = 3}。
     * 这不是随手写的，判据是<b>「判错了会不会变成越权」</b>：
     * <pre>
     *   member_id 在 SQL 里 → 判错 = 能评价别人的订单 = 【越权】。
     *                          所以它必须和查询绑死，永远不能靠 Java 的 if 兜。
     *                          收益：它【不可能】返回一条不属于你的明细。
     *   status 在 Java 里    → 判错 = 少一句友好提示 = 【不越权】。
     *                          收益：分得清「不是你的」和「还没确认收货」，
     *                          这两种拒绝给用户的话完全不同。
     * </pre>
     * ★ 「把条件尽量写进 SQL」这条规则只适用于<b>并发闸门</b> ——
     * 那里的错误信息可以很粗（正常路径根本撞不上，见 {@code markPaid} 那类条件更新）。
     * 而这里两条分支都是用户会正常走到的。
     *
     * <h4>⚠️ 第 2 步的三种拒绝用的是【不同】的错误码，这是刻意的</h4>
     * <pre>
     *   查不到        → 1003 NOT_FOUND，「订单明细不存在」
     *   这一行退过款   → 1013 ORDER_ITEM_REFUNDED，「这条商品已经退款，不能再评价」
     *   状态不是已完成 → 1002 ORDER_STATUS_INVALID，「确认收货后才能评价」
     * </pre>
     * ⚠️ 信息里说的是「明细不存在」，而<b>不是</b>「明细不存在或不属于你」——
     * 后者等于告诉一个攻击者「这条明细是存在的，只是不是你的」。
     * 这和 {@code OrderServiceImpl.requireOwnOrder} 是同一条纪律。
     *
     * <p>★★ <b>后两条的判断顺序也是承重的</b>（★ 里程碑 17 加入 1013 时确立）：
     * 先判「这一行退过款没有」，再判「订单走完流程没有」。
     * 反过来的症状很具体 —— 一张已发货、其中一件已退款的订单，
     * 用户点评价先看到「确认收货后才能评价」，去确认收货回来再被拒一次，
     * 说「这条商品已经退款」。<b>多走两步，而每一步都不算错。</b>
     * 最难查的就是这种「每句都是真话、合起来是误导」的错。
     *
     * <p>★ 层次上也该这样排：退款是<b>这一行</b>结束了，
     * 而「已完成」只是<b>订单</b>走完了流程 —— 行的层次在订单之下。
     *
     * <h4>★★★ 第 3 步 + 第 4 步：查重只是给句话，唯一索引才是闸门</h4>
     *
     * <p>先把第 3 步的「先查一次」说清楚：<b>它不是为了安全。</b>
     * 两个请求可以【同时】走过这一句「没评过」，然后一个插入成功、
     * 一个撞唯一索引。<b>Java 里的「先查后写」永远挡不住并发。</b>
     *
     * <p>那为什么还留着它？因为它能让<b>正常路径</b>给出一句
     * 干净的、不依赖异常的错误信息。靠 {@code catch} 也能给出同一句话，
     * 但那意味着每次重复提交都要抛一个异常、走一遍 Spring 的异常展开 ——
     * 而这件「用户点两次按钮」的事一点都不异常。
     *
     * <p>而下面那个 {@code catch (DuplicateKeyException)} <b>不是防御性编程</b>：
     * 它是<b>唯一</b>能接住并发那条路的地方，删掉它，并发下就会漏出一个 500。
     * 两条路给出<b>同一个码、同一句话</b>，所以调用方看不出区别 ——
     * 这正是我们要的：用户不需要知道自己是哪条路。
     *
     * <h4>★ 晒图为什么在最后一步、而且要先判空</h4>
     *
     * <p>因为 {@code product_review_image.review_id} 要用评价的 id，
     * 而那个 id 是 insert 之后才由数据库生成的 —— <b>顺序不能反</b>。
     *
     * <p>⚠️ 而 {@code if (urls.isEmpty()) return;} 是承重的：
     * {@code batchInsert} 的 {@code <foreach>} 遇到空集合会拼出
     * <b>一个没有 VALUES 的 INSERT —— SQL 语法错误，不是「插入 0 行」</b>。
     * 这个坑本项目已经踩过两次（里程碑 10 / 11），不是理论风险。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ReviewSaveDTO dto) {
        Long memberId = currentMemberId();

        // ★★ 第 2 步：资格。一次查询同时拿回「这条明细是什么」和「订单什么状态」。
        //
        //   ⚠️ memberId 是 SQL 的 WHERE 条件的一部分，不是返回值里的字段 ——
        //      所以这里【没有】「查出来一看不是自己的」这个分支，查不到就是查不到。
        ReviewEligibleVO item = reviewMapper.selectOrderItemForReview(dto.getOrderItemId(), memberId);
        if (item == null) {
            // 明细不存在 / 不属于当前会员 / 它指向的订单不存在 —— 三种情况在这里【故意】合并成一句话。
            // 分开说等于告诉攻击者「这条明细是存在的」。
            throw new BusinessException(ResultCode.NOT_FOUND, "订单明细不存在");
        }
        // ★★★ 里程碑 17：先判【这一行退过款没有】，再判【订单什么状态】。
        //
        //   顺序是承重的，不是随手写的。反过来的症状非常具体：
        //   一张「已发货、着一件已退款」的订单，用户点评价会看到
        //   「确认收货后才能评价」→ 他老老实实去确认收货 → 回来再得到一句
        //   「这条商品已经退款」。★ 多走两步，而每一步都不算错 ——
        //   最难查的就是这种「每句话都是真话、合起来是误导」的错。
        //
        //   ★ 而且这个顺序在语义上也更靠前：退款是【这一行】结束了，
         //     而「已完成」只是【订单】走完了流程。行的层次在订单之下，
         //     一行结束了就不该再看它所在订单有没有走完。
        //
        //   ★ 用 Boolean.TRUE.equals(...) 而不是直接拆箱，理由写在
         //     ReviewEligibleVO.refunded 的注释里：null 和 false 同样处理，
         //     拿不准时按「没退过款」放行（让用户能评价，比让他不能更安全）。
        if (Boolean.TRUE.equals(item.getRefunded())) {
            // 1013 是里程碑 17 为它专门开的码，不是复用 1002 ——
            // 判据是前端要做的事不一样：1002 要「弹提示 + 重查订单列表」，
            // 而这里订单状态完全正常，变的是【那一行】，
            // 前端该做的是把那一行标成「已退款」并永久去掉评价入口。
            // 完整的论证写在 ResultCode.ORDER_ITEM_REFUNDED 上。
            throw new BusinessException(ResultCode.ORDER_ITEM_REFUNDED, "这条商品已经退款，不能再评价");
        }
        if (item.getOrderStatus() != OrderStatus.COMPLETED) {
            // ★ 这里用的是 int 常量比较（不是 equals）：
            //   orderStatus 来自数据库的 NOT NULL 列，不会是 null。
            //   如果哪天它变成了包装类型，这一行会 NPE —— 那是好事，
            //   比静默地判错要好。
            //
            // 1002 是现成的码（「订单状态不允许该操作」），不需要新造 ——
            // 判据还是那一条：前端需不需要针对它做不同的动作。
            // 这里前端要做的和「订单状态不对」完全一样：弹一句提示 + 重查列表。
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID, "确认收货后才能评价");
        }

        // 第 3 步：查重。见 javadoc —— 它挡不住并发，它是给正常路径一句话的。
        if (reviewMapper.existsByOrderItemId(dto.getOrderItemId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "这个商品你已经评价过了");
        }

        // 第 4 步：落库。
        //
        // ★★★ productId 取自【第 2 步查出来的值】，memberId 取自 JWT ——
        //     两者都没有从 dto 里读，因为 dto 里根本没有这两个字段。
        //     这不是「顺便更安全」，这是这个功能能成立的前提：
        //     如果 productId 来自请求，用户就能「评价自己买过的 A、
        //     把内容挂到别人的 B 商品下」，而 order_item_id 上的唯一索引
        //     完全拦不住这件事（它约束的是明细，不是商品）。
        ProductReview review = new ProductReview();
        review.setOrderItemId(item.getOrderItemId());
        review.setProductId(item.getProductId());
        review.setMemberId(memberId);
        review.setRating(dto.getRating());
        review.setContent(dto.getContent());

        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // ★ 上面已经查过了，为什么还要 catch？
            //   因为两个请求可以【同时】通过上面那句「没评过」，
            //   然后一个成功、一个撞 uk_order_item。
            //   Java 里的「先查后写」永远挡不住并发 —— 唯一索引才是真正的闸门。
            //
            //   ⚠️ 这段 catch 不是「防御性编程」，它是【唯一】能接住并发那条路的地方。
            //      把它当成多余的删掉，并发下就会漏出一个 500。
            //
            //   ⚠️ 这里【不要】把 e 记成 error 日志：走这条路代表的是一件
            //      完全正常的事（用户连点了两次按钮），不是故障。
            //      记 error 会让日志里充满噪音，真正的故障被淹没。
            //      （对照 OrderServiceImpl 处理幂等键撞车时的做法。）
            throw new BusinessException(ResultCode.BAD_REQUEST, "这个商品你已经评价过了");
        }

        // ★★★ 插评价成功之后才有 id，才能插晒图 —— 顺序不能反。
        attachNewImages(review.getId(), dto.getImages());

        log.info("发表评价成功, reviewId={}, orderItemId={}, productId={}, 晒图 {} 张",
                review.getId(), review.getOrderItemId(), review.getProductId(),
                dto.getImages() == null ? 0 : dto.getImages().size());

        return review.getId();
    }

    // ======================================================================
    //  查询
    // ======================================================================

    /**
     * 分页查某个商品的评价（用户端，匿名可调）。
     *
     * <p>★ 走的是 {@code ShopOrderController.page} 那个现成的四步模板：
     * <pre>
     *   normalize() → count → total == 0 提前返回空页 → 查列表 → 装晒图
     * </pre>
     * <b>「先数再查、为 0 就早退」这一步不是优化，是省钱</b>：
     * 一件没人评价过的商品（库里 40 多件都是这样）只花一次 COUNT，
     * 而不是白查一次 LIMIT 查询。
     *
     * <h4>★ 为什么不校验商品是否存在</h4>
     *
     * <p>商品不存在就返回一个空页，不抛 1003。
     *
     * <p>因为<b>这个接口的读者是商品详情页</b>，而商品不存在时
     * 详情页自己会 404（{@code ShopProductServiceImpl.detail} 先抛），
     * 前端根本走不到这个请求。
     *
     * <p>★ <b>为一个走不到的分支多查一次 product 是白花钱</b>——
     * 而且白花的那次查询还落在「每个访客每次打开详情页」这条路上。
     * 一条规则如果只在「有人绕过前端直接调接口」时才有意义，
     * 那它买到的东西就得掂量一下：这里买到的是「一个更准确的错误码」，
     * 代价是每个正常用户多一次查询。
     *
     * <p>（对照 {@code create}：那里就必须查，因为那边「查不到」的结果是
     * <b>放行还是拒绝</b>；这边只是<b>显示一个空列表还是显示一个错误</b>。)
     */
    @Override
    public PageResult<ReviewVO> pageByProduct(Long productId, PageQueryDTO query) {
        query.normalize();

        long total = reviewMapper.countByProduct(productId);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<ReviewVO> list = reviewMapper.selectPageByProduct(productId, query);
        attachImages(list);

        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    /**
     * {@inheritDoc}
     *
     * <p>★ 实现就是一行 —— 因为「零评价时返回什么」这件事
     * 已经在 SQL 里解决了（不带 {@code GROUP BY} 的聚合永远返回一行，
     * 加上 {@code COALESCE} 让每个字段都是数字）。
     * <b>所以这里既没有判空、也没有「如果 total 为 0 就 new 一个」的分支。</b>
     *
     * <p>这正是 {@code ReviewSummaryVO} 那段注释想说的：
     * <b>形状由 SQL 决定</b>。如果当初那条 SQL 是「按星级 GROUP BY」，
     * 这里就会有一堆「5 星那一行不存在要补 0」的逻辑 ——
     * 而那些逻辑是纯粹被 SQL 的写法逼出来的。
     */
    @Override
    public ReviewSummaryVO summary(Long productId) {
        return reviewMapper.selectSummary(productId);
    }

    /**
     * 分页查全部会员的评价（管理端）。
     *
     * <p>结构和 {@link #pageByProduct} 完全一样，只是查询和 VO 换成了管理端的。
     * <b>这算重复代码吗？</b>不算 —— 两个方法各 6 行，而把它们合并成
     * 一个带 {@code boolean admin} 参数的方法，会让「到底查哪张表、
     * 给哪些字段」变成一个要看参数才知道的事，
     * 而这恰好是两种完全不同的安全问题（一个不能给 username，一个可以）。
     * <b>安全和展示的分岔，宁可多写 6 行。</b>
     */
    @Override
    public PageResult<AdminReviewVO> pageForAdmin(ReviewQueryDTO query) {
        query.normalize();

        long total = reviewMapper.countAdminQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<AdminReviewVO> list = reviewMapper.selectAdminPage(query);
        // ★ 同一个 attachImages —— AdminReviewVO 是 ReviewVO 的子类，
        //   所以 List<? extends ReviewVO> 装得下它。
        //   这正是当初让它继承而不是平铺的收益之一：装晒图的逻辑只有一份。
        attachImages(list);

        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    // ======================================================================
    //  删除（管理端）
    // ======================================================================

    /**
     * 删除一条评价。
     *
     * <h4>⚠️ 顺序：先删晒图，再删评价 —— 而且没有东西帮你守这个顺序</h4>
     *
     * <p>全库没有外键（10 张表一个都没有，理由见 {@code sql/mall.sql} 的全库约定）。
     * 顺序反了会留下一批 {@code product_review_image.review_id}
     * 指向不存在评价的孤儿行，<b>而且没有任何东西会报错</b> ——
     * 症状要等到某天有人去 join 这两张表时才会出现。
     *
     * <p>两道锁一起用：{@code @Transactional} 保证「要么都成、要么都不成」，
     * 而<b>写对顺序</b>保证中途失败时不会留下半截状态。
     * 事务不能替代顺序 —— 它俩解决的是不同的问题。
     *
     * <h4>★★ 物理删除有一个必须说清楚的后果</h4>
     *
     * <p>删掉这条评价之后，{@code order_item_id} 上那个唯一索引的
     * 槽位<b>就空出来了</b>，那个会员可以<b>重新评价</b>这条明细。
     *
     * <p>这个语义是合理的 —— 被删掉的（违规）评价不该永久剥夺他重写的权利。
     * 但它是「删除」这个动作的一个<b>真实后果</b>，不是附带小事：
     * 如果哪天有人想加「拉黑会员，不许再评价」的功能，
     * 他会发现「删了评价他就又能评了」—— 而那是从这条行为推出来的必然结果。
     * <b>测试脚本里有一条用例专门锁住它（删完能量新评 → 200）。</b>
     *
     * <h4>★ 为什么不判断「这条评价是不是真的删掉了」以外的东西</h4>
     *
     * <p>不看它属于哪个商品、哪个会员 —— 管理员就是在删别人的内容，
     * 加会员隔离反而是语义错误。安全边界是 {@code AdminAuthInterceptor}
     * 对 {@code /api/admin/**} 的一刀切拦截，
     * 和 {@code OrderAdminMapper.markShipped} 是同一个豁免理由。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // ★ 顺序：先删晒图，再删评价。理由见上面的 javadoc。
        //   ⚠️ 这一步【不判影响行数】：返回 0 表示「这条评价本来就没晒图」
        //      （晒图是可选的），是完全正常的情况，不是错误。
        reviewImageMapper.deleteByReviewId(id);

        int affected = reviewMapper.deleteById(id);

        // ★ 用影响行数判断，而不是先 select 一次再 delete ——
        //   和 ProductServiceImpl.delete 同一个写法：少一次查询，
        //   而且在高并发下更准确（「先查后删」两步之间记录可能已经被删了）。
        //
        // ⚠️ 这个分支会让整个事务回滚，包括上面那条「删晒图」——
        //    所以「评价不存在」时报错【不会】留下
        //    「晒图被删了但我们说评价不存在」这种半截状态。
        //    这正是把它们放进同一个事务的价值。
        if (affected == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "评价不存在或已被删除");
        }

        log.info("删除评价成功, id={}", id);
    }

    // ======================================================================
    //  私有辅助方法
    // ======================================================================

    /**
     * 取当前登录会员的 id。写法和理由同 {@code OrderServiceImpl.currentMemberId}。
     *
     * <p><b>★ 评价的 memberId 必须来自 JWT，绝不能来自请求参数</b> ——
     * 否则就等于「可以替别人写评价」，而且那条评价会永久留在
     * 别人的商品页上（评价不可改不可删）。
     */
    private Long currentMemberId() {
        return UserContext.require().id();
    }

    /**
     * 给「一页评价」装填晒图 —— <b>列表两条路（用户端 / 管理端）共用这一个方法</b>。
     *
     * <p>★ 参数类型是 {@code List<? extends ReviewVO>}，靠的是
     * {@code AdminReviewVO extends ReviewVO}。这就是当初让它继承而不是平铺的
     * 收益之一。<b>如果两个 VO 没有继承关系，这里就要写两遍</b> ——
     * 而「写两遍」正是漏改一处和 N+1 的温床。
     *
     * <p>★★ 一次查完再分组，<b>绝不在循环里查</b>（那就是教科书级的 N+1）：
     * <pre>
     *   一页 10 条评价 → 1 次查评价 + 1 次查晒图 = 2 次往返，
     *   而不是 1 + 10 = 11 次。
     * </pre>
     * 形状和 {@code OrderServiceImpl.attachItems} 完全一样。
     *
     * <p>⚠️ 第一行的判空是<b>承重</b>的：{@code selectByReviewIds} 用
     * {@code <foreach>} 拼 {@code IN (...)}，空集合会拼出一个光秃秃的
     * {@code IN ()} —— <b>SQL 语法错误，不是「返回 0 行」</b>。
     */
    private void attachImages(List<? extends ReviewVO> list) {
        if (list.isEmpty()) {
            return;
        }

        List<Long> reviewIds = list.stream()
                .map(ReviewVO::getId)
                .toList();

        // ★ 一次查回本页所有评价的晒图，然后在内存里按 reviewId 分组。
        //   mapper 那边 ORDER BY review_id, id 保证了「同一条的晒图相邻且组内有序」，
        //   所以 groupingBy 出来的每个 List 天然就是展示顺序 —— 不需要再排一次。
        Map<Long, List<String>> byReviewId = reviewImageMapper.selectByReviewIds(reviewIds)
                .stream()
                .collect(Collectors.groupingBy(
                        ProductReviewImage::getReviewId,
                        Collectors.mapping(ProductReviewImage::getUrl, Collectors.toList())));

        for (ReviewVO vo : list) {
            // ★ getOrDefault(..., List.of()) 这一层【不是防御性编程】：
            //   晒图是可选的，所以「评价在、晒图不在」是【常态】而不是例外。
            //   少了它就是一个 NPE —— 而且是十条评价里只有三条会触发的 NPE。
            //
            //   ★ 空列表而不是 null，这样前端可以直接写 review.images.length，
            //     不用先判空。见 ReviewVO.images 的注释。
            vo.setImages(byReviewId.getOrDefault(vo.getId(), List.of()));
        }
    }

    /**
     * 校验并插入一条新评价的晒图（只被 {@code create} 调用）。
     *
     * <p>★ 两件事按固定顺序做，顺序不能反：
     * <pre>
     *   1. requireUploadedImages → 校验地址是【本服务上传的】
     *   2. batchInsert           → 落库
     * </pre>
     * 先校验再写，是「校验必须在写之前」这条最简单的纪律 ——
     * 反过来的话，一批地址里前两个合法、第三个非法，
     * 数据库里就已经有两行了（靠 {@code @Transactional} 回滚也能兜住，
     * 但那是兜底，不是设计）。
     *
     * <p>⚠️ <b>{@code null} 和 {@code []} 在这里被归一化成同一个东西</b>
     * （都是「没晒图」）—— 和 {@code ProductSaveDTO.images} 里
     * 「null = 不动、[] = 清空」的语义<b>不同</b>。
     * 判据不是「字段名」，而是<b>「这个操作有没有『保持不变』这个可能」</b>：
     * {@code create} 是新建，没有「之前那几张图」可以保持不变，
     * 所以两者只能是同一个意思。详见 {@code ReviewSaveDTO.images} 的注释。
     *
     * <p>⚠️ 那个 {@code if (urls.isEmpty()) return;} 是承重的 ——
     * 空的 {@code VALUES} 是 SQL 语法错误。理由见类注释。
     */
    private void attachNewImages(Long reviewId, List<String> rawUrls) {
        // ★ 归一化放在最前面，后面的代码就只有一种形状要处理。
        List<String> urls = rawUrls == null ? List.of() : rawUrls;

        // 1) 地址必须是本服务上传的（/uploads/ 前缀 + 长度）。
        //    ★ 这个校验【不能】只写在 DTO 的注解上 —— 前缀白名单注解表达不了。
        fileStorageService.requireUploadedImages(urls);

        // 2) ★ 承重的判空：空的 <foreach> 会拼出没有 VALUES 的 INSERT。
        //    注意这个 return 在【校验之后】—— 位置是这段代码的全部要点：
        //    空数组也要走一遍校验（虽然循环体不会执行），
        //    但更重要的是以后加校验时不会漏掉这条路径。
        if (urls.isEmpty()) {
            return;
        }

        List<ProductReviewImage> rows = new ArrayList<>(urls.size());
        for (String url : urls) {
            ProductReviewImage row = new ProductReviewImage();
            row.setReviewId(reviewId);
            row.setUrl(url);
            // ★ 没有 sortNo —— 晒图没有顺序这个概念，展示顺序 = 插入顺序。
            //   这是和 ProductImage 的一处刻意对照，见 ProductReviewImage 的类注释。
            rows.add(row);
        }

        reviewImageMapper.batchInsert(reviewId, rows);
    }
}
