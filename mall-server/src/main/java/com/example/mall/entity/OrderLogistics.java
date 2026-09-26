package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单物流轨迹节点，对应表 {@code order_logistics}。★ 里程碑 18 新增。
 *
 * <h3>★★ 它是「外部世界发生过的事」的记录，不是「系统状态」的一部分</h3>
 *
 * <p>这是本轮的中心张力，也是这个类和 {@code Order} 的根本区别：
 *
 * <pre>
 *   物流轨迹是外部世界发生的事，订单状态是系统内部的判断。
 *   前者可以独立于后者存在，后者绝不能被前者「猜」出来。
 * </pre>
 *
 * <p>这个不对称决定了三件事：
 *
 * <p><b>① 它是只增不减的事实记录</b> —— 一条节点被录进来之后，
 * 唯一诚实的修改方式是删掉重录，不是「编辑成新的值」。
 * 一条被改过的轨迹不是轨迹：它的 {@code createTime} 会说谎。
 * 所以本轮<b>不提供编辑接口</b>，只有新增和删除。
 *
 * <p><b>② 它不存 {@code orderNo} 快照</b>（对比 {@code AfterSale.orderNo}）。
 * 判据是里程碑 16 立的第 ③ 条：<b>有读者才加列。</b>
 * 轨迹只在「查某一单的物流」时被读，那时调用方手里已经有 {@code orderId} 了 ——
 * 那个 {@code orderNo} 永远没有人读。零读者的列不加。
 *
 * <p><b>③ 承运商和单号不在这里</b>，它们在 {@code orders} 上。
 * 那是「这一单怎么发出去的」，是<b>订单级</b>事实（单包裹），不是节点级。
 * 放在这张表上意味着每条节点抄一份承运商，改一次要改 N 行 ——
 * 而它们本来就不可能不一致。
 *
 * <h3>★ 排序键是 {@code (trace_time, id)}，不是 {@code id}</h3>
 *
 * <p><b>{@code traceTime} 和 {@code createTime} 是两个不同的时刻，
 * 这不是冗余</b>：
 *
 * <pre>
 *   trace_time  = 这件事【发生】的时刻（管理员可以填过去的时刻 = 补录）
 *   create_time = 这一条是什么时候【录进系统】的
 * </pre>
 *
 * <p>而<b>补录是这个功能的默认用法</b>（本轮不做快递公司 API 对接，
 * 手工录入是轨迹唯一的来源）：管理员白天忙，晚上把一天的发货节点
 * 一次性补录进去 —— 于是<b>同一张订单的节点，{@code traceTime} 的顺序
 * 和 {@code id} 的顺序相反</b>。
 *
 * <p>所以两端一律按 {@code ORDER BY trace_time DESC, id DESC} 排（最新在上）。
 * 按 {@code id} 排会把<b>录入顺序当成发生顺序</b>，
 * 症状是补录之后时间线倒过来，而页面、代码、控制台<b>全都正常</b>。
 *
 * <p>⚠️ 那个 {@code id DESC} 是<b>必需的、不是装饰</b>：
 * 两个节点被填了完全相同的 {@code traceTime}（比如都填 09:00）时，
 * {@code trace_time} 单独排不出先后，MySQL 返回的顺序<b>不确定</b>，
 * 而且每次查询都可能不一样 —— 断言会随机红，然后被人用 {@code DISTINCT} 掩盖。
 * 同里程碑 17「同秒创建的两张单要取 {@code MAX(id)}」是同一个坑。
 */
@Data
public class OrderLogistics {

    private Long id;

    /** 所属订单 id。故意不加外键 —— 全库零外键是既定约定 */
    private Long orderId;

    /** 节点状态码，取值见 {@code LogisticsStatus} */
    private Integer status;

    /**
     * 这一节点的说明，管理员手写，如「快件已到达【杭州转运中心】」。
     *
     * <p>{@code NOT NULL} —— 一个只有状态码、没有说明的节点对用户没有意义
     * （「运输中」三个字谁都会看，用户想知道的是<b>到哪了</b>）。
     */
    private String description;

    /** ★ 这一节点【发生】的时刻（可填过去的时刻 = 补录）。见类注释 */
    private LocalDateTime traceTime;

    /** 录入时间，由数据库 DEFAULT CURRENT_TIMESTAMP 填 */
    private LocalDateTime createTime;
}
