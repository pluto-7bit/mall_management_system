package com.example.mall.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一张订单的物流全貌：怎么发出去的 + 走过哪些节点。★ 里程碑 18 新增。
 *
 * <h3>★★ 为什么是【一个】接口，而不是「查单号」+「查轨迹」两个</h3>
 *
 * <p>因为这三样东西（承运商、单号、轨迹）<b>正是那个弹窗要显示的全部内容</b>。
 * 拆成两个请求意味着弹窗要等两次，而且中间那次失败会留下一个
 * <b>半空的弹窗</b> —— 上面显示着「顺丰 SF123」，下面是空的，
 * 用户以为「还没更新」，实际是第二个请求失败了。
 *
 * <p>★ 这就是里程碑 10 给 {@code ship} 立的那条规矩的反向应用：
 * <b>「一次操作之后前端马上要用到的数据，就该由这一个请求返回」</b>。
 * 这里是「一次打开弹窗要用到的数据」。
 *
 * <h3>★★ 三个顶层字段都可能是 null（未发货时），前端要当心</h3>
 *
 * <p>{@code logisticsCompany} / {@code trackingNo} / {@code shipTime}
 * 直接来自 {@code orders} 表，<b>未发货时全是 null</b>，
 * 而 Jackson 的 {@code non_null} 会把它们<b>从 JSON 里整个删掉</b>。
 *
 * <p>所以前端判断「这一单发货了没」要写假值判断，
 * <b>不能写 {@code === null}</b> —— 那恒为 false。同一个坑这是第 5 次，
 * 见 {@code OrderVO.trackingNo} 的注释。
 *
 * <p>★ 但 {@link #traces} <b>永远存在、永远是数组</b>（没有节点时是空数组，
 * 不是 null）—— 它是服务端组装的，不来自数据库的某一列。
 * 前端 {@code v-for="t in data.traces"} 不需要判空。
 */
@Data
public class LogisticsVO {

    /**
     * 承运商（快递公司）。未发货 / 历史订单时<b>这个字段会从 JSON 里消失</b>。
     *
     * <p>★ 它是 {@code orders} 上的<b>快照</b>，不是从轨迹节点里推出来的 ——
     * 「这一单是哪家快递发的」在发货那一刻就定了，之后不会变。
     */
    private String logisticsCompany;

    /** 快递单号。同 {@link #logisticsCompany} */
    private String trackingNo;

    /** 发货时间。同 {@link #logisticsCompany} */
    private LocalDateTime shipTime;

    /**
     * 轨迹节点，<b>最新在上</b>（{@code traceTime DESC, id DESC}）。
     *
     * <p>★ 顺序由 SQL 决定，<b>服务端不重排</b> —— 重排就是第二个定义者，
     * 而「谁在上谁在下」这件事只该有一个地方说了算。
     * 见 {@code OrderLogisticsMapper.selectByOrderId}。
     *
     * <p>★ 没有节点时是<b>空数组</b>，绝不是 null。
     * 刚发货的订单就是这个样子（单号有了、还一条轨迹都没录），
     * 前端要显示「暂无物流信息」而不是报错。
     */
    private List<LogisticsTraceVO> traces;
}
