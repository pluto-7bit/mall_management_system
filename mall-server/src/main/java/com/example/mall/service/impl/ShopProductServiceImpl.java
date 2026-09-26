package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.PageResult;
import com.example.mall.common.ResultCode;
import com.example.mall.common.SpecGroup;
import com.example.mall.dto.ShopProductQueryDTO;
import com.example.mall.mapper.ProductImageMapper;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.service.CategoryService;
import com.example.mall.service.ProductReviewService;
import com.example.mall.service.ShopProductService;
import com.example.mall.service.ShopSkuService;
import com.example.mall.util.SpecJson;
import com.example.mall.vo.ShopProductDetailVO;
import com.example.mall.vo.ShopProductVO;
import com.example.mall.vo.SkuVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
     * ★ 里程碑 16 起：分类筛选要「含后代」。
     *
     * <p><b>和管理端的 {@code ProductServiceImpl} 调的是同一个方法，这是刻意的。</b>
     * 「点父分类要连同子分类的商品一起显示」是一条事实，
     * 如果只有一端含后代，同一件商品在两个端上的件数会对不上
     * （管理端 20 件、用户端 28 件），而运营会先怀疑后台统计错了，
     * 不会先怀疑这是两个不同的规则。
     *
     * <p>这也是为什么这里跨层调 Service 而不是让 Mapper 直接查：
     * 规则必须只有一个定义者，见 {@code CategoryService.selfAndDescendantIds}。
     */
    private final CategoryService categoryService;

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
     * 取一件商品的全部规格（★ 里程碑 15 阶段 3 起）。
     *
     * <p>★ 这里注入的是 <b>Service 而不是 Mapper</b>，判据和上面注入
     * {@code reviewService} 时是同一个：<b>「这次调用有没有绕过规则」。</b>
     * 「一个商品有哪些规格」这件事有自己的规则（不筛缺货的、
     * 按 spec_schema 的顺序渲染 specText），那些规则住在
     * {@code ShopSkuService} 里，绕过去直接用 Mapper 就等于绕过它们。
     */
    private final ShopSkuService shopSkuService;

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

        // ★★ 里程碑 16：把「点了一个分类」展开成「它自己 + 所有后代」。
        //
        //   和 ProductServiceImpl.page 里那一段是【同一个规则、同一份实现】
        //   （都调 CategoryService.selfAndDescendantIds），只是写在了两个类里。
        //   这份「重复」是两个端各有一套 DTO 这条约定带来的，不是抄错了 ——
        //   但要盯住它：test-category.py 里「按父分类筛选含后代商品」
        //   在两个端上【各断言一次】。如果哪天有人只改了其中一端，
        //   两条断言会一条绿一条红，而不是两条都绿。
        //
        //   ★ 这里也是【无条件】覆盖 categoryIds，理由同管理端：
        //     派生字段必须每次重算，否则前端能直接传 ?categoryIds=1,2
        //     把「含后代」这条规则绕过去。
        if (query.getCategoryId() != null) {
            List<Long> categoryIds = categoryService.selfAndDescendantIds(query.getCategoryId());
            // 空集合 → 空页。分类不存在时就是这种情况，
            // 而「不存在的分类筛出 0 件」是里程碑 16 之前就有的行为，原样保住。
            // 不短路的话 IN () 会变成 SQL 语法错误 → 500（见 ProductMapper.xml 里的说明）。
            if (categoryIds.isEmpty()) {
                return PageResult.empty(query.getPageNum(), query.getPageSize());
            }
            query.setCategoryIds(categoryIds);
        }

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

        // ★★ 里程碑 15 阶段 3：规格定义 + 全部规格。
        //
        //   ★ 这两块【没有】加进 selectShopById 那条 SQL ——
        //     这是本文件里第三次做同一个决定了，理由一字不差
        //     （前两次是 images 和 reviewSummary，完整的论证在
        //     ShopProductDetailVO.images 的注释里）：
        //
        //     那条 SQL 被 CartServiceImpl.requireAvailableProduct 复用
        //     （「加入购物车」和「改数量」两个接口都在走），而那边只需要
        //     price 和 stock。给它加上 skuAggregate 那个派生表，
        //     就等于【让每次加入购物车都把整个 product_sku 分组一遍】——
        //     而且 MySQL 的派生条件下推对带 GROUP BY 的派生表不生效，
        //     所以这不是「优化器会处理掉」的小事。
        //
        //   ★ 加字段的代价不取决于字段本身，而取决于读它的那条 SQL 还有谁在用。
        //
        //   ⚠️ 而且 skus 根本没法靠那条 SQL 带出来：它的类型是
        //     List<SkuVO>，数据库那边是 VARCHAR(500) 的 JSON 文本，
        //     MyBatis 没有现成的 TypeHandler 做这个转换 ——
        //     和 spec_schema 那一列撞的是同一堵墙（见 selectSpecSchema 的注释）。
        List<SkuVO> skus = shopSkuService.listByProductId(id);
        product.setSkus(skus);
        // ★ skuCount、minPrice、maxPrice 都是【从这个列表里算出来的】，
        //   不是再查一次数据库。
        //
        //   为什么不走 SQL 聚合（像列表接口那样）？
        //   因为要渲染规格选择器就必须先把整批 SKU 查出来，顺便算一下是零成本的，
        //   而再去数据库聚合一次就多一次往返。
        //
        //   ★★ 而且这样它们【永远不会分叉】：同一份数据、同一次计算。
        //      如果 minPrice 改回由 SQL 提供，就会出现「选择器上是 4 档、
        //      起售价却按 3 档算」这种只在某个商品上出现的诡异现象 ——
        //      而两个来源都各自「正确」，没有任何地方会报错。
        //
        //   ★ 里程碑 16 加的 maxPrice 【必须走同一条路】，理由正是上面那句：
        //     列表接口的 maxPrice 来自 SQL 的 MAX(price)，这一份来自 Java。
        //     两者不一致的现象是「首页写 ¥4999 ~ ¥6999，点进去变成 ¥4999 ~ ¥5999」
        //     —— 用户会怀疑自己看错了，而两个数都各自「算对了」。
        product.setSkuCount(skus.size());
        product.setMinPrice(minPriceOf(skus));
        product.setMaxPrice(maxPriceOf(skus));

        // ★ spec_schema 单独查一次（只有一列，主键命中）。
        //
        //   ⚠️ 这里和 shopSkuService.listByProductId 内部那次查重了 ——
        //     代价是一次主键命中的单列查询（微秒级），换来的是
        //     ShopSkuService 的接口不必为了「把已查到的 schema 传回去」
        //     而多出一个返回值。**这个交换是划算的，但它是刻意的，不是没想到。**
        List<SpecGroup> schema = SpecJson.schemaOf(productMapper.selectSpecSchema(id));
        product.setSpecSchema(schema);

        return product;
    }

    /**
     * 起售价 = 所有规格里最便宜的那个。
     *
     * <p>⚠️ 规格列表为空时返回 <b>null</b>，不是 0 ——
     * 空集合上的最小值没有答案，而 0 在价格这个语境里是一个
     * 强烈得多的陈述（「免费」）。前端拿到 null 会显示成「—」。
     *
     * <p>★ 用 {@code BigDecimal.compareTo} 而不是 {@code Math.min}：
     * 后者只对基本类型有效；也不能用 {@code <} 运算符比较两个 BigDecimal
     * （它们重写了 equals 但运算符比的是引用）。这类金额比较的坑
     * 在 {@code Product.price} 的注释里也提过。
     */
    private BigDecimal minPriceOf(List<SkuVO> skus) {
        BigDecimal min = null;
        for (SkuVO sku : skus) {
            if (sku.getPrice() == null) {
                continue;
            }
            if (min == null || sku.getPrice().compareTo(min) < 0) {
                min = sku.getPrice();
            }
        }
        return min;
    }

    /**
     * 最高价 = 所有规格里最贵的那个（★ 里程碑 16 新增）。
     *
     * <p>空列表同样返回 <b>null</b>，理由和 {@link #minPriceOf} 完全一致。
     *
     * <p>★ 为什么是<b>两个方法</b>而不是一个带 {@code boolean wantMax} 的：
     * 那样调用点会变成 {@code extremePriceOf(skus, true)}，
     * 读的人得回头数一下 true 到底是要大的还是要小的。
     * <b>窄方法的名字就是它的文档</b> —— 两段各自 8 行、各自说清自己是什么，
     * 比一个需要查参数含义的通用折叠便宜。
     *
     * <p>⚠️ 唯一需要注意的重复是「跳过 null 价格」这一条：两个方法里都有。
     * 它在这里是<b>同一条规则的第二份抄写</b>，所以将来要改（比如把 null 当 0）
     * 必须两处一起改 —— 上面那句「同一份数据、同一次计算」是它们的共同前提，
     * 改岔了就会出现 min 跳过 null 而 max 不跳这种荒唐情况。
     */
    private BigDecimal maxPriceOf(List<SkuVO> skus) {
        BigDecimal max = null;
        for (SkuVO sku : skus) {
            if (sku.getPrice() == null) {
                continue;
            }
            if (max == null || sku.getPrice().compareTo(max) > 0) {
                max = sku.getPrice();
            }
        }
        return max;
    }
}
