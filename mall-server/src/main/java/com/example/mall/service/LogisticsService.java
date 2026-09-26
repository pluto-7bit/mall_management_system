package com.example.mall.service;

import com.example.mall.dto.LogisticsTraceSaveDTO;
import com.example.mall.vo.LogisticsVO;

/**
 * 订单物流：发货时记下的承运商 + 单号，以及管理员手工录入的轨迹节点。
 * ★ 里程碑 18 新增。
 *
 * <h3>★ 四个方法，都是「取某一单的物流」这一个形状</h3>
 *
 * <p>读写各两个（用户端 / 管理端），差别只有一处：
 * <b>用户端多一层会员隔离</b>（{@code member_id} 来自 JWT），
 * 管理端没有。安全边界由 {@code AdminAuthInterceptor} 拦
 * {@code /api/admin/**} 提供，见 {@code OrderAdminMapper} 的类注释。
 *
 * <p>★★ <b>四个方法【都返回 {@link LogisticsVO}】</b>（包括删除）——
 * 这不是偷懒，是本项目立过的那条规矩：
 * <b>「一次操作之后前端马上要用到的数据，就该由这个操作直接返回」。</b>
 * 增删一个节点之后，对话框要的就是刷新后的完整列表；
 * 返回一个空 body 会逼前端再发一次 GET，而那次 GET 失败时
 * 用户会看到一个「删掉了但还显示着」的界面。
 *
 * <h3>★ 什么不在这个接口里</h3>
 *
 * <p>没有「修改节点」—— 一条被改过的轨迹不是轨迹，改错的做法是删了重录。
 * 完整理由见 {@code OrderLogistics} 的类注释。
 */
public interface LogisticsService {

    /**
     * 查<b>我的</b>某一单的物流。{@code GET /api/shop/orders/{orderNo}/logistics}
     *
     * <p>★ 必须先按 {@code (orderNo, memberId)} 查订单，查不到就抛 1003 ——
     * 物流里含<b>收货地址级别的隐私信息</b>（快件到了哪个城市），
     * 不能凭订单号就能读到别人的。
     *
     * <p>★ 错误信息是「订单不存在」而不是「不是你的订单」，
     * 理由见 {@code OrderServiceImpl.requireOwnOrder} 的注释
     * （后者等于确认了这个订单号存在，能被用来枚举订单号）。
     *
     * <p>⚠️ <b>这个读接口【不判断订单状态】</b>，只判断身份。这是刻意的：
     * 它是无副作用的读，而按状态拦会让「订单刚被退款」这类正常情况
     * 变成一句「状态不允许」—— 用户只想看一眼单号而已。
     * 「只有已发货的订单能<b>记</b>物流」那条闸门在 {@link #addTrace} 里。
     */
    LogisticsVO getMyLogistics(String orderNo);

    /**
     * 查<b>任意</b>订单的物流（管理端）。{@code GET /api/admin/orders/{orderNo}/logistics}
     *
     * <p>和 {@link #getMyLogistics} 同形状，只有一处不同：<b>没有会员隔离</b>。
     * 同样是纯读，同样不判断状态。
     */
    LogisticsVO getAdminLogistics(String orderNo);

    /**
     * 新增一个轨迹节点（管理端）。{@code POST /api/admin/orders/{orderNo}/logistics}
     *
     * <h4>★ 只允许给「已发货 / 已完成」的订单记物流</h4>
     *
     * <p>没发货哪来的轨迹。其余状态（待付款、已付款、已取消）报 1002。
     * 已退款（5）的订单也挡在这里 —— 货都退回来了，不该再录发货轨迹。
     *
     * <p>★ 这条判断<b>刻意写在 Service，不写进 SQL 的 WHERE</b>：
     * 它是「这一次动作的前置条件」，而 SQL 的 WHERE 里没有「这一次录入」
     * 那一刻（里程碑 17 立的判据 ②，同售后的时限判定）。
     *
     * <h4>★★ 录到「已签收」会把订单【自动推到已完成】且不可撤销</h4>
     *
     * <p>这是用户拍板要的联动。它<b>不是第二个状态写入者</b> ——
     * 它写的是「已发货 → 已完成」这条边的<b>第二个行动者</b>，
     * 用的是同一个 {@code WHERE status = 2} 闸门。
     * 完整论证见 {@code OrderAdminMapper.markCompletedByNo}。
     *
     * <p>★ 触发条件是 {@code status == LogisticsStatus.SIGNED} 这个<b>码</b>，
     * 不是「说明里有没有『已签收』三个字」—— 后者会在有人写
     * 「已签收失败，改约明天」的那天静默出错。
     *
     * <p>★ <b>删掉这条节点不会把订单退回「已发货」</b>，见 {@link #deleteTrace}。
     *
     * @return 录完之后的完整物流（含新节点），前端直接用它刷新对话框
     */
    LogisticsVO addTrace(String orderNo, LogisticsTraceSaveDTO dto);

    /**
     * 删除一个录错的轨迹节点（管理端）。
     * {@code DELETE /api/admin/orders/{orderNo}/logistics/{traceId}}
     *
     * <p>★ 路径里必须带 {@code orderNo}：Service 先按它拿到 {@code orderId}，
     * 再用 {@code WHERE id = ? AND order_id = ?} 删 ——
     * 这样<b>前端传错 id 也删不到别人的节点</b>。
     * 全库零外键意味着数据库不会帮你挡这个，只能由 WHERE 挡。
     *
     * <h4>★★ 删除【绝不回退】订单状态</h4>
     *
     * <p>删掉一条「已签收」<b>不会</b>把订单退回「已发货」。
     * 这是决定，不是漏了。
     *
     * <p>理由：{@code markCompletedByNo} 和用户端的 {@code markCompleted}
     * 都是<b>单向</b>的（{@code status = 2 → 3} 是单行道，
     * {@code OrderStatus} 的边表里<b>没有任何一条回退边</b>）。
     * 给它配一条回退 SQL 意味着<b>订单状态机第一次有了反向边</b>，
     * 而反向边的后果是「一张订单可以在已发货和已完成之间来回走」——
     * 所有依赖终态的逻辑（售后 7 天窗口、评价资格、
     * {@code markShipped} 的闸门）都要重新论证一遍。
     *
     * <p>★ 管理端的对话框上写明了这一点：
     * <b>「记入『已签收』会把订单标记为已完成，且不可撤销。」</b>——
     * 把风险放在操作者眼前，而不是靠事后补救。
     *
     * @param traceId 节点 id。不存在、或不属于这张订单 → 1003
     *                （两种情况刻意合并，同 {@code requireOwnOrder}）
     * @return 删完之后的完整物流，前端直接用它刷新对话框
     */
    LogisticsVO deleteTrace(String orderNo, Long traceId);
}
