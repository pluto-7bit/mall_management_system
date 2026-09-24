package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.ResultCode;
import com.example.mall.common.SpecGroup;
import com.example.mall.entity.Product;
import com.example.mall.entity.ProductSku;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.mapper.ProductSkuMapper;
import com.example.mall.service.ShopSkuService;
import com.example.mall.util.SpecJson;
import com.example.mall.vo.ShopProductVO;
import com.example.mall.vo.ShopSkuVO;
import com.example.mall.vo.SkuVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户端 SKU 读取业务实现。
 *
 * <h3>★ 这个类的核心手法：先查 SKU，再查商品，在 Java 里拼</h3>
 *
 * <p>两条 SQL，永远两条（不是 N+1），也不是一条 JOIN。为什么不 JOIN：
 * 因为那个 JOIN 的 {@code WHERE} 里会带上 {@code p.status = 1}，
 * 于是「用户端只能看下架商品之外的东西」这条<b>安全规则就有了第二处定义</b>。
 * 完整的理由写在 {@link ShopSkuVO} 的类注释里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopSkuServiceImpl implements ShopSkuService {

    private final ProductSkuMapper productSkuMapper;
    private final ProductMapper productMapper;

    @Override
    public ShopSkuVO getAvailable(Long skuId) {
        if (skuId == null || skuId < 1) {
            // 负数 id 查库必然为空，早一点返回能省一次数据库往返。
            // 和 ShopProductServiceImpl.detail 开头那个判断是同一个考虑。
            // （URL 里写 /skus/abc 走不到这里，Spring 的类型转换会先 400）
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
        }

        ProductSku sku = productSkuMapper.selectById(skuId);
        if (sku == null) {
            throw notFound(skuId, "这个 skuId 查不到");
        }

        // ★★ 这一步是【必须】的，而且它承担了两个职责：
        //
        //   ① 上下架过滤 —— product_sku 上没有上下架的概念，
        //      那件事在 product.status 上，而 selectShopById 的 SQL 里
        //      写死了 status = 1
        //   ② 商品上下文 —— 商品名 / 封面 / 分类名，见 ShopSkuVO 的类注释
        //
        //   ⚠️ 所以「SKU 存在但商品已下架」在这里和「SKU 不存在」
        //      得到同一个 1003、同一句话。这是刻意的，理由见
        //      ProductMapper.selectShopById 的注释：不把
        //      「这个 id 存在、只是下架了」这个有效信息泄露给遍历探测的人。
        ShopProductVO product = shopProduct(sku.getProductId());
        if (product == null) {
            throw notFound(skuId, "SKU 在，但它所属的商品已下架或被删了");
        }

        List<SpecGroup> schema =
                SpecJson.schemaOf(productMapper.selectSpecSchema(sku.getProductId()));
        return ShopSkuVO.of(sku, product, schema);
    }

    @Override
    public List<ShopSkuVO> listAvailable(List<Long> skuIds) {
        // 「购物车是空的」是个正常状态，不是错误 —— 返回空列表而不是抛异常。
        // ⚠️ 这个判断还有第二个作用：下面所有的 IN 查询都不能接空集合，
        //   空的 IN () 是 SQL 语法错误（见 ProductSkuMapper.deleteByIds 的注释）
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }

        List<ProductSku> rows = productSkuMapper.selectByIds(skuIds);
        if (rows.isEmpty()) {
            return List.of();
        }

        // ★ 去重：购物车里两行可能是同一件商品的两个规格。
        //   用 LinkedHashSet 而不是 Set.copyOf —— 后者遇到 null 元素会 NPE，
        //   而且不保证顺序（这里的顺序会决定后面 Map 的构造顺序）
        Set<Long> productIds = new LinkedHashSet<>();
        for (ProductSku row : rows) {
            productIds.add(row.getProductId());
        }

        // ② 查商品。★ 这一步就是「上下架过滤」——查不到商品的 SKU
        //    会在下面被丢掉，于是它们不出现在结果里。
        //    调用方靠差集识别失效行，见接口注释。
        Map<Long, ShopProductVO> products = productMapper.selectShopByIds(new ArrayList<>(productIds))
                .stream()
                .collect(Collectors.toMap(ShopProductVO::getId, Function.identity()));

        // ③ 查规格定义。它只影响 specText 里维度的【显示顺序】，
        //    但少了它会显示成「内存:128G / 颜色:黑」——
        //    一个管理员从来没有定义过的顺序，而且不同商品之间还不一致。
        //    ★ 一次查完，不是逐个商品查（那就是 N+1）。
        Map<Long, List<SpecGroup>> schemas = productMapper
                .selectSpecSchemasByProductIds(new ArrayList<>(productIds))
                .stream()
                .collect(Collectors.toMap(Product::getId,
                        p -> SpecJson.schemaOf(p.getSpecSchema())));

        List<ShopSkuVO> list = new ArrayList<>(rows.size());
        for (ProductSku row : rows) {
            ShopProductVO product = products.get(row.getProductId());
            if (product == null) {
                // ★ 正常的失败路径，不是异常：这件商品下架了或被删了。
                //   不抛错、也不记 warn —— 用户购物车里放着一件下架商品
                //   是很平常的事，记 warn 只会淹没真正的告警
                continue;
            }
            list.add(ShopSkuVO.of(row, product,
                    schemas.getOrDefault(row.getProductId(), List.of())));
        }
        return list;
    }

    @Override
    public List<SkuVO> listByProductId(Long productId) {
        if (productId == null || productId < 1) {
            return List.of();
        }
        List<ProductSku> rows = productSkuMapper.selectByProductId(productId);
        if (rows.isEmpty()) {
            return List.of();
        }
        // ★ 这里可以放心地逐商品查 schema：入参只有一个商品 id，
        //   「逐商品查」在这里就是「查一次」。N+1 的判据是
        //   「循环次数取决于数据量」，不是「调了几次查询」。
        List<SpecGroup> schema = SpecJson.schemaOf(productMapper.selectSpecSchema(productId));
        return SkuVO.ofAll(rows, schema);
    }

    // ======================================================================

    /**
     * 查一件【上架】商品，查不到返回 null。是「用户端能不能看见它」的唯一判据。
     *
     * <p>★ 注意这里没有另写一条「按 id 查上架商品」的 SQL，而是复用了
     * 购物车用的那条批量查询。理由和 {@code ProductSkuMapper.selectByIds}
     * 注释里说的是同一件事：<b>{@code status = 1} 这条安全规则只能有一处实现。</b>
     * 单条和批量在这里的区别只是 {@code IN} 里放几个值。
     */
    private ShopProductVO shopProduct(Long productId) {
        if (productId == null) {
            return null;
        }
        List<ShopProductVO> found = productMapper.selectShopByIds(Collections.singletonList(productId));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 统一的「不可买」出口。
     *
     * <p>★ 对外的消息只有一句，不给调用方任何区分「不存在」和「下架」的机会 ——
     * 具体是哪一种只写进日志。理由见 {@code ProductMapper.selectShopById} 的注释。
     *
     * <p>日志用 info 不用 warn：用户点了一个失效链接是很正常的事
     * （半年后从收藏夹点进来），不是异常。
     */
    private BusinessException notFound(Long skuId, String reason) {
        log.info("用户端查询 SKU 失败, skuId={}, 原因={}", skuId, reason);
        return new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
    }
}
