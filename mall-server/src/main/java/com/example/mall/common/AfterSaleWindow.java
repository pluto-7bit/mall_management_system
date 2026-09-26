package com.example.mall.common;

import java.time.LocalDateTime;

/**
 * 售后申请时限（★ 里程碑 17）。
 *
 * <h3>★★ 这个类为什么存在 —— 「两处实现」这个老毛病，这回没法用断言兜住</h3>
 *
 * <p>时限这条规则有<b>两个地方</b>要用它，而且它们隔着一个 HTTP 往返：
 * <pre>
 *   订单列表 / 详情（OrderServiceImpl.toVO / attachItems）
 *       →  把 afterSaleDeadline 一起返回，前端拿它决定
 *          「申请售后」那个按钮显不显示
 *
 *   真正申请（AfterSaleServiceImpl.apply）
 *       →  服务端在提交的那一刻再判一次
 * </pre>
 *
 * <p>前端那一次是<b>体验</b>，服务端这一次才是<b>不变量</b>。这没问题。
 * 问题在于：<b>如果两边的算法不一样，症状是「按钮显示着、点了说超期」</b>
 * （或者反过来：按钮不见了，但接口其实允许）。
 *
 * <p>本项目对付「同一事实两份实现」的常规武器是<b>配一条断言</b>
 * （见 {@code active_token} 那两条、{@code orders.status = 5} 那一条）。
 * <b>但这一条配不出断言</b>：它依赖「现在几点」，而两个实现被调用时
 * 差着几毫秒到几秒 —— 一条断言只能问「此刻两边算得一样吗」，
 * 那恰恰是它们唯一一定一样的地方。
 *
 * <p>所以只剩一条路：<b>让「两处实现」变成「一处实现、两处调用」。</b>
 * 判据和 {@code StockRestoreService} 是同一条（两个以上调用者 +
 * 写错了不会报错），只是那一个是 Bean（要写库），这一个是纯函数
 * （输入两个值、输出两个值），所以它是静态工具而不是接口。
 *
 * <h3>★ 落在 {@code common} 而不是 {@code util}</h3>
 *
 * <p>因为它是一条<b>业务规则</b>，不是技术工具 ——
 * {@code util} 里那两个是号码生成器（技术细节：多少位、前缀是什么），
 * 而这里装的是「起点是什么」「边界往哪边靠」这两个决定。
 * 同类的还有 {@code PayMethod}、{@code OrderStatus}。
 */
public final class AfterSaleWindow {

    private AfterSaleWindow() {
    }

    /**
     * 算一笔订单的售后申请截止时刻 —— <b>只有「已完成」的订单才算，其他状态返回 null。</b>
     *
     * <h4>★★ 「已付款 / 已发货」的订单为什么不受时限约束？这不是疏漏，是唯一可能的选择</h4>
     *
     * <p>时限的起点是「确认收货」（{@code orders.complete_time}），
     * 而未确认收货的订单<b>根本没有这个起点</b> ——
     * {@code complete_time} 是 NULL，而 NULL 和任何数比较都是 NULL，不是真。
     * 本项目没有「发货后 N 天自动确认收货」的定时任务，
     * 所以这个起点永远不会自己出现。
     *
     * <p>业务上也是对的：<b>钱在商家手里、货还在路上，
     * 不该因为「等太久」剥夺用户退款的权利</b> ——
     * 「多久没到货」是商家的责任，不是用户的。
     *
     * <pre>
     *   status = 1 (已付款，未发货)  →  complete_time 是 NULL  →  不受时限约束
     *   status = 2 (已发货，未收货)  →  complete_time 是 NULL  →  不受时限约束
     *   status = 3 (已完成)          →  起点 = complete_time  →  受 N 天约束
     *   status ∉ {1,2,3}（待付款/已取消/已退款）  →  根本不能申请售后，谈不上时限
     * </pre>
     *
     * <p>★ 最后一个 {@code completeTime == null} 的判断<b>不是防御「不可能的事」</b>：
     * {@code complete_time} 是<b>可空列</b>，而「可空列一定会有一条路径是 null」
     * 是本项目反复验证过的经验（里程碑 15 的 {@code sku_id} 就是）。
     * 返回 null 在这里是<b>正确的降级</b>：两边都判「不受时限约束」，
     * 也就是放到「用户还能继续用」的那一侧。
     *
     * <h4>★ 为什么参数是 status + completeTime，而不是整个 Order</h4>
     *
     * <p>因为调用方有两种：{@code OrderServiceImpl.toVO} 手上是实体，
     * 而 {@code attachItems} 手上是<b>已经转好的 VO</b>（分页查询的结果
     * 本来就是 VO，那里根本没有实体）。只取这两个字段当参数，
     * 两种调用方就能共用同一份实现 —— 连同 {@code AfterSaleServiceImpl}
     * 手上的 {@code Order} 实体，一共三种，全部落得下。
     *
     * <p>★ 这同时说明它确实是个<b>纯派生值</b>：算它只需要两个输入，
     * 和订单的其余部分没有任何关系。
     *
     * @param status       订单状态，取值见 {@link OrderStatus}
     * @param completeTime 订单的确认收货时间，未确认收货时为 null
     * @param days         时限天数（{@code mall.order.after-sale-window-days}）。
     *                     ★ 做成参数而不是在这里读配置：那样这个类就要变成
     *                     Spring Bean，而它是一条纯规则，越简单越不容易错
     * @return 截止时刻；不受时限约束时返回 <b>null</b>
     */
    public static LocalDateTime deadlineOf(Integer status, LocalDateTime completeTime, int days) {
        // ★ 用 equals 而不是 == 比 Integer：status 可能为 null
        //   （装配路径上有「对象刚 new 出来还没赋值」的情况）。
        if (!Integer.valueOf(OrderStatus.COMPLETED).equals(status)) {
            return null;
        }
        if (completeTime == null) {
            return null;
        }
        return completeTime.plusDays(days);
    }

    /**
     * 这个截止时刻过了吗？<b>★ 边界是一个决定：恰好等于 deadline 算「还在期限内」</b>
     * （也就是只有 {@code now > deadline} 才算超期，宽以待人）。
     *
     * <p>和 {@code OrderServiceImpl.freightOf} 里那句「恰好等于门槛时免运费」
     * 是同一类决定 —— <b>边界值往哪边靠是一个要写下来的决定，
     * 不是一个可以随手写 {@code >=} 的地方。</b>
     *
     * <p>★ <b>边界只在这一个方法里。</b>写两遍（一边 {@code >}、一边 {@code >=}）
     * 的症状是「期限最后那一毫秒，按钮显示着、点了说超期」。
     *
     * <p>★ 为什么 {@code now} 要当参数传进来，而不是在里面调
     * {@code LocalDateTime.now()}：那样这个方法就有了一个<b>看不见的输入</b>，
     * 而且「两个调用方各看自己那块表」这件事会被藏起来。
     * 传进来之后，它是个纯函数 —— 给定输入必然给定输出。
     *
     * @param deadline {@link #deadlineOf} 算出来的时刻，<b>可以是 null</b>
     *                 （null = 不受时限约束 = 永远不算超期）
     */
    public static boolean isExpired(LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) {
            return false;
        }
        return now.compareTo(deadline) > 0;
    }
}
