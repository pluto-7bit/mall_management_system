package com.example.mall.controller.shop;

import com.example.mall.common.Result;
import com.example.mall.service.ShopSkuService;
import com.example.mall.vo.ShopSkuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SKU 读取接口（<b>用户端</b>）。
 *
 * <p>这个 Controller 属于 {@code /api/shop/**} 下<b>允许游客访问</b>的那一片区域，
 * 理由和 {@link ShopProductController} 完全一样 —— 它也是只读的：
 * 「读可以匿名，写必须登录」。
 *
 * <h3>★ 为什么只有一个方法？</h3>
 *
 * <p>因为 {@code ShopSkuService} 上的另外两个方法<b>不该有 HTTP 接口</b>，
 * 而这是一个需要说清楚的边界：
 *
 * <table border="1">
 *   <tr><th>Service 方法</th><th>为什么不给接口</th></tr>
 *   <tr>
 *     <td>{@code listAvailable}</td>
 *     <td>它服务的是<b>购物车</b>，而购物车必须登录、而且只该看到
 *         <b>自己车里</b>的那些 skuId。做成公开接口，任何人传一批
 *         skuId 就能批量探测「哪些还在卖」—— 那是一次没有成本的爬取。
 *         购物车页面本身就提供了这个数据（只要登录）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code listByProductId}</td>
 *     <td>它<b>不检查商品是否上架</b>（由调用方保证）。
 *         商品详情接口已经把它需要的规格一起返回了，
 *         单独暴露它就等于给了一条「绕过商品详情、直接枚举下架商品规格」的路</td>
 *   </tr>
 * </table>
 *
 * <p>★ 这是一个反复出现的判断：<b>Service 上的方法不等于接口上的路径。</b>
 * Service 是「这个域能提供什么」，接口是「外界能问什么」。
 * 每加一个 {@code @GetMapping} 之前问一句「谁需要它、他能不能通过已有的东西拿到」——
 * 加接口的成本不是那 5 行代码，是<b>多了一个要一直维护的契约</b>。
 *
 * <h3>★ 为什么挂在 {@code /api/shop/skus} 而不是 {@code /api/shop/products/{id}/skus}？</h3>
 *
 * <p>因为调用方<b>手里只有 skuId，没有 productId</b>。
 * 挂成商品下面的一级路径，等于要求「立即购买」的链接里同时带着两个 id ——
 * 而它们在 URL 里一旦不一致（用户改了其中一个），就会出现
 * 「A 商品的第 3 个规格」这种自相矛盾的请求，还得再写一段校验去判断谁说了算。
 *
 * <p>★ <b>URL 的结构应该反映「谁是主键」。</b>里程碑 15 之后，
 * 交易那条链路上的主键就是 skuId。
 */
@RestController
@RequestMapping("/api/shop/skus")
@RequiredArgsConstructor
public class ShopSkuController {

    private final ShopSkuService shopSkuService;

    /**
     * 查一个 SKU 的详情（含它所属商品的名字、封面、分类）。
     *
     * <p>{@code GET /api/shop/skus/204}
     *
     * <p>「立即购买」用它：{@code /checkout?skuId=204&quantity=2} 这条链接上
     * 只有一个 skuId 和数量，结算页要显示的这一行（商品名、封面、
     * 规格文字、单价）全都得靠这次请求拿回来。
     *
     * <p>SKU 不存在、或它所属的商品已下架时，Service 抛业务异常，
     * 由全局异常处理器转成 {@code {code: 1003, message: "商品不存在或已下架"}}。
     * <b>Controller 里不需要写任何判断</b> —— 和商品详情接口完全一致。
     *
     * <p>⚠️ 和商品详情一样：这是 HTTP 200 + 业务码 1003，不是 HTTP 404。
     * 分工是「HTTP 状态码表达请求本身怎么样了，业务 code 表达
     * 请求没问题但你要的东西不存在」。
     *
     * <p>⚠️ 两种失败原因（不存在 / 已下架）<b>刻意返回同一句话</b>，
     * 理由见 {@code ProductMapper.selectShopById} 的注释：
     * 区分它们就等于告诉外面「这个 id 是存在的，只是下架了」，
     * 对按 id 遍历探测商品的人来说那是有效信息。
     */
    @GetMapping("/{skuId}")
    public Result<ShopSkuVO> detail(@PathVariable Long skuId) {
        return Result.success(shopSkuService.getAvailable(skuId));
    }
}
