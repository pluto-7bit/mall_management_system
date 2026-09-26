package com.example.mall.controller.shop;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.AfterSaleApplyDTO;
import com.example.mall.dto.AfterSaleQueryDTO;
import com.example.mall.dto.AfterSaleReturnDTO;
import com.example.mall.service.AfterSaleService;
import com.example.mall.vo.AfterSaleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 售后接口（<b>用户端</b>）。★ 里程碑 17 新增。
 *
 * <h3>★ 路径 {@code /api/shop/after-sales} 没有被排除出拦截器</h3>
 *
 * <p>和 {@code /api/shop/orders/**} 一样。<b>售后单是纯私人数据</b>
 * （买了什么、退了多少钱、寄回的快递单号），而且申请和撤销都会改状态 ——
 * 没有任何一个接口该对游客开放。
 *
 * <p>⚠️ 判断依据是 {@code WebMvcConfig} 的排除列表，不是路径的「大前缀」：
 * 同样是 {@code /api/shop/} 开头，{@code /api/shop/products/**}
 * 和 {@code /api/shop/categories} 是给游客浏览的，安全等级完全不同。
 *
 * <h3>★★ 四个接口对应售后状态机上的四条边，而它们的关键区别是「谁按的」</h3>
 *
 * <pre>
 *   POST   /api/shop/after-sales                    T1  申请（无 → 0 待审核）
 *   GET    /api/shop/after-sales                    列表（只能看到自己的）
 *   POST   /api/shop/after-sales/{no}/cancel        T7  撤销（0 → 5）
 *   POST   /api/shop/after-sales/{no}/return        T5  填寄回物流（1 → 2）
 * </pre>
 *
 * <p>★ 后两个是<b>只有买家本人能做</b>的动作，所以 Service 会把
 * JWT 里的 memberId 写进 UPDATE 的 WHERE ——
 * 少了它，任何人拿别人的售后单号就能撤掉别人的申请。
 * <b>售后单号是「会出现在客服聊天记录、分享链接里的标识符，不是密码」。</b>
 *
 * <h3>★ 三个动作接口都返回整个售后单，列表接口返回分页</h3>
 *
 * <p>依据 {@code ShopOrderController} 的类注释里那句话：
 * <b>「一个操作之后前端马上要用到的数据，就该由这个操作直接返回。」</b>
 * 撤销之后那一行要就地变成「已撤销」，填完单号要就地变成「待卖家收货」——
 * 让前端为此再查一次列表，多一次往返，而且那一次请求失败的话
 * 用户会看到「操作成功但页面报错」。
 *
 * <p>对比『申请』接口：它当然也要返回 —— 但返回的是<b>一个列表</b>，
 * 因为整单退一次会建 N 张单。
 *
 * <h3>★ 为什么没有「售后详情」接口</h3>
 *
 * <p>因为售后单的全部字段都在列表项里（{@code AfterSaleVO}），
 * 加一个详情接口就是<b>第二条装配路径</b> ——
 * 将来加字段时漏改一处的经典来源。
 * 而且用户端本来就没有订单详情页，售后详情更没有存在的理由。
 */
@RestController
@RequestMapping("/api/shop/after-sales")
@RequiredArgsConstructor
public class ShopAfterSaleController {

    private final AfterSaleService afterSaleService;

    /**
     * 申请售后。<b>一次可以申请多条明细（整单退）。</b>
     *
     * <p>{@code POST /api/shop/after-sales}
     *
     * <pre>
     *   {
     *     "orderNo": "20260925005831482913",
     *     "orderItemIds": [12, 13],
     *     "type": 1,
     *     "reason": 1,
     *     "description": "买重了"
     *   }
     * </pre>
     *
     * <p>★ <b>请求体里没有金额、没有 memberId、没有 productId</b> ——
     * 三条理由各不相同，完整对照表在 {@code AfterSaleApplyDTO} 的类注释里。
     * 这里只说金额那一条：<b>客户端提供金额 = 客户端决定退多少钱。</b>
     *
     * <p>⚠️ <b>为什么用 {@code POST} 而不是 {@code PUT}？</b>
     * 因为它的语义是「新增一张（或 N 张）售后单」，不是「把某个已有资源改成某个状态」。
     * 而且它<b>不是幂等的</b> —— 换个明细再发一次就该产生新的售后单。
     * 那重复提交怎么办？靠数据库：同一条明细上
     * {@code uk_order_item_active} 唯一索引只让一张「进行中」的单存在，
     * 第二次提交会拿到 1012。这一条比下单的幂等键更硬 ——
     * <b>下单可以重复（用户确实想买两次），而「退同一件东西」不能重复。</b>
     */
    @PostMapping
    public Result<List<AfterSaleVO>> apply(@Valid @RequestBody AfterSaleApplyDTO dto) {
        return Result.success(afterSaleService.apply(dto));
    }

    /**
     * 我的售后列表。
     *
     * <p>{@code GET /api/shop/after-sales?status=0&pageNum=1&pageSize=10}
     *
     * <p>★ {@code status} 不传就是「全部」。<b>不要为了「全部」而传一个 0</b> ——
     * 0 是一个真实状态（待审核），用它表示「全部」的话，
     * 「只看待审核」这个功能就永远做不出来，
     * 而且症状是「筛选待审核时列出了全部售后」这种不报错的错。
     */
    @GetMapping
    public Result<PageResult<AfterSaleVO>> page(AfterSaleQueryDTO query) {
        return Result.success(afterSaleService.pageMyAfterSales(query));
    }

    /**
     * 撤销申请。
     *
     * <p>{@code POST /api/shop/after-sales/{afterSaleNo}/cancel}
     *
     * <p>★ <b>没有请求体</b>：撤销不需要解释（和「同意」不需要参数是同一条）。
     * 对比「拒绝」接口的 {@code rejectReason} 是必填的 ——
     * 那不是不对称，是<b>只有需要说点什么的时候才有参数</b>。
     *
     * <p>★ 只允许撤销「待审核」的单。管理员同意退货之后就撤不了了 ——
     * 那时候货已经在往回寄的路上，`撤销`是什么意思？东西退不退？钱退不退？
     * 每个答案都需要一段新的规则。见 {@code AfterSaleMapper.markCancelledByMember}。
     */
    @PostMapping("/{afterSaleNo}/cancel")
    public Result<AfterSaleVO> cancel(@PathVariable String afterSaleNo) {
        return Result.success(afterSaleService.cancel(afterSaleNo));
    }

    /**
     * 填写寄回物流。
     *
     * <p>{@code POST /api/shop/after-sales/{afterSaleNo}/return}
     *
     * <pre>
     *   { "returnCompany": "顺丰", "returnTracking": "SF1234567890" }
     * </pre>
     *
     * <p>★ 只有「待买家寄回」的单能填，而且<b>只有买家本人</b>能填
     * （管理员不知道买家寄了什么）。
     *
     * <p>⚠️ 这两个字段都是<b>自由文本</b>，不是码表 —— 论证见
     * {@code AfterSaleReturnDTO}：快递公司天生就是外部世界的标识符，
     * 给它做码表只会得到一张永远在补的字典，然后漏掉用户用的那一家。
     */
    @PostMapping("/{afterSaleNo}/return")
    public Result<AfterSaleVO> submitReturn(@PathVariable String afterSaleNo,
                                            @Valid @RequestBody AfterSaleReturnDTO dto) {
        return Result.success(afterSaleService.submitReturn(afterSaleNo, dto));
    }
}
