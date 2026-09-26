package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.LogisticsStatus;
import com.example.mall.common.OrderStatus;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.dto.LogisticsTraceSaveDTO;
import com.example.mall.entity.Order;
import com.example.mall.entity.OrderLogistics;
import com.example.mall.mapper.OrderAdminMapper;
import com.example.mall.mapper.OrderLogisticsMapper;
import com.example.mall.mapper.OrderMapper;
import com.example.mall.service.LogisticsService;
import com.example.mall.vo.AdminOrderVO;
import com.example.mall.vo.LogisticsTraceVO;
import com.example.mall.vo.LogisticsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物流：承运商 + 单号（发货时写在 {@code orders} 上）+ 手工录入的轨迹节点。
 * ★ 里程碑 18 新增。
 *
 * <h3>★★ 本轮的中心张力，在这个类里体现得最清楚</h3>
 *
 * <pre>
 *   物流轨迹是【外部世界发生的事】，订单状态是【系统内部的判断】。
 *   前者可以独立于后者存在，后者绝不能被前者「猜」出来。
 * </pre>
 *
 * <p>这个不对称决定了本类的三处写法，每一处都有一个「更对称、更直觉」
 * 但错误的替代做法：
 *
 * <table border="1">
 *   <caption>三处不对称</caption>
 *   <tr><th>做法</th><th>更直觉的写法</th><th>为什么不行</th></tr>
 *   <tr>
 *     <td>「已签收」触发完成，但<b>删除不回退</b></td>
 *     <td>对称地写一条回退 SQL</td>
 *     <td>状态机第一次有反向边，见 {@link #deleteTrace}</td>
 *   </tr>
 *   <tr>
 *     <td>触发条件是 {@code status == SIGNED} 这个码</td>
 *     <td>在说明里找「已签收」三个字</td>
 *     <td>拿中文猜状态，见 {@link #addTrace}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code markCompletedByNo} 的返回值<b>不检查</b></td>
 *     <td>检查 affected = 0 就报错</td>
 *     <td>0 的正常含义是「已经完成过了」，检查它会回滚掉刚写的节点，见 {@link #addTrace}</td>
 *   </tr>
 * </table>
 *
 * <h3>★ 哪些方法需要事务</h3>
 *
 * <p>判据逐字同售后模块：<b>「有没有多个必须一起成败的写」。</b>
 * <ul>
 *   <li>{@link #addTrace} 有<b>两个</b>写（INSERT 节点 + 可能 UPDATE 订单）→ <b>必须事务</b></li>
 *   <li>{@link #deleteTrace} 只有<b>一个</b>写 → <b>不套事务</b>。
 *       ⚠️ 不要为了「和兄弟方法看起来一致」给它套一个空事务 ——
 *       没有理由的事务和没有理由的锁一样，是负债
 *       （{@code OrderServiceImpl.ship} 立过的同一条规矩）</li>
 *   <li>两个 GET 纯读 → 不需要</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsServiceImpl implements LogisticsService {

    private final OrderLogisticsMapper orderLogisticsMapper;
    private final OrderMapper orderMapper;
    private final OrderAdminMapper orderAdminMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public LogisticsVO getMyLogistics(String orderNo) {
        // ★ 会员 id 必须来自 JWT，绝不能来自请求参数 —— 否则就等于「可以读别人的物流」。
        //   同 OrderServiceImpl.currentMemberId 的注释。
        Long memberId = UserContext.require().id();

        Order order = orderMapper.selectByOrderNoAndMember(orderNo, memberId);
        if (order == null) {
            // ★ 信息是「订单不存在」而不是「不是你的订单」：
            //   后者等于确认了这个订单号存在，能被用来枚举订单号。
            //   完整论证见 OrderServiceImpl.requireOwnOrder。
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return toVO(order);
    }

    @Override
    public LogisticsVO getAdminLogistics(String orderNo) {
        return toVO(requireOrderByNo(orderNo));
    }

    /**
     * 新增一个轨迹节点 —— 本轮唯一一个需要事务的写。
     *
     * <h4>★ 顺序：先写【事实】，后写【派生】</h4>
     *
     * <p>有事务时两者同生共死，顺序不影响正确性 —— 但它是一条可读性纪律：
     * 「外部世界发生了什么」先落库，「系统对它的解读」后落库。
     * 而且<b>将来若有人把联动挪到事务外去做补偿，顺序就真的重要了</b>。
     */
    @Override
    public LogisticsVO addTrace(String orderNo, LogisticsTraceSaveDTO dto) {
        // ---- 1. 白名单。★ DTO 的 @NotNull 只管「填了没有」，不管「填的是不是 1~5」。
        //          形状逐字同 AfterSaleServiceImpl.apply 里那两行。
        if (!LogisticsStatus.isValid(dto.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "节点状态不正确");
        }

        AdminOrderVO order = requireOrderByNo(orderNo);

        // ---- 2. ★ 判据 ②（里程碑 17 立的）：「这是【一次动作】的前置条件」
        //          → 判在 Service，不写进 SQL 的 WHERE。
        //          SQL 的 WHERE 里没有「这一次录入」那一刻。
        //       ★ 已退款（5）也挡在这里：货都退回来了，不该再录发货轨迹。
        if (order.getStatus() != OrderStatus.SHIPPED
                && order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前是「" + OrderStatus.text(order.getStatus())
                            + "」，只有已发货的订单能记录物流");
        }

        return transactionTemplate.execute(st -> {
            // ---- 3. 先写【事实】
            OrderLogistics node = new OrderLogistics();
            node.setOrderId(order.getId());
            node.setStatus(dto.getStatus());
            node.setDescription(dto.getDescription().trim());
            node.setTraceTime(dto.getTraceTime());
            orderLogisticsMapper.insert(node);

            // ---- 4. 后写【派生】。★ 用户拍板：录到「已签收」就把订单推到已完成。
            //
            //   ★ 这不是第二个写入者 —— markCompletedByNo 用的是
            //     「已发货 → 已完成」这条边【同一个】WHERE status = 2 闸门。
            //     见 OrderAdminMapper.markCompletedByNo。
            //
            //   ★ 触发条件是【码】，不是描述里的中文。理由见 LogisticsStatus 的类注释。
            //
            //   ★★ 绝不检查 affected：affected = 0 的正常含义是
            //     「订单已经不是已发货了」（买家自己已经确认收货、或被退款），
            //     那是正常情况不是错误。检查它 → 假失败 → 抛异常 →
            //     【事务回滚 → 连刚写的轨迹节点也没了】。
            //     判据逐字同售后 T8 的 markOrderRefundedIfAllRefunded。
            if (dto.getStatus() == LogisticsStatus.SIGNED) {
                orderAdminMapper.markCompletedByNo(orderNo);
                log.info("物流签收自动完成订单: orderNo={}", orderNo);
            }

            log.info("新增物流节点: orderNo={}, status={}",
                    orderNo, LogisticsStatus.text(dto.getStatus()));

            // ★ 重查一次：订单状态可能刚被上面那一步改了，
            //   而前端拿这个响应直接刷新整块界面。
            return toVO(requireOrderByNo(orderNo));
        });
    }

    /**
     * 删除一个录错的轨迹节点。★ 只有<b>一个</b>写，所以不套事务。
     *
     * <h4>★★ 删除【绝不回退】订单状态 —— 本类最容易被改错的地方</h4>
     *
     * <p>看到 {@link #addTrace} 里「签收 → 自动完成」，很容易想给这里
     * 对称地补一条「删掉签收节点 → 退回已发货」。<b>别加。</b>
     *
     * <p>理由有三层，一层比一层具体：
     *
     * <p><b>① 方向错了。</b>删掉节点是「这条记录我不该录」，
     * 不是「货没送到」。货大概率真的送到了 —— 用户都签收过了。
     * 回退等于<b>拿一次数据订正去推翻一个已经发生过的事实</b>。
     *
     * <p><b>② 订单状态机没有反向边，一条都没有。</b>
     * {@code OrderStatus} 的边表里全部是单向的。给它配一条回退 SQL，
     * 就是<b>第一次引入反向边</b>，后果是「一张订单可以在已发货和
     * 已完成之间来回走」。
     *
     * <p><b>③ 而反向边会让三个已论证过的东西失效：</b>
     * 售后 7 天窗口（{@code complete_time} 是起点，回退要不要清掉它？）、
     * 评价资格、{@code markShipped} 的 {@code WHERE status = 1} 闸门。
     * 每一个都要重新论证一遍。
     *
     * <p>★ 所以补救办法是<b>把风险提前说清楚</b>：
     * 管理端对话框上写着「记入『已签收』会把订单标记为已完成，且不可撤销」。
     */
    @Override
    public LogisticsVO deleteTrace(String orderNo, Long traceId) {
        AdminOrderVO order = requireOrderByNo(orderNo);

        // ★ orderId 同时进 WHERE：前端传错 id 也删不到别人的节点。
        //   全库零外键，数据库不会帮你挡这个。
        int affected = orderLogisticsMapper.deleteByIdAndOrderId(traceId, order.getId());
        if (affected == 0) {
            // ★ 「不存在」和「不属于这张订单」刻意合并成同一个 1003 ——
            //   分开报等于确认了「这个 id 是存在的，只是不属于这一单」。
            throw new BusinessException(ResultCode.NOT_FOUND, "物流节点不存在");
        }

        log.info("删除物流节点: orderNo={}, traceId={}", orderNo, traceId);

        // ★ 注意这里【没有】任何 markXxx 调用 —— 见方法注释。
        return toVO(requireOrderByNo(orderNo));
    }

    /**
     * 按订单号查订单（管理端视角，<b>不带会员条件</b>）。
     *
     * <p>复用的是 {@code OrderAdminMapper.selectAdminByOrderNo} ——
     * 它返回 {@code AdminOrderVO}，正是这里需要的形状
     * （有 id、有 status、有 logisticsCompany / trackingNo / shipTime）。
     * 没有为了这个类再写一条查询。
     */
    private AdminOrderVO requireOrderByNo(String orderNo) {
        AdminOrderVO order = orderAdminMapper.selectAdminByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    /**
     * 把「订单上的物流三列」+「轨迹节点」组装成 {@link LogisticsVO}。
     *
     * <p>★ {@code traces} 永远是<b>空列表</b>而不是 null：
     * 刚发货的订单就是这个样子（单号有了、还一条轨迹都没录），
     * 前端要显示「暂无物流信息」而不是报错。
     *
     * <p>★ 顺序<b>原样来自 SQL</b>（{@code trace_time DESC, id DESC}），
     * 这里<b>不重排</b> —— 重排就是第二个定义者，
     * 而「谁在上谁在下」只该有一个地方说了算。
     */
    private LogisticsVO toVO(Long orderId, String logisticsCompany,
                             String trackingNo, LocalDateTime shipTime) {
        LogisticsVO vo = new LogisticsVO();
        vo.setLogisticsCompany(logisticsCompany);
        vo.setTrackingNo(trackingNo);
        vo.setShipTime(shipTime);

        List<LogisticsTraceVO> traces = orderLogisticsMapper
                .selectByOrderId(orderId)
                .stream()
                .map(this::toTraceVO)
                .toList();
        vo.setTraces(traces);

        return vo;
    }

    /**
     * 两个重载，因为它们来自两条<b>不同</b>的查询、却是同一件事。
     *
     * <p>用户端走 {@code OrderMapper.selectByOrderNoAndMember}（带会员隔离）
     * 拿回实体 {@code Order}；管理端走 {@code OrderAdminMapper.selectAdminByOrderNo}
     * 拿回 {@code AdminOrderVO}。两个类型没有共同的父类
     * （{@code Order} 是实体，{@code AdminOrderVO} 继承 {@code OrderVO}），
     * 而这里需要的 4 个字段两边都有。
     *
     * <p>★ <b>刻意不用一个「按订单号查」的公共方法把两边统一起来</b> ——
     * 那会把用户端那条的 {@code member_id} 条件抹掉，
     * 也就是把「读自己的物流」变成「读任何人的物流」。
     * <b>两个类型在这里的不一致，恰好是安全边界存在的证据。</b>
     */
    private LogisticsVO toVO(Order order) {
        return toVO(order.getId(), order.getLogisticsCompany(),
                order.getTrackingNo(), order.getShipTime());
    }

    /** 管理端那条路。见上面那个重载的注释 */
    private LogisticsVO toVO(AdminOrderVO order) {
        return toVO(order.getId(), order.getLogisticsCompany(),
                order.getTrackingNo(), order.getShipTime());
    }

    /**
     * 节点实体 → VO。
     *
     * <p>★ 只丢掉了 {@code orderId} 这一个字段（前端已有 orderNo，
     * 每条节点再带一次没人读），其余全部保留 ——
     * {@code createTime} 虽然页面上只以很小的一行显示，
     * 但它是管理员排查「这条是什么时候录的」的唯一依据。
     */
    private LogisticsTraceVO toTraceVO(OrderLogistics node) {
        LogisticsTraceVO vo = new LogisticsTraceVO();
        vo.setId(node.getId());
        vo.setStatus(node.getStatus());
        vo.setDescription(node.getDescription());
        vo.setTraceTime(node.getTraceTime());
        vo.setCreateTime(node.getCreateTime());
        return vo;
    }
}
