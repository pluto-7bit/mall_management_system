package com.example.mall.controller.admin;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.LogisticsTraceSaveDTO;
import com.example.mall.dto.OrderQueryDTO;
import com.example.mall.dto.OrderShipDTO;
import com.example.mall.service.LogisticsService;
import com.example.mall.service.OrderService;
import com.example.mall.vo.AdminOrderVO;
import com.example.mall.vo.LogisticsVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final LogisticsService logisticsService;

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
     * <p><b>★★ 里程碑 18：这个接口从「没有请求体」变成了「请求体必填」。</b>
     * 里程碑 10 那句「没有参数就没有可以被乱填的地方」本身没错，
     * 它错在<b>当时没有东西要填</b> —— 货是怎么发出去的（哪家快递、单号多少）
     * 是发货这个动作的一部分，里程碑 10 只是没有记它。
     *
     * <p>⚠️ 所以本方法是这个 Controller 里<b>唯一加 {@code @Valid} 的</b>。
     * 别的接口不加，是因为它们的分页参数靠 {@code normalize()} 兜住脏值
     * （不拒绝请求，同上面 {@link #page}）；而这里没有可兜的余地 ——
     * 承运商和单号必须有值，否则用户端连「查看物流」的入口都渲染不出来。
     * <b>校验策略取决于「脏值能不能被兜住」，不取决于「统一风格」。</b>
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
    public Result<AdminOrderVO> ship(@PathVariable String orderNo,
                                     @Valid @RequestBody OrderShipDTO dto) {
        return Result.success(orderService.ship(orderNo, dto));
    }

    // ==========================================================================
    // 里程碑 18：物流
    //
    // ★ 三个方法，读 / 增 / 删 —— 刻意【没有修改】。
    //   一条被改过的轨迹不是轨迹：它的 createTime 会说谎，
    //   而「这一条到底发生过什么」会有两个版本。改错的做法是删了重录。
    //
    // ★ 三个方法【都返回 LogisticsVO】（包括删除）——
    //   「一次操作之后前端马上要用到的数据，就该由这个操作直接返回」。
    //   对话框增删一个节点后要的就是刷新后的完整列表；
    //   返回空 body 会逼前端再发一次 GET，而那次失败时用户会看到
    //   「删掉了但还显示着」的界面。
    //
    // ★ 承运商和单号【不在这里改】—— 它们在发货那一步写死在 orders 上，
    //   没有「改单号」接口。发错货的补救路径是走售后退款再重下，
    //   而不是让单号可以被改来改去（那会让「这一单当初怎么发的」没有答案）。
    // ==========================================================================

    /**
     * 查这一单的物流（管理端）。{@code GET /api/admin/orders/{orderNo}/logistics}
     *
     * <p>和用户端的 {@code GET /api/shop/orders/{orderNo}/logistics} 同一个形状，
     * 唯一的结构性差别是<b>这里没有会员隔离</b> ——
     * 「这条订单是不是我的」对管理员来说是个没有意义的问题。
     * 边界由 {@code AdminAuthInterceptor} 在路径层面提供。
     *
     * <p>★ 同样是纯读，同样<b>不判断订单状态</b>（见用户端那个方法的注释）。
     */
    @GetMapping("/{orderNo}/logistics")
    public Result<LogisticsVO> logistics(@PathVariable String orderNo) {
        return Result.success(logisticsService.getAdminLogistics(orderNo));
    }

    /**
     * 新增一个物流轨迹节点。★ 本轮的主交付点。
     * {@code POST /api/admin/orders/{orderNo}/logistics}
     *
     * <p>请求体是 {@code {status, description, traceTime}}，三个都必填。
     *
     * <p>★★ <b>录到「已签收」（{@code status = 4}）会把订单自动推到「已完成」，
     * 而且不可撤销。</b>这是用户拍板要的联动。它<b>不是第二个状态写入者</b> ——
     * 它写的是「已发货 → 已完成」这条边的第二个行动者，
     * 用的是同一个 {@code WHERE status = 2} 闸门。
     * 完整论证见 {@code OrderAdminMapper.markCompletedByNo}。
     *
     * <p>⚠️ <b>所以管理端的对话框必须把这句话写在操作者眼前</b>
     * （「记入『已签收』会把订单标记为已完成，且不可撤销」），
     * 而不是靠事后补救 —— 删掉节点<b>不会</b>把订单退回去。
     *
     * <p>⚠️ 只有「已发货 / 已完成」的订单能记物流，其余状态报 1002。
     * 这条闸门在 Service（{@code LogisticsServiceImpl.addTrace}），
     * <b>不在 SQL 的 WHERE 里</b> —— 它是「这一次动作的前置条件」，
     * 而 SQL 的 WHERE 里没有「这一次」那一刻（判据 ②，里程碑 17 立的）。
     *
     * <p>★ 加 {@code @Valid} 的理由同上面 {@link #ship}：
     * 三个字段没有可兜的余地，脏值只能在入口拒绝。
     */
    @PostMapping("/{orderNo}/logistics")
    public Result<LogisticsVO> addTrace(@PathVariable String orderNo,
                                        @Valid @RequestBody LogisticsTraceSaveDTO dto) {
        return Result.success(logisticsService.addTrace(orderNo, dto));
    }

    /**
     * 删除一个录错的轨迹节点。
     * {@code DELETE /api/admin/orders/{orderNo}/logistics/{traceId}}
     *
     * <p>★★ <b>删除【不回退】订单状态</b> —— 删掉一条「已签收」不会把订单
     * 退回「已发货」。这是决定，不是漏了。完整的三层理由见
     * {@code LogisticsServiceImpl.deleteTrace}。
     *
     * <p>★ 路径里<b>必须</b>带 {@code orderNo}，虽然 {@code traceId} 本身
     * 全局唯一、看起来一个就够 —— 服务层要用它先把 {@code orderId} 查出来，
     * 再用 {@code WHERE id = ? AND order_id = ?} 删。
     * 这样<b>前端传错 id 也删不到别人的节点</b>；
     * 而只按 {@code traceId} 删的话，那个 id 就是一把能开任何订单的钥匙。
     *
     * <p>★ 用 {@code DELETE} 而不是 {@code POST .../delete}：
     * 这是标准的 REST 语义，而且**这个操作真的是幂等意义上的删除**
     * （删两次第二次报 1003，不会删掉别的东西）。
     * 本项目其他「动作」型接口用 POST，是因为它们改状态、不删资源。
     */
    @DeleteMapping("/{orderNo}/logistics/{traceId}")
    public Result<LogisticsVO> deleteTrace(@PathVariable String orderNo,
                                           @PathVariable Long traceId) {
        return Result.success(logisticsService.deleteTrace(orderNo, traceId));
    }
}
