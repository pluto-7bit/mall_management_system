package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.PageResult;
import com.example.mall.common.ResultCode;
import com.example.mall.dto.ShopProductQueryDTO;
import com.example.mall.mapper.ProductImageMapper;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.service.ProductReviewService;
import com.example.mall.service.ShopProductService;
import com.example.mall.vo.ShopProductDetailVO;
import com.example.mall.vo.ShopProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户端商品浏览业务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopProductServiceImpl implements ShopProductService {

    private final ProductMapper productMapper;
    private final ProductImageMapper productImageMapper;

    /**
     * 只用来取某个商品的评价聚合（★ 里程碑 12 起）。
     *
     * <p>★ 注意这里注入的是 <b>Service 而不是 Mapper</b> —— 和
     * {@code ProductServiceImpl} 删评价时直接用 Mapper 的做法正好相反。
     * 区别在「这件事有没有业务规则」：
     * <pre>
     *   删商品下的评价  → 没有规则，就是「按 productId 全删」 → 用 Mapper
     *   取评价聚合      → 是「评价这个域对外提供的一个口径」，
     *                     将来口径变了（比如要排除被折叠的评价），
     *                     改的应该是 ProductReviewService 那一处 → 用 Service
     * </pre>
     * <b>判据不是「跨域调用该用 Service 还是 Mapper」，而是「这次调用有没有绕过规则」。</b>
     */
    private final ProductReviewService reviewService;

    /**
     * 分页浏览商品。
     *
     * <p>结构和 {@code ProductServiceImpl.page} 几乎一样。这是<b>可以接受的重复</b> ——
     * 两个方法的业务规则不同（一个能看全部、一个只能看上架），
     * 硬抽成一个带 boolean 参数的私有方法，反而会让「到底过不过滤」
     * 变成一个需要看参数才知道的事。
     *
     * <p>而且它们接下来会分岔：用户端马上要加「排序」，
     * 管理端则不打算支持（后台列表按 id 倒序看最新的就够了）。
     */
    @Override
    public PageResult<ShopProductVO> page(ShopProductQueryDTO query) {
        // ★ 这一步不能省。
        //
        //   normalize() 里做了两件事，两件都是安全相关的：
        //     1. 分页参数兜底 —— 否则前端传 pageSize=999999
        //        就能一次把整张商品表拉走（一种「数据爬取」的经典手法）
        //     2. sort 白名单 —— 非法值退化成默认排序
        //
        //   注意它是【修改传入对象】而不是返回一个新对象。
        //   这一点要留意：调用方传进来的 query 会被改写。
        //   在 Service 的入口处这么做是常见写法（参数进来就立刻规范化，
        //   后面的代码都可以假定它是干净的），
        //   但如果这个方法被别处复用，就要小心副作用。
        query.normalize();

        long total = productMapper.countShopByQuery(query);
        if (total == 0) {
            // 一条都没有就不必再发第二条 SQL。
            // 更妙的是 PageResult.empty 会保证 pageNum/pageSize 照常返回，
            // 前端的分页器不会因为少字段而报错
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<ShopProductVO> list = productMapper.selectShopPage(query);
        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    @Override
    public ShopProductDetailVO detail(Long id) {
        // 防御性判断：id 是从 URL 来的，虽然 Spring 会做类型转换
        // （传 /products/abc 会直接 400，走不到这里），
        // 但传 /products/-1 是能进来的。负数 id 查库必然为空，
        // 早一点返回能省一次数据库往返
        if (id == null || id < 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
        }

        // SQL 里已经带了 status = 1，所以「不存在」和「已下架」
        // 在这里都表现为 null，Service 也就无从区分 —— 这正是我们想要的。
        // 详见 ProductMapper.selectShopById 的注释
        ShopProductDetailVO product = productMapper.selectShopById(id);
        if (product == null) {
            // ★ 这里记日志但不区分原因。
            //
            //   注意日志级别用 info 而不是 warn：用户点了一个失效的商品链接
            //   是很正常的事（比如从收藏夹进来的），不是异常情况。
            //   用 warn 的话，日志里会充满这种噪音，真正的告警反而被淹没。
            //
            //   「什么该记 warn」的判断标准是：**运维需不需要半夜被叫起来处理**。
            //   商品不存在显然不需要。
            log.info("用户端查询商品详情失败（不存在或已下架）, id={}", id);
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
        }

        // ★ 里程碑 11：图集单独查一次装进来。
        //
        //   ⚠️ 为什么不在 selectShopById 那条 SQL 里加 <collection>？
        //      因为那条 SQL 【被购物车复用了】——
        //      CartServiceImpl.requireAvailableProduct 调它，
        //      而那边只读 stock（加购物车 / 改数量两个接口都在走）。
        //      给它加 <collection>，就是让每次加购物车都多查一次 product_image。
        //
        //      而且 resultType 本来就装不下 List<String>，要加就得整条
        //      statement 从 resultType 改成 resultMap —— 改动面还要扩大。
        //
        //   ★ 关键判断：**给一个 VO 加字段，代价不取决于这个字段本身，
        //     而取决于「读它的那条 SQL 还有谁在用」。**
        //     把加载放在这个方法里（只有详情页会走到），
        //     那条共用的查询就一个字都不用动 ——
        //     于是这个字段加得完全没有副作用。
        //
        //   商品没有图时返回空列表（不是 null），
        //   前端的展示逻辑有一条「空就退回 cover」的兜底，
        //   因为库里 40 多件商品的图集都是空的。
        product.setImages(productImageMapper.selectUrlsByProductId(id));

        // ★ 里程碑 12：评价聚合，同样单独查一次装进来。
        //
        //   ★★ 这一行和上面那一行是【同一个做法】，而第二次不需要重新论证 ——
        //      完整的理由在 ShopProductDetailVO.reviewSummary 的注释里
        //      （「给一个 VO 加字段，代价取决于读它的那条 SQL 还有谁在用」）。
        //
        //   ★ 它永远非 null（零评价时各字段为 0），因为 selectSummary 是
        //     不带 GROUP BY 的聚合查询，永远返回一行 —— 所以这里没有判空。
        //     前端因此可以直接写 product.reviewSummary.total。
        //
        //   ⚠️ 它【不能】和评价列表合成一次查询：
        //      这里是聚合（一行），那边是分页列表（一页 N 行），
        //      两者的形状和调用时机都不同 —— 列表还会被单独翻页。
        product.setReviewSummary(reviewService.summary(id));

        return product;
    }
}
