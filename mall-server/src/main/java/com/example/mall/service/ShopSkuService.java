package com.example.mall.service;

import com.example.mall.vo.ShopSkuVO;
import com.example.mall.vo.SkuVO;

import java.util.List;

/**
 * 用户端的 SKU 读取业务（★ 里程碑 15 阶段 3 新增）。
 *
 * <h3>★ 为什么单独开一个 Service，而不是挂在 {@code ShopProductService} 上？</h3>
 *
 * <p>因为<b>寻址方式不同</b>：<pre>
 *   ShopProductService  —— 以【商品】为中心：给我一件商品，我列出它的规格
 *   ShopSkuService      —— 以【SKU】为中心：给我一个 skuId，我告诉你它是什么
 * </pre>
 * 里程碑 15 之后，「买什么」由 skuId 表达（购物车里存的、下单时传的都是它）。
 * 一个只有 skuId 的调用方（购物车、立即购买）手里<b>没有 productId</b>，
 * 它需要的这一组查询在 {@code ShopProductService} 上无处安放。
 *
 * <p>★ 判断「该不该新开一个 Service」的标准不是类的数量，而是
 * <b>「这两个方法的调用方，手里握着的是不是一个东西」</b>。
 *
 * <h3>★ 这里的方法【刻意】不都抛异常</h3>
 *
 * <p>三个方法的失败方式各不相同，因为<b>它们的调用方要说的不是同一句话</b>：
 * <pre>
 *   getAvailable   单个。查不到 → 抛 1003「商品不存在或已下架」
 *                  （立即购买：用户就是冲着这一个来的，只能明确告诉他不行）
 *   listAvailable  一批。【不抛错】。查不到的那些不出现，靠差集识别
 *                  （购物车：这一行要显示成「已失效」并留在页面上，
 *                    而不是让整个购物车报错 —— 用户会以为车坏了）
 * </pre>
 *
 * <p>★ 这条区分很重要：<b>「缺失」在批量场景里是一个正常的返回值，
 * 在单个场景里是一个错误。</b>把它们统一成一种处理方式，
 * 一定有一边的体验是错的。
 */
public interface ShopSkuService {

    /**
     * 取一行<b>可买</b>的 SKU（连带它的商品信息）。
     *
     * <p>「可买」= SKU 行存在 <b>且</b> 它所属的商品是上架的。
     *
     * <p>它取代了里程碑 7 的 {@code CartServiceImpl.requireAvailableProduct} ——
     * 那个方法是按 productId 校验的，现在加购、下单、
     * {@code GET /api/shop/skus/{id}} 三处都需要这条规则，
     * 复制三份就是三条会分叉的规则。
     *
     * @param skuId SKU 主键
     * @return 永远非 null
     * @throws com.example.mall.common.BusinessException 业务码 1003（商品不存在或已下架）
     */
    ShopSkuVO getAvailable(Long skuId);

    /**
     * 批量取一批<b>可买</b>的 SKU（连带它们的商品信息）。
     *
     * <p>★ <b>不抛异常</b>：某个 skuId 不可买时，它只是不出现在结果里。
     * 调用方拿「请求了哪些」和「回来了哪些」求差集，
     * 就知道哪几行失效了 —— 那正是购物车的「失效商品」。
     *
     * <p>⚠️ 返回的条数可能少于传入的个数，<b>不要按下标对位</b>。
     * 结果里每一行都带着 {@code id}，按它去配对。
     *
     * @param skuIds skuId 列表。为 null 或空时返回空列表（不报错）——
     *               「购物车是空的」是个正常状态，不是错误
     * @return 其中可买的那些，可能为空列表但不会是 null
     */
    List<ShopSkuVO> listAvailable(List<Long> skuIds);

    /**
     * 列出某件商品的全部规格，给详情页的规格选择器用。
     *
     * <p>★ <b>不筛有货的</b>：库存为 0 的规格必须能被看见，
     * 详情页要把它的按钮标灰、要能说清「该规格暂时缺货」。
     * 被过滤掉的规格看起来就像「这个规格压根不存在」——
     * 「不存在」和「不可买」是两种状态，不能靠删数据来表达后者。
     *
     * <p>⚠️ 这个方法<b>不检查商品是否上架</b>：它由商品详情接口
     * 内部调用，而那个接口在更外层已经确认过商品可读了。
     * 单独把它暴露给前端会让下架商品的规格可以被直接枚举 ——
     * 所以它<b>没有</b>对应的 HTTP 接口。
     *
     * @param productId 商品 id
     * @return 该商品的 SKU，可能为空列表但不会是 null
     */
    List<SkuVO> listByProductId(Long productId);
}
