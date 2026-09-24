package com.example.mall.mapper;

import com.example.mall.entity.ProductSku;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品 SKU 数据访问接口。
 *
 * <p>方法体在 {@code src/main/resources/mapper/ProductSkuMapper.xml}，
 * 没有实现类 —— 和 {@code ProductMapper} 完全同一套机制。
 *
 * <p><b>本层唯一的职责是「读写数据库」</b>，不放任何业务判断：
 * 「库存不足要抛 1001」「组合数不能超过 60」这类规则一律在 Service。
 *
 * <h3>★ 为什么这张表的写操作比 {@code ProductMapper} 少得多？</h3>
 *
 * <p>因为它<b>没有通用的 {@code updateById}</b>，这是刻意的：
 * 通用版的字段清单里会包含 {@code spec_json}，而那是这一行的<b>身份</b>
 * （它和 {@code product_id} 一起构成 {@code uk_product_spec}）。
 * 能原地改身份，就能把「黑色 128G」那一行改成「白色 256G」，
 * 而 {@code order_item.sku_id} 正指着它 —— 历史订单的快照会被就地篡改。
 *
 * <p>{@code spec_json} 的改动方式只有一种：<b>插一条新的、删一条旧的</b>。
 * 所以这里只有「改价格库存」这一条窄更新。
 *
 * <h3>★ 扣库存 / 还库存为什么也在这张表上</h3>
 *
 * <p>里程碑 15 阶段 4 把 {@code decreaseSkuStock} / {@code increaseSkuStock}
 * 从 {@code ProductMapper} 搬到了这里。搬家的理由不是「同类的东西放一起好看」，
 * 而是<b>库存的定义者换人了</b>：
 * <pre>
 *   阶段 4 之前  product.stock 是库存      → 扣它
 *   阶段 4 之后  product_sku.stock 是库存  → 扣它
 * </pre>
 * 而本轮的不变量是「{@code product} 表上没有库存」。<b>一个表上没有的东西，
 * 这里就不该有改它的方法</b> —— 留着 {@code ProductMapper.decreaseStock}
 * 等于留了一条「把库存写回一个已经不表示库存的列」的路。
 *
 * <p>⚠️ 那两条 SQL 的注释（为什么靠影响行数判断、为什么归还没有条件）
 * 整段搬了过来，一个字没改 —— 它们论证的对象从 product 行换成了 sku 行，
 * 而理由对两者完全一样。
 */
public interface ProductSkuMapper {

    /**
     * 查一个商品下的全部 SKU，按 id（插入顺序）排序。
     *
     * <p>管理端详情、规格矩阵编辑器回填都用它。
     *
     * <p>⚠️ <b>不过滤缺货的 SKU</b>：库存为 0 的规格必须能被看见，
     * 前端要把它标灰、要能说清「这个规格缺货」。被过滤掉的规格
     * 看起来就像「不存在」，用户会以为商品配置丢了。
     *
     * @return 该商品的 SKU 列表，没有时是空列表但不会是 null
     */
    List<ProductSku> selectByProductId(@Param("productId") Long productId);

    /**
     * 按主键查一行 SKU（<b>原始实体，不过滤上下架</b>）。
     *
     * <p>⚠️ <b>它查不出「这件商品还能不能买」</b>：{@code product_sku}
     * 上没有上下架的概念，那个状态在 {@code product.status} 上。
     * 用户端拿到它之后<b>必须</b>再去
     * {@code ProductMapper.selectShopById(sku.getProductId())} 确认一次 ——
     * 查不到就说明商品已下架或被删了。理由见 {@link #selectByIds} 的注释。
     */
    ProductSku selectById(@Param("id") Long id);

    /**
     * 新增一行 SKU，自增主键回填到 {@code sku.getId()}。
     *
     * @return 影响行数，正常为 1
     */
    int insert(ProductSku sku);

    /**
     * 改一行的价格和库存（只能按 id 改，不能改规格 —— 见类注释）。
     *
     * <p>它是 {@code ProductServiceImpl.replaceSkus} 认领老行时用的：
     * 规格组合没变的那一行要<b>保住它的 id</b>，
     * 因为 {@code order_item.sku_id} 指着它。
     *
     * <p>⚠️ 这是<b>全量覆盖</b>：管理员打开编辑页时库存 10、期间卖掉 3 件、
     * 然后点保存 —— 库存会回到 10，凭空多出 3 件。
     * 这是本阶段<b>已知的取舍</b>（真正的修法是「库存调整走增量 ±N」，
     * 明确推迟），表单上有一行提示，README 的已知取舍里也记了一笔。
     *
     * @return 影响行数。返回 0 说明 id 不存在
     */
    int updatePriceStock(@Param("id") Long id,
                         @Param("price") BigDecimal price,
                         @Param("stock") Integer stock);

