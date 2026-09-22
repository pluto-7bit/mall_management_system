package com.example.mall.job;

import com.example.mall.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时订单清理任务 —— 每 10 秒扫一遍，把超过支付时限还没付款的订单取消掉、归还库存。
 *
 * <h3>★ 这个类存在的意义，是把「调度」和「业务」分开</h3>
 *
 * <p>它里面只有一行真正的逻辑：{@code orderService.cancelTimeoutOrders()}。
 * 剩下的全是「怎么被触发」和「出错了怎么办」。
 *
 * <p>为什么要分成两个类，而不是把 {@code @Scheduled} 直接加在
 * {@code OrderServiceImpl.cancelTimeoutOrders} 上？因为<b>那样做会让 Service
 * 无法脱离调度器被调用</b>：
 * <pre>
 *   - 管理端想加一个「手动触发一次超时清理」的按钮 → 调 Service 就行
 *   - 测试想直接验一次清理逻辑，不等 10 秒 → 调 Service 就行
 *   - 将来换成用消息队列触发 → 只改这个 Job 类，Service 不动
 * </pre>
 * <b>「什么时候做」和「做什么」是两件会各自变化的事，就该分开。</b>
 * 这和 Controller 只是薄薄一层、业务全在 Service 里是同一个思路。
 *
 * <h3>★ 为什么整个方法包在 try-catch 里？</h3>
 *
 * <p>两个理由，第一个是事实，第二个是意图：
 *
 * <ol>
 *   <li><b>事实上</b>：{@code @Scheduled} 的方法抛出异常，Spring 会把它记进日志，
 *       但<b>不会</b>让调度停掉 —— 下一次到点还是会跑。所以不 catch 也不会"崩"。</li>
 *   <li><b>但意图上</b>：不 catch 的话，日志里每 10 秒一次 ERROR 堆栈。
 *       而且读代码的人会以为"异常会冒到框架层所以不用管"。
 *       显式接住并写清楚「这一轮失败不影响下一轮」，比依赖框架的行为更好 ——
 *       <b>明确表达出来的意图，比依赖隐含行为可靠。</b></li>
 * </ol>
 *
 * <p>⚠️ 注意这里 catch 的是 {@code Exception} 而不是某个具体异常。
 * 定时任务是最需要"宽 catch"的地方之一：它没有调用者可以处理异常，
 * 任何逃出去的异常除了刷日志之外没有任何作用，只会让这一轮白跑。
 * （这和业务代码里"不要 catch Exception"的纪律不矛盾 ——
 * <b>那条纪律针对的是有调用者的代码，错误必须让调用者知道。</b>
 * 定时任务是"没有调用者"的那一类，兜住才是对的。）
 *
 * <h3>★ 为什么用 {@code fixedDelay} 而不是 {@code fixedRate}？</h3>
 *
 * <p>两者的差别只在「从什么时候开始计时」：
 * <pre>
 *   fixedRate  = 按固定【频率】发起。第 0 秒发起、第 10 秒发起、第 20 秒发起……
 *                如果某次执行花了 15 秒，那么第 10 秒那次会在第 15 秒
 *                立刻补跑（甚至堆积多个），任务会越欠越多。
 *   fixedDelay = 上一次【跑完】之后再等 10 秒。（本项目选的）
 *                执行时间变长时，实际间隔自动跟着变长，永不堆积。
 * </pre>
 *
 * <p><b>定时清理类的任务一律用 fixedDelay。</b>
 * 它要的是「别漏、别堆」，不是「准点」。
 * 反过来，如果任务必须"每个整点跑一次"（比如每天凌晨生成报表），
 * 那就该用 cron 表达式，那才是真正需要"准点"的场景。
 *
 * <h3>★ 没有超时订单时，它什么都不打印</h3>
 *
 * <p>这是刻意的。每 10 秒跑一次，一天 8640 次 ——
 * 如果每次都打一行 "本轮取消 0 单"，真正有用的日志会被彻底淹掉。
 * <b>定时任务的日志应该是「没事发生时不说话」。</b>
 * 需要确认它还活着的话，看 Actuator 或者临时把日志级别调成 debug。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final OrderService orderService;

    /**
     * 扫描并取消超时未付款的订单。
     *
     * <p>间隔由 {@code mall.order.timeout-scan-interval-ms} 配置。
     * 用 {@code fixedDelayString}（而不是 {@code fixedDelay}）是为了能从
     * 配置文件读值 —— 它接受一个字符串表达式，Spring 会解析成毫秒数。
     */
    @Scheduled(fixedDelayString = "${mall.order.timeout-scan-interval-ms}")
    public void cancelTimeoutOrders() {
        try {
            orderService.cancelTimeoutOrders();
        } catch (Exception e) {
            // 这一轮失败不影响下一轮 —— 这句话必须写出来。
            // 看到这行 ERROR 说明有需要人看的问题（比如数据库连不上），
            // 而不是"某个订单数据不对"（那种在 Service 里已经被逐条跳过了）。
            log.error("超时订单扫描异常，本轮跳过，等待下一轮", e);
        }
    }
}
