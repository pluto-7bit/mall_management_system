package com.example.mall.controller.admin;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.OrderQueryDTO;
import com.example.mall.service.OrderService;
import com.example.mall.vo.AdminOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单管理接口（<b>管理端</b>）。
 *
 * <h3>★ 它是本项目第一个「管理员操作别人订单」的入口</h3>
 *
 * <p>在这之前，订单相关的接口全都在 {@code /api/shop/orders/**} 下面，
 * 而那条路径下的每一条查询都带着 {@code AND member_id = #{memberId}}。
 * 本文件的查询<b>故意没有</b>这个条件 —— 管理员就该看到所有人的订单，
 * 发货本来就是在操作<b>别人的</b>订单。
 *
 * <p><b>★ 那安全边界在哪？在路径前缀。</b>
 * {@code WebMvcConfig} 里 {@code AdminAuthInterceptor} 拦截
 * <b>整个</b> {@code /api/admin/**}（只排除登录接口本身），
 * 所以这个 Controller 一挂到 {@code /api/admin/orders} 上就自动受保护 ——
 * 不是"记得给它加保护"，是"想不加保护都难"。
 *
 * <p>⚠️ 但这里要说清一件容易含混的事：
 * <b>拦截器保证的是「你是管理员」，不是「你有权发这个货」。</b>
 * 本项目<b>没有管理员权限模型</b>（任何登录的管理员都能发货、
 * 能看全部订单）。这是一个<b>明知的简化</b>，不是遗漏 ——
 * 真实系统里这里会是一个权限点，但那属于"权限系统"这个独立话题。
 * <b>明知的简化可以接受，被误以为已经做了的简化不行。</b>
 * （同一个说明也写在 {@code OrderAdminMapper} 的类注释里。）
 *
 * <h3>★ 为什么管理端和用户端是两个 Controller，而不是一个里面写 if？</h3>
 *
 * <p>和 {@code ProductAdminController} 的类注释讲的是同一件事：
 * 两端的<b>权限规则、数据范围、返回字段</b>都不一样。
 * 管理端要多看到「这单是谁下的」（{@code AdminOrderVO} 的两个会员字段），
 * 用户端一个都不该看到。混在一起就会变成一堆 {@code if (isAdmin)}，
 * 而且拦截器没法按路径一刀切。
 *
 * <p>本项目虽然只有一个应用、一个数据库，但<b>「两端」这条界线是真实的</b>，
 * 代码里的分包和分 Controller 只是让它显形。
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    /**
     * 分页查询所有会员的订单。
     *
     * <p>{@code GET /api/admin/orders?status=1&orderNo=xxx&memberKeyword=张&pageNum=1&pageSize=10}
     *
     * <p>三个筛选条件，各有各的匹配方式，<b>混在一起会出问题</b>：
     * <pre>
     *   status         精确匹配（null = 全部，不是 0）
     *   orderNo        精确匹配 —— 订单号是 20 位业务编号，只该整串比对
     *   memberKeyword  模糊匹配（username 或 nickname 任一命中）
     * </pre>
     *
     * <p><b>★ 为什么不做成「一个 keyword 字段自动猜是订单号还是会员名」？</b>
     * 因为那会引入一个<b>猜错就静默返回错结果</b>的分支：
     * 一个昵称恰好长得像订单号的会员，搜索行为就变得无法预测。
     * 两个字段、两种语义，让调用方明确说出他要搜什么 —— 多一个输入框，
     * 换来的是「搜索结果永远等于你输入的条件」，这个交换很划算。
     *
     * <p>⚠️ {@code orderNo} 是精确匹配，所以 Service 里对它做了 {@code trim()}。
     * 这不是顺手 —— 管理员从页面上复制订单号时很容易带进来一个尾随空格，
     * 而精确匹配带着空格查就是 0 行，且<b>看不出任何错</b>
     * （没有报错、没有提示，就是"查不到"）。
     * 模糊匹配的 LIKE 天然不怕这个，精确匹配怕。
     *
     * <p>⚠️ 不加 {@code @Valid}，理由同用户端列表：
     * 分页参数靠 {@code normalize()} 兜住脏值，不是拒绝请求。
     */
    @GetMapping
    public Result<PageResult<AdminOrderVO>> page(OrderQueryDTO query) {
        return Result.success(orderService.pageAdminOrders(query));
    }

    /**
     * 发货。
     *
     * <p>{@code POST /api/admin/orders/{orderNo}/ship}
     *
     * <p><b>没有请求体</b> —— 发哪一单在 URL 里，谁在发在 JWT 里。
     * 没有参数就没有可以被乱填的地方（同用户端的 cancel / complete）。
     *
     * <p>⚠️ 只有「已付款」的订单能发货。这条规则在
     * {@code OrderAdminMapper.markShipped} 的 {@code WHERE status = 1} 里守着，
     * <b>不在前端按钮上</b> —— 前端只是个界面，绕过它直接调接口是很容易的事。
     * 前端把不该有的按钮藏起来是<b>体验优化</b>，不是安全措施；
     * 真正的门在 SQL 的条件里。
     *
     * <h4>★★ 为什么这个操作需要二次确认（前端必须做）？</h4>
     *
     * <p>因为它<b>不可逆</b> —— 本项目没有「取消发货」接口。
     * 一次误点之后，那笔订单就永远停在「已发货」，没有任何页面能改回来。
     * <b>不可逆的操作，代价必须由「确认」这一步承担一部分。</b>
     * 这不是"多一步很烦"，而是把误操作的代价从"无法挽回"降到"多点一下"。
     *
     * <p>（对比：取消订单虽然也改状态，但用户可以重新下单，
     * 代价是可控的，所以那里的二次确认更多是防手滑。）
     *
     * <p>★ 返回发货<b>之后</b>的完整订单，前端可以直接用它刷新那一行，
     * 不用重查列表。判断标准还是那句：
     * <b>操作之后前端马上要用到的数据，就该由这个操作直接返回。</b>
     */
    @PostMapping("/{orderNo}/ship")
    public Result<AdminOrderVO> ship(@PathVariable String orderNo) {
        return Result.success(orderService.ship(orderNo));
    }
}
