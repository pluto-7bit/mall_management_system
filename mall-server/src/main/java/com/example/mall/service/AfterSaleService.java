package com.example.mall.service;

import com.example.mall.common.PageResult;
import com.example.mall.dto.AdminAfterSaleQueryDTO;
import com.example.mall.dto.AfterSaleApplyDTO;
import com.example.mall.dto.AfterSaleQueryDTO;
import com.example.mall.dto.AfterSaleRejectDTO;
import com.example.mall.dto.AfterSaleReturnDTO;
import com.example.mall.vo.AdminAfterSaleVO;
import com.example.mall.vo.AfterSaleVO;

import java.util.List;

/**
 * 售后业务的接口。★ 里程碑 17 新增。
 *
 * <h3>★★ 这八个方法分成三类，而分类的依据是「有几个写」</h3>
 *
 * <p>本项目对「要不要事务」有一条已经立过的判据，写在
 * {@code OrderServiceImpl.ship} 的注释里：
 * <b>「要不要事务，看有没有多个必须一起成败的写」</b>。
 * 售后这边八条路径正好落成三档：
 *
 * <table border="1">
 *   <caption>八个方法的写数量</caption>
 *   <tr><th>档</th><th>方法</th><th>写几个</th><th>事务</th></tr>
 *   <tr><td>建单</td>
 *       <td>{@link #apply}</td>
 *       <td>N（一次最多建 N 张单）</td>
 *       <td>★ 要 —— N 个写必须全成或全败</td></tr>
 *   <tr><td>退款</td>
 *       <td>{@link #approve}（仅退款分支）、{@link #receive}</td>
 *       <td>3（抢边 + 还库存 + 推订单终态）</td>
 *       <td>★ 要 —— 「退款成功、库存已还」必须一起成败</td></tr>
 *   <tr><td>纯状态迁移</td>
 *       <td>{@link #cancel}、{@link #submitReturn}、
 *           {@link #approve}（退货退款分支）、{@link #reject}</td>
 *       <td>1</td>
 *       <td>不要 —— 单条 SQL 本身就是原子的</td></tr>
 * </table>
 *
 * <p>⚠️ {@code approve} 同时出现在两档里，因为它的两个分支是
 * <b>两个不同的业务操作共用一个 URL</b>（仅退款 = 同意即退款；
 * 退货退款 = 同意后等买家寄回）。这不是含糊，是状态机本来的形状 ——
 * 见 {@code AfterSaleStatus} 里「同意退货 和 确认收到退货 必须是两个状态」。
 *
 * <h3>★ 所有方法都不收 memberId</h3>
 *
 * <p>理由和 {@code OrderService} 一字不差：身份从 {@code UserContext}（JWT）来，
 * 而<b>管理端的三个动作（approve / reject / receive）根本没有会员可言</b> ——
 * 管理员处理的就是别人的单。把 memberId 放进签名，会让人以为
 * 「管理员也能传一个会员 id 来操作」，而那是没有意义的东西。
 *
 * <p>★ 安全边界因此落在两个不同的地方，这一点值得看清楚：
 * <pre>
 *   用户端的三个方法（apply / cancel / submitReturn）
 *       →  Service 内部从 UserContext 取 memberId，写进 SQL 的 WHERE
 *   管理端的四个方法（pageForAdmin / approve / reject / receive）
 *       →  安全边界在【路径前缀】/api/admin/**，被 AdminAuthInterceptor 整体拦着
 * </pre>
 * <b>「同一层里两种边界」不是不一致，是因为这两类接口的授权模型本来就不同</b>
 * （同 {@code OrderService} 里 shop / admin 两组方法的注释）。
 */
public interface AfterSaleService {

    // ======================================================================
    //  用户端
    // ======================================================================

    /**
     * 申请售后。<b>一次可以申请多条明细（整单退）</b>。
     *
     * <p>★ 这 N 张售后单在<b>一个事务里</b>创建，全成或全败。
     * 详见 {@code AfterSaleApplyDTO.orderItemIds} 的论证：
     * 换成 N 次请求的话，用户勾了 3 行、第 2 次失败了，
     * 就会得到「退了 1 行、剩下的不知道退没退」的状态。
     *
     * <p>★ 它<b>只建单，不退款、不动库存</b>。退款发生在管理员同意
     * （仅退款）或确认收到退货（退货退款）的那一刻 —— 见
     * {@code AfterSaleMapper} 的 T2 / T6。
     *
     * @return 刚创建的那几张售后单（最新的样子）。
     *         ★ 「一个操作之后前端马上要用到的数据，就该由这个操作直接返回」
     *         （{@code ShopOrderController} 的类注释里那句话），
     *         所以这里返回 VO 而不是 id 列表 —— 申请完那几行要就地变成
     *         「售后处理中」，前端不该为了拿这个再查一次列表
     * @throws com.example.mall.common.BusinessException
     *         1003 订单不存在 / 不是你的 / 明细不属于这笔订单；
     *         1002 订单状态不支持这种售后类型；
     *         1012 该明细已有进行中的售后单；
     *         1013 该明细已退款；
     *         1014 已超过售后申请期限
     */
    List<AfterSaleVO> apply(AfterSaleApplyDTO dto);

