package com.example.mall.controller.admin;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.AdminAfterSaleQueryDTO;
import com.example.mall.dto.AfterSaleRejectDTO;
import com.example.mall.service.AfterSaleService;
import com.example.mall.vo.AdminAfterSaleVO;
import com.example.mall.vo.AfterSaleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 售后管理接口（<b>管理端</b>）。★ 里程碑 17 新增。
 *
 * <h3>★ 安全边界在路径前缀，不在这个类里</h3>
 *
 * <p>{@code WebMvcConfig} 里 {@code AdminAuthInterceptor} 拦截
 * <b>整个</b> {@code /api/admin/**}（只排除登录接口本身），
 * 所以这个 Controller 一挂到 {@code /api/admin/after-sales} 上就自动受保护 ——
 * <b>不是「记得给它加保护」，是「想不加保护都难」。</b>
 * 完整的说明（包括「拦截器保证的是『你是管理员』，不是『你有权处理这一单』」
 * 这个明知的简化）写在 {@code AdminOrderController} 的类注释里。
 *
 * <h3>★★ 三个动作接口，对应三条边，而它们最关键的区分是「货在哪」</h3>
 *
 * <pre>
 *   GET    /api/admin/after-sales              列表（全部会员，四个筛选条件）
 *   POST   /api/admin/after-sales/{no}/approve 同意   仅退款 → 0→3 直接退款；退货退款 → 0→1
 *   POST   /api/admin/after-sales/{no}/reject  拒绝   0 或 1 → 4
 *   POST   /api/admin/after-sales/{no}/receive 确认收到退货 → 退款  2 → 3
 * </pre>
 *
 * <h3>★★ 为什么 {@code approve} 和 {@code receive} 是两个接口，不是三个</h3>
 *
 * <p>直觉的形状是「同意 → 收货 → 退款」三个接口、三个状态。
 * 本轮<b>只有两个</b>，因为仅退款那条路上没有中间状态 ——
 * 判据是 {@code OrderStatus} 的原话：
 * <b>「状态的划分标准是『接下来的行为会不会不同』」</b>。
 * 仅退款从「同意」到「退款」之间<b>没有任何人能做任何事</b>，
 * 那个中间状态里没有行为，所以它不该存在。
 *
 * <p>★ 反过来，「同意退货」和「确认收到退货」<b>必须是两个状态</b>，
 * 而且是最贵的一个决定：合成一个的话，管理员能在用户还没寄回时
 * 就点「确认收到」—— <b>钱退了，货还在买家手里，而且没有任何一层会报错。</b>
 * 两个状态的全部意义在这里：
 * <pre>
 *   待买家寄回（1）  只有买家能动（POST .../return）
 *   待卖家收货（2）  只有管理员能动（POST .../receive）
 *   库存归还在 2 → 3 上，不在 0 → 1 上
 * </pre>
 * <b>「谁按的按钮」和「货在哪」是同一件事的两面 —— 而它决定了库存什么时候回来。</b>
 *
 * <h3>★ 三个动作接口都没有请求体，除了 {@code reject}</h3>
 *
 * <p>同意不需要解释，确认收到也不需要。<b>参数只在「需要说点什么」的时候存在。</b>
 * 而拒绝的理由是 {@code @NotBlank} 的 —— 因为拒绝是唯一一个会让用户不满、
 * 且他无从申诉的动作。论证见 {@code AfterSaleRejectDTO}：
 * <b>必填的理由字段是把「决策」逼成「可以说清楚的决策」。</b>
 */
@RestController
@RequestMapping("/api/admin/after-sales")
@RequiredArgsConstructor
public class AdminAfterSaleController {

    private final AfterSaleService afterSaleService;

    /**
     * 全部会员的售后列表。
     *
     * <p>{@code GET /api/admin/after-sales?status=0&afterSaleNo=AS2026...&memberKeyword=张&type=2}
     *
     * <p>★ 四个条件各有各的匹配方式，和 {@code AdminOrderController.page}
     * 是同一套约定（那里有完整的对照表）：
     * <pre>
     *   status         精确匹配（null = 全部，不是 0）
     *   afterSaleNo    ★ 精确匹配 —— 管理员是拿着用户报的号来查的，
     *                    那种场景要的是「一条命中」，不是「列出所有以 AS 开头的单」。
     *                    而且等值查询能走 uk_after_sale_no 索引
     *   type           精确匹配
     *   memberKeyword  模糊匹配（username 或 nickname 任一命中），★ 刻意接受全表扫
     * </pre>
     */
    @GetMapping
    public Result<PageResult<AdminAfterSaleVO>> page(AdminAfterSaleQueryDTO query) {
        return Result.success(afterSaleService.pageForAdmin(query));
    }

    /**
     * 同意。
     *
     * <p>{@code POST /api/admin/after-sales/{afterSaleNo}/approve}
     *
     * <p>★★ <b>一条 URL 背后是两个业务操作，分支看的是库里这张单的类型</b>：
     * <pre>
     *   仅退款（type = 1）    →  0 → 3，<b>同意即退款</b>：退款 + 归还库存 + 可能推订单终态
     *   退货退款（type = 2）  →  0 → 1，★ 只把球交给买家。<b>不退款、不还库存。</b>
     * </pre>
     *
     * <p>⚠️ 所以「点了同意，钱立刻就退了」只在仅退款上成立 ——
     * 这是刻意的，不是漏了一步。仅退款的订单「已付款、未发货」，
     * 货在仓库里，没有需要寄回的东西，也没有需要等的事实。
     *
     * <p>★ <b>type 不在请求参数里，也不在 URL 里</b> ——
     * 它由 Service 从库里读出来决定走哪条边。
     * 「哪条边由 URL 决定，绝不能由一个运行时参数决定」这条纪律的另一个方向：
     * <b>能由数据决定的东西，就不要让调用方说出来。</b>
     */
    @PostMapping("/{afterSaleNo}/approve")
    public Result<AfterSaleVO> approve(@PathVariable String afterSaleNo) {
        return Result.success(afterSaleService.approve(afterSaleNo));
    }

    /**
     * 拒绝。
     *
     * <p>{@code POST /api/admin/after-sales/{afterSaleNo}/reject}
     *
     * <pre>
     *   { "rejectReason": "商品已使用，不支持无理由退款" }
     * </pre>
     *
     * <p>★ 「待审核」和「待买家寄回」两种状态都能拒 ——
     * 管理员同意退货之后看了买家发来的照片再反悔，是合理的。
     * <b>但「待卖家收货」不能拒</b>：那时货已经在路上了，
     * 拒绝会让买家陷入「东西寄走了、钱没退、单还被拒了」，
     * 而正确的操作只有一个 —— 确认收到。
     * <b>「货在途」这个事实让「拒绝」不再是一个可选项。</b>
     *
     * <p>★ <b>拒绝不还库存</b>（两种情形下货都还在买家手里）。
     */
    @PostMapping("/{afterSaleNo}/reject")
    public Result<AfterSaleVO> reject(@PathVariable String afterSaleNo,
                                      @Valid @RequestBody AfterSaleRejectDTO dto) {
        return Result.success(afterSaleService.reject(afterSaleNo, dto));
    }

    /**
     * 确认收到退货 → 退款。
     *
     * <p>{@code POST /api/admin/after-sales/{afterSaleNo}/receive}
     *
     * <p>★★ <b>这是整轮里唯一一处「库存归还」和「退货」真正相遇的地方。</b>
     * 货在买家手里待了一路，直到管理员在这里点一下，它才回到仓库 ——
     * 所以归还库存挂在这一条边上，不在「同意」那条上。
     * 挂在「同意」上的话，买家可以既不寄回又拿到退款，
     * 而库存已经加回去了，<b>那件货凭空多了一份可以卖的</b>。
     *
     * <p>★ 它也是并发测试（D 组）打的那个接口：12 个线程同时打它，
     * 必须有且只有一个成功（其余 1002），而且库存<b>恰好</b>加一个 quantity ——
     * 不是两个。幂等性不靠 Java 里的判断，靠
     * {@code WHERE status = 2} 这条条件 UPDATE 的影响行数。
     */
    @PostMapping("/{afterSaleNo}/receive")
    public Result<AfterSaleVO> receive(@PathVariable String afterSaleNo) {
        return Result.success(afterSaleService.receive(afterSaleNo));
    }
}
