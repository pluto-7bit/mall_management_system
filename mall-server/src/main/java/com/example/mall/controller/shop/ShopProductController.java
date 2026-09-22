package com.example.mall.controller.shop;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.ShopProductQueryDTO;
import com.example.mall.service.ShopProductService;
import com.example.mall.vo.ShopProductDetailVO;
import com.example.mall.vo.ShopProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品浏览接口（<b>用户端</b>）。
 *
 * <p>这个 Controller 属于 {@code /api/shop/**} 下<b>允许游客访问</b>的那一片区域 ——
 * 见 {@link com.example.mall.config.WebMvcConfig} 里
 * {@code memberAuthInterceptor} 的 excludePathPatterns。
 *
 * <p>⚠️ <b>「唯一」这个词在里程碑 12 已经删掉了。</b>
 * 原来这里写的是「<b>唯一</b>允许游客访问的业务接口」，
 * 而 {@link ShopReviewController#pageOfProduct} 加入之后，
 * <b>游客能读的接口变成了两个</b>（它挂在 {@code /api/shop/products/{id}/reviews}，
 * 落在同一条排除规则里）。
 * <b>这种「原来只有我一个」的注释是最容易悄悄变假的一类</b> ——
 * 它描述的不是代码本身，而是「别的代码长什么样」，
 * 而别处新增一个接口时，没有任何机制会提醒你回来改这句话。
 * 所以这类注释要么不写，要么就得写清依据（去哪儿核对），
 * 让人下次能发现它过期了。
 *
 * <h3>★ 为什么商品浏览可以不登录</h3>
 *
 * <p>这是电商的通行规则，理由不是「技术限制」而是<b>生意</b>：
 * 让用户注册才能看商品，转化率会掉得非常惨 ——
 * 大部分人只是想随便看看，被一堵注册墙拦住就直接关掉了。
 * 而愿意注册的人，往往是已经决定要买的人，
 * 这时候向他索要账号才是合理的交换。
 *
 * <p>安全上的边界很清晰：<b>读可以匿名，写必须登录。</b>
 * 这个原则在设计接口时非常好用 ——
 * 遇到「这个接口要不要登录」的问题，
 * 先问一句「它是读还是写」，
 * 大部分情况答案就出来了。
 * （例外是「读别人的隐私数据」，比如我的订单，那当然要登录。）
 *
 * <h3>★ 这个类里一个写操作都没有</h3>
 *
 * <p>没有「加入购物车」「下单」「收藏」——
 * 那些是写操作，属于里程碑 7 之后的
 * {@code ShopCartController} / {@code ShopOrderController}。
 * 把只读的浏览和会改数据的操作分成不同的 Controller，
 * 好处是<b>可以按 Controller 划分权限边界</b>：
 * 这个类整个都能匿名访问，而另一个类整个都要登录，
 * 不用逐个方法去判断。
 */
@RestController
@RequestMapping("/api/shop/products")
@RequiredArgsConstructor
public class ShopProductController {

    private final ShopProductService shopProductService;

    /**
     * 浏览商品列表。
     *
     * <p>{@code GET /api/shop/products}
     * <pre>
     *   ?pageNum=1&amp;pageSize=12          第 1 页
     *   &amp;keyword=手机                    搜索关键词
     *   &amp;categoryId=1                    分类筛选
     *   &amp;sort=price_asc                  排序方式
     * </pre>
     *
     * <p><b>注意这个方法的参数只有一个 DTO，没有 {@code @Valid}。</b>
     * 因为 {@code ShopProductQueryDTO} 上没有任何校验注解 ——
     * 它所有的字段都是「可选」的，传不传、传什么都有合理的兜底
     * （非法值在 {@code normalize()} 里被规范化）。
     * 查询条件不存在「非法输入必须拒绝」的情况，
     * 所以不需要 {@code @Valid}。
     *
     * <p>对比一下新增商品的接口就必须有 {@code @Valid} ——
     * 那里商品名为空是<b>不能接受</b>的，必须报错让用户改。
     * <b>「可选参数」和「必填参数」在处理方式上是两类东西。</b>
     */
    @GetMapping
    public Result<PageResult<ShopProductVO>> page(ShopProductQueryDTO query) {
        return Result.success(shopProductService.page(query));
    }

    /**
     * 商品详情。
     *
     * <p>{@code GET /api/shop/products/5}
     *
     * <p>商品不存在或已下架时，Service 会抛业务异常，
     * 由全局异常处理器转成 {@code {code: 1003, message: "商品不存在或已下架"}}。
     * Controller 里<b>不需要写任何判断</b>。
     *
     * <p>⚠️ 注意这个请求返回的是 HTTP 200 + 业务码 1003，不是 HTTP 404。
     * 这正是本项目约定的分工：
     * <pre>
     *   HTTP 状态码 → 表达「这次请求本身怎么样了」
     *   业务 code   → 表达「请求没问题，但你要的东西不存在」
     * </pre>
     * 「商品不存在」是后者 —— 请求完全合法，只是这个商品没有。
     * 前端拿到 200 后看 body 里的 code，再决定显示什么。
     */
    @GetMapping("/{id}")
    public Result<ShopProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(shopProductService.detail(id));
    }
}