    /**
     * 我的售后列表（分页，可按状态筛选）。
     *
     * <p>★ 只返回当前登录会员的售后单 —— {@code memberId} 从 JWT 取，
     * 写进 SQL 的 WHERE，不是筛选项。
     */
    PageResult<AfterSaleVO> pageMyAfterSales(AfterSaleQueryDTO query);

    /**
     * 撤销申请（<b>T7</b>：0 待审核 → 5 已撤销）。
     *
     * <p>★ 它<b>永不接触库存</b>，这一点是承重的：
     * 它让「售后走完一整条流程而库存一个数都没变」有了一个真实的对照组
     * （见 {@code AfterSaleMapper.markCancelledByMember}）。
     *
     * @throws com.example.mall.common.BusinessException
     *         1003 售后单不存在 / 不是你的（<b>刻意合并，理由见方法注释</b>）；
     *         1002 状态不是「待审核」
     */
    AfterSaleVO cancel(String afterSaleNo);

    /**
     * 买家填写寄回物流（<b>T5</b>：1 待买家寄回 → 2 待卖家收货）。
     *
     * <p>★ 它<b>不退款、不还库存</b>。这一条边唯一的效果是
     * 把「球」从买家手里传给卖家 —— 而库存要等到管理员确认收到（T6）才归还。
     *
     * @throws com.example.mall.common.BusinessException
     *         1003 售后单不存在 / 不是你的；1002 状态不是「待买家寄回」
     */
    AfterSaleVO submitReturn(String afterSaleNo, AfterSaleReturnDTO dto);

    // ======================================================================
    //  管理端
    // ======================================================================

    /**
     * 全部会员的售后列表（分页，可按状态 / 售后单号 / 会员 / 类型筛）。
     *
     * <p>★ 它<b>不</b>带会员条件，安全边界在 {@code /api/admin/**} 的拦截器上。
     */
    PageResult<AdminAfterSaleVO> pageForAdmin(AdminAfterSaleQueryDTO query);

    /**
     * 同意（<b>两个分支，一条 URL</b>）。
     *
     * <pre>
     *   仅退款   （0 → 3）  ★ 同意即退款：退款 + 归还库存 + 可能推订单终态
     *   退货退款 （0 → 1）      不退款、不还库存，只把球交给买家
     * </pre>
     *
     * <p>★★ <b>这两条边的差别正是本轮最贵的那个决定</b>：
     * 「同意退货」和「确认收到退货」必须是两个状态，
     * 否则管理员能在货还在买家手里时就点「确认收到」——
     * <b>钱退了、货没回来，而且没有任何一层会报错</b>。
     *
     * @throws com.example.mall.common.BusinessException
     *         1003 售后单不存在；1002 状态不是「待审核」
     */
    AfterSaleVO approve(String afterSaleNo);

    /**
     * 拒绝（<b>T4</b>：0 待审核 <b>或</b> 1 待买家寄回 → 4 已拒绝）。
     *
     * <p>★ 理由必填，论证见 {@code AfterSaleRejectDTO}：
     * 「把决策逼成可以说清楚的决策」。
     *
     * @throws com.example.mall.common.BusinessException
     *         1003 售后单不存在；1002 状态不是 0 或 1
     */
    AfterSaleVO reject(String afterSaleNo, AfterSaleRejectDTO dto);

    /**
     * 确认收到退货 → 退款（<b>T6</b>：2 待卖家收货 → 3 退款完成）。
     *
     * <p>★★ <b>这是整轮里唯一一处「库存归还」和「退货」真正相遇的地方。</b>
     * 货在买家手里待了一路，直到管理员确认收到，它才回到仓库 ——
     * 所以归还库存挂在这一条边上，不在「同意」那条上。
     *
     * <p>★ 它是本轮并发测试（D 组）打的那个接口：12 个线程同时打它，
     * 必须有且只有一个成功，库存必须<b>恰好</b>加一个 quantity。
     *
     * @throws com.example.mall.common.BusinessException
     *         1003 售后单不存在；1002 状态不是「待卖家收货」
     */
    AfterSaleVO receive(String afterSaleNo);
}
