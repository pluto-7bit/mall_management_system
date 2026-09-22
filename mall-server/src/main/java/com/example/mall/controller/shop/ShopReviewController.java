package com.example.mall.controller.shop;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.PageQueryDTO;
import com.example.mall.dto.ReviewSaveDTO;
import com.example.mall.service.ProductReviewService;
import com.example.mall.vo.ReviewVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品评价接口（<b>用户端</b>）。
 *
 * <h3>★★★ 这个类里两个方法的权限【不一样】，而决定权不在这个类里</h3>
 *
 * <pre>
 *   GET  /api/shop/products/{productId}/reviews   →  ★ 匿名可调
 *   POST /api/shop/reviews                        →  ★ 必须登录
 * </pre>
 *
 * <p><b>必须先看 {@code WebMvcConfig} 的 excludePathPatterns 才能知道这件事</b>——
 * {@code MemberAuthInterceptor} 拦的是 {@code /api/shop/**}，
 * 然后按那份排除列表放行。所以一个 {@code /api/shop/} 下的接口要不要登录，
 * <b>唯一的依据是那份排除列表，不是路径长什么样</b>。
 *
 * <p>这一条必须写在这里，因为「同一个类里的两个方法权限不同」
 * 是<b>反直觉</b>的：绝大多数人看到 {@code @RestController} 就默认
 * 这个类里的东西待遇一致。不写的话，下一个人加第三个方法时
 * 会照着上面那个写（反正「这个类是公开的」），然后它就裸奔了。
 *
 * <h3>★★ 评价列表挂在 {@code /products/**} 下面不是风格选择，是【承重】的</h3>
 *
 * <p>因为 {@code WebMvcConfig} 的排除列表里有
 * <b>{@code /api/shop/products/**}</b> —— 所以
 * {@code GET /api/shop/products/{productId}/reviews} 会
 * <b>自动</b>落进「商品浏览匿名可读」那条规则，一个字都不用改配置。
 *
 * <p><b>★ 路径本身就是权限声明。</b>把「某个商品的评价」放在
 * 「某个商品」的路径树下，既符合 REST 的资源从属关系，
 * 又刚好免费继承了那条只读豁免 —— 两件事同时成立不是巧合，
 * 是因为「匿名可读」这条规则本来就是<b>按资源</b>划的：
 * 商品是公开资源，商品的评价也是。
 *
 * <p>反过来，{@code POST /api/shop/reviews} 落在一条
 * <b>全新的、没被排除的路径树</b>里。<b>这不是「随便挑了个地址」</b>，
 * 而是 {@code WebMvcConfig} 里那条规矩的直接应用：
 * <b>「需要登录的接口，不要放在被排除的路径树下」，各自独立成树。</b>
 * 如果图省事把它写成 {@code POST /api/shop/products/{id}/reviews}，
 * 它就变成匿名可写的了 —— 而且不会有任何报错，拦截器根本不跑。
 * （这正是那段注释里「里程碑 7 的购物车绝对不能挂在这里」警告过的同一个坑。）
 */
@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopReviewController {

    private final ProductReviewService reviewService;

    /**
     * 分页查某个商品的评价（★ 匿名可调）。
     *
     * <p>{@code GET /api/shop/products/5/reviews?pageNum=1&pageSize=10}
     *
     * <p>★ 参数用的是<b>基类</b> {@code PageQueryDTO}，不是专门建的 QueryDTO ——
     * 因为这个接口<b>只有分页参数</b>（「查哪个商品」是路径参数，
     * 不是筛选条件）。
     *
     * <p>⚠️ 一个字段都没有的子类不是抽象，是绕路。
     * 判据和「只有一个子类的基类不该存在」是同一条，只是方向相反。
     *
     * <p><b>★ 为什么没有 {@code @Valid}？</b>
     * 和用户端商品列表、我的订单的既有做法一致：
     * 分页参数不合法时<b>不是拒绝请求，而是钳到合法范围</b>
     * （{@code normalize()} 把 pageSize 钳到 100 以内、页码至少为 1）。
     * <b>「翻页翻到了不存在的一页」不该是一个错误</b> ——
     * 用户手改 URL 里的 pageNum 是常事，最好的回应是给他第 1 页。
     *
     * <p>⚠️ 路径段数是 3 段（{@code products}/{@code {productId}}/{@code reviews}），
     * 和 {@code ShopProductController} 的 {@code @GetMapping("/{id}")}（2 段）
     * <b>不冲突</b> —— Spring 按段数匹配，不是按前缀。
     *
     * <p>★ 它不校验商品是否存在：商品不存在就返回一个空页。
     * 理由见 {@code ProductReviewServiceImpl.pageByProduct}。
     */
    @GetMapping("/products/{productId}/reviews")
    public Result<PageResult<ReviewVO>> pageOfProduct(@PathVariable Long productId,
                                                      PageQueryDTO query) {
        return Result.success(reviewService.pageByProduct(productId, query));
    }

    /**
     * 发表一条评价（★ 必须登录）。
     *
     * <p>{@code POST /api/shop/reviews}，请求体是 JSON：
     * <pre>
     *   { "orderItemId": 12, "rating": 5, "content": "很好用", "images": ["/uploads/..."] }
     * </pre>
     *
     * <p><b>★★ 请求体里没有 {@code productId}，也没有 {@code memberId}。</b>
     * <pre>
     *   productId ← 服务端从 order_item 查出来（不信客户端报的商品）
     *   memberId  ← 服务端从 JWT 里取（不信客户端报的人）
     * </pre>
     * 客户端只报「我想评哪一条明细」，能不能评由 Service 判定。
     * 详见 {@code ReviewSaveDTO} 的类注释 —— 那里写了为什么
     * 「让客户端报 productId」是开了一个洞，而不是少传一个参数。
     *
     * <p>★ <b>返回 {@code Result<Long>}，一个裸的评价 id。</b>
     * 照 {@code ProductAdminController.create} 返回 {@code Result<Long>}
     * 和 {@code ImageAdminController.upload} 返回 {@code Result<String>}
     * 的先例 —— 前端提交成功后要做的事是<b>重查一次评价列表</b>
     * （把按钮变成「已评价」），它不需要这个 id 来做什么。
     * 为一个用不上的返回值造一个 VO 是多余的。
     *
     * <p>⚠️ <b>为什么不返回整个 {@code ReviewVO}？</b>
     * 对照{@code ShopOrderController} 的下单接口 —— 那个返回了完整的订单，
     * 因为<b>下单结果页马上就要显示它</b>。而这里不同：
     * 刚提交的评价只能是<b>列表里的某一条</b>，位置取决于排序和分页，
     * 前端没法把它「就地拼进列表」而不破坏分页 ——
     * 所以它无论如何都要重查一次。
     * <b>判断依据还是那一条：这个操作的调用方，接下来是不是立刻要用到它。</b>
     *
     * <p>★ {@code @Valid} 在这里是<b>必须</b>的：{@code rating} 越界、
     * {@code content} 为空、晒图超过 3 张都要报错让用户改。
     * 校验失败抛 {@code MethodArgumentNotValidException}，
     * 由 {@code GlobalExceptionHandler} 转成 <b>HTTP 200 + code 400</b>
     * （本项目的约定：业务错误不走 HTTP 状态码）。
     */
    @PostMapping("/reviews")
    public Result<Long> create(@RequestBody @Valid ReviewSaveDTO dto) {
        return Result.success(reviewService.create(dto));
    }
}
