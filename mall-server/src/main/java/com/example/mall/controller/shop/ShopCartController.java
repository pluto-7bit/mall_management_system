package com.example.mall.controller.shop;

import com.example.mall.common.Result;
import com.example.mall.dto.CartAddDTO;
import com.example.mall.dto.CartQuantityDTO;
import com.example.mall.service.CartService;
import com.example.mall.vo.CartVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车接口（<b>用户端</b>）。
 *
 * <h3>★★ 注意这个类所在的路径：{@code /api/shop/cart}，不是 {@code /api/shop/products}</h3>
 *
 * <p>这不是随便起的名字，而是<b>安全边界</b>。回顾一下
 * {@code WebMvcConfig}：为了让游客能浏览商品，我们把
 * {@code /api/shop/products/**} 整条路径从 {@code MemberAuthInterceptor}
 * 的拦截范围里<b>排除</b>了。
 *
 * <p>那意味着那条路径下的所有接口<b>都是匿名可访问的</b>。
 * 如果把「加入购物车」挂成 {@code POST /api/shop/products/cart}，
 * 它就会跟着一起被排除，任何人都能改别人的购物车 ——
 * <b>而且不会有任何报错，拦截器根本就不跑。</b>
 *
 * <p>这就是「按路径前缀做权限」的固有风险：授权规则和代码的对应关系
 * 是隐式的、靠位置维持的。防它的办法只有一条：
 * <b>把「需要登录」和「允许匿名」的接口放在不同的路径树下。</b>
 * 一次路径划分，换来一条看得见的边界。
 *
 * <p>所以本类一条路径都不排除，整个 {@code /api/shop/cart/**}
 * 都受 {@code MemberAuthInterceptor} 保护。<b>这个类里没有任何一个接口
 * 需要单独考虑鉴权</b> —— 这就是把同类的东西放在一起的好处。
 *
 * <h3>为什么这里的 {@code @Valid} 全都加上，而商品查询接口不加？</h3>
 *
 * <p>因为性质不同，见 {@code ShopProductController.page} 的注释：
 * <ul>
 *   <li>查询条件：全是可选参数，传脏值有合理兜底 → 不需要 {@code @Valid}</li>
 *   <li>写操作：数量传 0、传负数、不传 —— 都<b>没有</b>合理的兜底，
 *       必须明确拒绝 → 需要 {@code @Valid}</li>
 * </ul>
 * <b>「这个输入有没有安全的默认行为」是判断要不要强校验的标准。</b>
 */
@RestController
@RequestMapping("/api/shop/cart")
@RequiredArgsConstructor
public class ShopCartController {

    private final CartService cartService;

    /**
     * 查询购物车。
     *
     * <p>{@code GET /api/shop/cart}
     *
     * <p>返回 {@code {items: [...], totalQuantity, totalAmount}}。
     * 购物车为空时也返回 200 和一个结构完整的空对象，<b>不是 404</b> ——
     * 「购物车是空的」是一种正常状态，不是「资源不存在」。
     *
     * <p>这个区别在 REST 里经常被搞混。判断标准是：
     * <b>「空」是不是一个合法的、可预期的状态？</b>
     * 是就用 200（空购物车、空搜索结果、没有订单的新用户），
     * 不是才用 404（访问一个不存在的商品）。
     */
    @GetMapping
    public Result<CartVO> getCart() {
        return Result.success(cartService.getCart());
    }

    /**
     * 购物车商品件数（顶部角标用）。
     *
     * <p>{@code GET /api/shop/cart/count} → {@code {code:200, data:5}}
     *
     * <p><b>为什么单独开一个接口，而不是让前端调 {@code GET /api/shop/cart}
     * 然后自己数？</b>
     *
     * <p>因为角标要在<b>每一个页面</b>都显示。而完整的购物车接口会返回
     * 所有商品的名称、价格、库存、封面 —— 在商品详情页为了显示一个「3」
     * 去拉这一大坨数据，是很明显的浪费。
     *
     * <p>反过来说，<b>如果哪天前端需要在角标旁边显示「合计 ¥xxx」，
     * 那这个接口就应该被删掉</b>，因为那时候需要的数据量和完整接口一样了，
     * 保留两个接口只会让人犹豫该调哪个。
     *
     * <p><b>接口的粒度应该跟着「调用方真正需要多少数据」走，
     * 而不是固定的。</b>现在的选择是基于「只需要一个数字」这个事实。
     */
    @GetMapping("/count")
    public Result<Integer> count() {
        return Result.success(cartService.count());
    }

    /**
     * 加入购物车。
     *
     * <p>{@code POST /api/shop/cart/items}，body {@code {"productId":5,"quantity":2}}
     *
     * <p>{@code quantity} 是<b>增量</b>：车里已有 2 件时再加 3 件 → 5 件。
     * 「点两次加入购物车 = 加两次」是用户预期，所以用 POST 而不是 PUT。
     *
     * <p>返回体是空的（{@code Result<Void>}），前端不需要拿到什么 ——
     * 加完之后它会重新拉一次购物车。为什么不直接返回最新的购物车？
     * 因为那样每次加购都要多查一遍所有商品（见 {@code CartServiceImpl.getCart}），
     * 而前端本来就要刷新角标。让前端自己决定什么时候拉最新状态，
     * 比在写接口里硬塞一份读结果更灵活。
     */
    @PostMapping("/items")
    public Result<Void> add(@Valid @RequestBody CartAddDTO dto) {
        cartService.add(dto);
        return Result.success();
    }

    /**
     * 修改购物车里某个商品的数量。
     *
     * <p>{@code PUT /api/shop/cart/items/5}，body {@code {"quantity":3}}
     *
     * <p>⚠️ 商品 id 在 <b>URL 里</b>，数量在 <b>body 里</b> —— 为什么这么分？
     *
     * <p>因为 URL 标识的是<b>「改哪个东西」</b>（资源），
     * body 是<b>「改成什么样」</b>（新状态）。
     * {@code PUT /cart/items/5} 读起来就是「把 5 号商品这条记录，
     * 置成 body 描述的那个状态」。这正是 REST 里 PUT 的标准用法。
     *
     * <p>如果反过来写成 {@code PUT /cart/items} + body {@code {productId, quantity}}，
     * 也能用，但就丢掉了「URL 指向一个具体资源」这层含义，
     * 而且和 POST {@code /cart/items} 长得一模一样，只靠方法名区分，
     * 容易看错。
     *
     * <p><b>顺带一个安全细节：</b>这里的 {@code productId} 来自 URL，
     * 而 {@code CartQuantityDTO} 上<b>没有</b> productId 字段 ——
     * 所以 body 里就算写了 {@code productId: 999} 也会被忽略。
     * 这叫<b>参数白名单</b>，详情见 {@code CartQuantityDTO} 的类注释。
     */
    @PutMapping("/items/{productId}")
    public Result<Void> updateQuantity(@PathVariable Long productId,
                                       @Valid @RequestBody CartQuantityDTO dto) {
        cartService.updateQuantity(productId, dto);
        return Result.success();
    }

    /**
     * 从购物车移除一个商品。
     *
     * <p>{@code DELETE /api/shop/cart/items/5}
     *
     * <p>商品<b>本来就不在车里时也返回成功</b>（幂等）——
     * 理由见 {@code CartService.remove} 的注释。
     * 用户在两个标签页里各点了一次删除，第二次不该看到红色报错。
     */
    @DeleteMapping("/items/{productId}")
    public Result<Void> remove(@PathVariable Long productId) {
        cartService.remove(productId);
        return Result.success();
    }

    /**
     * 清空购物车。
     *
     * <p>{@code DELETE /api/shop/cart}
     *
     * <p>注意和上面那个的路径差别：这个删的是<b>整个购物车</b>（资源本身），
     * 上面那个删的是<b>购物车里的一个商品</b>（子资源）。
     * {@code DELETE /cart} vs {@code DELETE /cart/items/5} ——
     * REST 的路径层级天然表达了「操作的范围」，不用额外写文档说明。
     *
     * <p>前端做这个操作前<b>必须弹二次确认</b>：清空购物车是不可撤销的，
     * 而用户可能已经挑了半个小时。（后端不管这事 —— 它无法知道
     * 用户是不是手滑了，这属于交互层面的责任。）
     */
    @DeleteMapping
    public Result<Void> clear() {
        cartService.clear();
        return Result.success();
    }
}
