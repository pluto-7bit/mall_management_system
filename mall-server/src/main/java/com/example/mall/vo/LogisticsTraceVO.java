package com.example.mall.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个物流轨迹节点。★ 里程碑 18 新增。
 *
 * <p>和实体 {@code OrderLogistics} 的差别只有一个：<b>它不带 {@code orderId}</b>。
 * 理由同那个实体不存 {@code orderNo} —— 判据 ③「有读者才加列」：
 * 这个 VO 是挂在 {@code LogisticsVO} 下面返回给前端的，
 * 而 {@code LogisticsVO} 本身就是<b>某一单</b>的物流，
 * 每条节点再带一次订单 id，前端永远没人读它。
 *
 * <h3>★ {@code status} 是码，不是中文</h3>
 *
 * <p>前端拿它画图标和颜色（{@code logisticsStatusTagType}），
 * 所以必须拿到码本身。中文只用来写日志 —— 这条纪律见
 * {@code LogisticsStatus.text} 的注释。
 */
@Data
public class LogisticsTraceVO {

    /**
     * 节点 id。前端删除节点时要用它拼路径
     * （{@code DELETE /api/admin/orders/{orderNo}/logistics/{traceId}}）。
     */
    private Long id;

    /** 节点状态码，取值见 {@code LogisticsStatus} */
    private Integer status;

    /** 这一节点的说明（管理员手写） */
    private String description;

    /**
     * ★ 这一节点【发生】的时刻。时间线显示的是它，不是 {@link #createTime}。
     *
     * <p>两者可以差很远：管理员晚上补录白天的事，{@code traceTime} 是上午 9 点、
     * {@code createTime} 是晚上 8 点。<b>用户要看的是前者</b> ——
     * 「我的包裹什么时候到的杭州」才是用户的问题。
     */
    private LocalDateTime traceTime;

    /**
     * 录入时间。
     *
     * <p>★ 读者<b>只有一个</b>：管理端物流对话框里那行小字，
     * 而且<b>只在「录入时间和发生时间不是同一天」时才显示</b> ——
     * 补录的场景下这两个时间差得远，值得看；当天录的情况下
     * 它们是同一件事，显示两遍只是噪声。
     *
     * <p>⚠️ 所以它<b>不是</b>「时间线要显示的两行字之一」。
     * 用户端那份弹窗完全不显示它：会员不关心管理员是什么时候录的，
     * 他关心的是包裹什么时候到的（{@link #traceTime}）。
     */
    private LocalDateTime createTime;
}
