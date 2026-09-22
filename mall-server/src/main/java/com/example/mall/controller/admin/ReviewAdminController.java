package com.example.mall.controller.admin;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.ReviewQueryDTO;
import com.example.mall.service.ProductReviewService;
import com.example.mall.vo.AdminReviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品评价管理接口（<b>管理端</b>）。
 *
 * <h3>★ 权限边界在路径前缀，不在这里写一行判断</h3>
 *
 * <p>{@code WebMvcConfig} 里 {@code AdminAuthInterceptor} 拦的是
 * <b>整个</b> {@code /api/admin/**}（只排除登录接口本身），
 * 所以这个 Controller 一挂到 {@code /api/admin/reviews} 上就自动受保护 ——
 * <b>不是「记得给它加保护」，是「想不加保护都难」</b>。
 *
 * <p>⚠️ 但要说清一件容易含混的事：
 * <b>拦截器保证的是「你是管理员」，不是「你有权删这一条」。</b>
 * 本项目<b>没有管理员权限模型</b>（任何登录的管理员都能删任何一条评价）。
 * 这是一个<b>明知的简化</b>，不是遗漏 ——
 * 和 {@code AdminOrderController} 的发货权限是完全同一个状态。
 *
 * <h3>★ 为什么用户端和管理端是两个 Controller</h3>
 *
 * <p>和 {@code AdminOrderController} 的理由一样：
 * 两端的<b>权限规则和返回字段</b>都不一样。
 * 管理端要多看到「这条评价挂在哪个商品上、是谁写的」
 * （{@code AdminReviewVO} 多出来的那几个字段），用户端一个都不该看到。
 * 混在一个类里会变成一堆 {@code if (isAdmin)}，
 * 而且拦截器没法按路径一刀切 —— <b>而路径一刀切正是本项目的安全基石</b>。
 *
 * <p>★ 注意这里<b>没有</b> {@code POST} / {@code PUT}：
 * 评价「不能改也不能删」（用户的选择），管理端只多了「删」。
 * 一个只有查询和删除的资源，Controller 也就只有两个方法。
 * <b>接口的数量应该反映业务上真实存在的动作，而不是「一个资源标配 CRUD」。</b>
 */
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
public class ReviewAdminController {

    private final ProductReviewService reviewService;

    /**
     * 分页查全部会员的评价（可按商品名、会员模糊筛选）。
     *
     * <p>{@code GET /api/admin/reviews?pageNum=1&pageSize=10&productKeyword=手机&memberKeyword=张}
     *
     * <p>★ 两个筛选都是<b>模糊匹配</b>，理由见 {@code ReviewQueryDTO} 的类注释
     * （商品名和会员名都是「想起来关键词」，不是「对着抄的标识符」）。
     *
     * <p><b>★ 为什么没有 {@code @Valid}？</b>
     * 和项目里所有列表接口一致：查询条件<b>不存在「非法输入必须拒绝」的情况</b>
     * （{@code normalize()} 会把空串清成 null、把分页参数钳到合法范围）。
     * 只有「不能为空」「越界必须报错」那类才用 {@code @Valid} ——
     * 比如下面这个删除接口的 {@code id}，或者 {@code create} 的 {@code rating}。
     */
    @GetMapping
    public Result<PageResult<AdminReviewVO>> page(ReviewQueryDTO query) {
        return Result.success(reviewService.pageForAdmin(query));
    }

    /**
     * 删除一条评价。
     *
     * <p>{@code DELETE /api/admin/reviews/7}
     *
     * <p><b>没有请求体</b>，理由和 {@code ShopOrderController.cancel} 一样：
     * 删哪一条在 URL 里，谁在删在 token 里，没有参数就没有可以被乱填的地方。
     *
     * <p>★ 返回 {@code Result<Void>} —— <b>删除类接口的惯例</b>
     * （对照 {@code ShopCartController.remove}）。
     * 删完之后前端手上的数据已经够了（少了一行），不需要服务端再告诉它什么。
     * <b>「操作之后调用方是不是立刻要用到某个值」</b>这条判断
     * 在这里的答案是否定的 —— 这正是它和下单接口返回整个订单的区别。
     *
     * <h4>⚠️ 这是物理删除，有两个必须知道的后果</h4>
     * <ol>
     *   <li><b>唯一索引的槽位会被释放</b>：删掉之后，
     *       那个会员可以<b>重新评价</b>这条订单明细。
     *       语义上合理（被删掉的违规评价不该永久剥夺他重写的权利），
     *       但它是「删除」这个动作的真实后果。详见
     *       {@code ProductReviewServiceImpl.delete}。</li>
     *   <li><b>磁盘上的晒图文件不会删</b>，会留在 {@code uploads/} 里成为孤儿 ——
     *       里程碑 11 起确认的取舍，见 {@code FileStorageServiceImpl} 的类注释。</li>
     * </ol>
     *
     * <p>⚠️ 删除<b>不区分「谁的」评价</b>：管理员就是在删别人的内容，
     * 加会员隔离反而是语义错误。这条豁免和
     * {@code OrderAdminMapper.markShipped} 是同一个理由 ——
     * <b>安全边界是拦截器，不是数据归属。</b>
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        reviewService.delete(id);
        return Result.success();
    }
}