    /**
     * 按 id 批量删除。
     *
     * <p>⚠️ <b>调用方必须保证 {@code ids} 非空</b>：空集合会拼出
     * {@code IN ()}，那是 SQL 语法错误。<code>&lt;foreach&gt;</code>
     * 没有「一个都没有就整体不生成」的语义，那是 <code>&lt;if&gt;</code> 的活。
     *
     * @return 影响行数
     */
    int deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 按一组主键批量查 SKU（<b>原始实体，不过滤上下架</b>）。
     *
     * <p>购物车在 Redis 里只存了「skuId → 数量」，展示需要的一切
     * 都得回 MySQL 查。用 {@code IN} 一条搞定，<b>绝不循环调
     * {@link #selectById}</b> —— 那是经典的 N+1：车里 10 件就发 10 条 SQL。
     *
     * <h3>★ 为什么它没有像 {@code ProductMapper} 那样另开一组 {@code shop*} 方法？</h3>
     *
     * <p>因为「用户端只能看下架商品之外的东西」这条<b>安全规则不在这张表上</b>。
     * {@code product_sku} 自己<b>没有上下架的概念</b> ——
     * 一行 SKU 可不可买，取决于它所属的 {@code product.status}。
     *
     * <p>于是那条规则仍然只写在<b>一个地方</b>：
     * {@code ProductMapper.selectShopById} / {@code selectShopByIds} 的
     * {@code WHERE p.status = 1}。Service 拿到这里的 SKU 之后
     * 去那两个方法里查它们的商品 —— <b>查不到商品的，就是不可买的</b>。
     *
     * <p>★ 这样安排的好处是：过滤规则没有第二条实现。
     * 如果这里也 JOIN 一次 product 加上 {@code status = 1}，
     * 那么「用户端能看见什么」就有了两处定义，而它们迟早会分叉 ——
     * 分叉的表现是「购物车里显示得出来、下单时说商品不存在」。
     *
     * <p>⚠️ <b>返回的条数可能少于传入的个数</b>（甚至一条都没有），
     * 这不是错误：差集就是「不可买的那几行」。
     * <b>不要假设返回的 list 和传入的 ids 一一对应。</b>
     *
     * @param ids skuId 列表。<b>调用方必须保证非空</b> ——
     *            空的 {@code IN ()} 是 SQL 语法错误，
     *            {@code <foreach>} 也生成不出合法 SQL
     * @return 对应的 SKU 行，可能为空列表
     */
    List<ProductSku> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 删掉一个商品下的全部 SKU（五级级联的第 4 级）。
     *
     * <p>必须排在 {@code DELETE FROM product} 之前 —— 全库零外键，
     * 顺序错了数据库不会拦，只会安静地攒孤儿行。
     *
     * @return 影响行数
     */
    int deleteByProductId(@Param("productId") Long productId);

    /**
     * 扣减 SKU 库存 —— <b>本项目里防超卖最关键的一个方法。</b>
     *
     * <p>「为什么这条 UPDATE 能防超卖」「为什么不能先查再改」
     * 整段论证在 {@code ProductSkuMapper.xml} 里（阶段 4 从
     * {@code ProductMapper.xml} 原样搬过来的）。这里只写<b>调用契约</b>：
     *
     * <p><b>★ 影响行数就是判断结果，调用方不要自己查库存来判断：</b>
     * <pre>
     *   返回 1 → 扣减成功
     *   返回 0 → 库存不足或这一行已不存在，且【库存没有被改变】
     * </pre>
     * 返回 0 时调用方要抛业务异常（1001 库存不足）。
     *
     * <p>⚠️ 事务是必须的：扣库存和写 {@code order_item} 要在同一个事务里，
     * 否则会出现「库存扣了但订单没生成」。
     *
     * @param id       SKU 主键
     * @param quantity 要扣掉的数量，必须为正数（0 或负数会变成「加库存」）
     * @return 1 = 扣减成功；0 = 库存不足或这一行已不存在
     */
    int decreaseSkuStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 归还 SKU 库存（取消订单 / 订单超时自动取消时用）。
     *
     * <p>⚠️ <b>归还和扣减【故意】不对称</b>：这条 SQL 没有任何附加条件。
     * 理由（「出错路径上条件越少越好」）见 {@code ProductSkuMapper.xml}。
     *
     * <p><b>★ 影响 0 行不算错误，调用方记一条 warn 就继续</b> ——
     * 它有两种正常来源：SKU 已被硬删、或者 {@code id} 本来就是 NULL
     * （{@code order_item.sku_id} 可空，历史订单和孤儿明细填不出真值）。
     *
     * @param id       SKU 主键，<b>允许为 null</b>
     * @param quantity 要归还的数量，必须为正数
     * @return 1 = 归还成功；0 = SKU 不存在（已硬删 / id 为 NULL）
     */
    int increaseSkuStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}
