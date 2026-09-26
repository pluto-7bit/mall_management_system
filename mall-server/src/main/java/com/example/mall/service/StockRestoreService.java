package com.example.mall.service;

import com.example.mall.vo.OrderItemVO;

import java.util.List;

/**
 * 把一批订单明细占用的库存还回去。★ 里程碑 17 新增。
 *
 * <h3>★★★ 这个接口的契约只有一句话：它<b>不做任何资格判断</b></h3>
 *
 * <p>「现在还该不该还库存」由<b>调用方的条件 UPDATE 的影响行数</b>决定 ——
 * 调用方抢到了那条边，才有资格调这里。
 *
 * <pre>
 *   取消订单     →  OrderMapper.markCancelled 拿到 affected = 1
 *   仅退款同意   →  AfterSaleMapper.markRefundedForOnlyRefund 拿到 affected = 1
 *   确认收到退货 →  AfterSaleMapper.markReceivedAndRefunded 拿到 affected = 1
 * </pre>
 *
 * <p><b>谁在这里加一个 {@code if}，谁就把闸门从数据库搬回了 Java，
 * 然后并发下库存会翻倍。</b> —— 因为 Java 里的「先查后写」
 * 永远挡不住并发（{@code ProductReviewServiceImpl.create} 已经用血写过结论）。
 *
 * <h3>★ 为什么它是一个独立接口，而不是某个 Service 里的一个方法</h3>
 *
 * <p>因为它跨两个 Service：订单（{@code OrderServiceImpl.cancelInternal}）
 * 和售后（{@code AfterSaleServiceImpl}）。
 * 放在任何一个里面，另一个都要<b>反向依赖</b>它 ——
 * 订单服务去调售后服务的内部方法，或者反过来，
 * 都是那种「编译得过、但依赖方向错了」的耦合。
 *
 * <h3>★★ 为什么可以抽这一层（这是本轮唯一一次「为消除重复而引入新抽象」）</h3>
 *
 * <p>判据有两条，缺一不可：
 * <ol>
 *   <li><b>两个以上调用者</b> —— 现在有三个，而且第四个（退货退款的另一条路径）
 *       也在路上。只为一个调用者抽一层不是抽象，是绕路。</li>
 *   <li><b>写错了会超卖</b> —— 这是 <b>{@code OrderService} 里那句原话的落地</b>：
 *       「扣库存那段代码绝对不能有两份。两份就意味着『改了一份忘了另一份』，
 *        而漏掉的那份就是超卖漏洞。」</li>
 * </ol>
 *
 * <p>⚠️ <b>把这句话写进注释是必要的，否则下一轮会有人拿它当
 * 「什么都可以抽一层」的先例。</b> 90% 的「抽一层」是过度设计；
 * 这一处是那 10% —— 而判据不是「看着像重复」，是上面那两条。
 *
 * <h3>★ 它只接收 VO，不接收 orderId</h3>
 *
 * <p>因为三个调用者手上拿到的都是明细列表（{@code OrderItemVO}），
 * 而且<b>它们都已经通过各自的路径校验过归属了</b> ——
 * 再让这个方法自己去查一次，等于在「分层安全」之外多开一条没人知道的查询路径。
 * 见 {@code OrderItemMapper.selectByOrderId} 注释里那段「分层安全」。
 */
public interface StockRestoreService {

    /**
     * 把 {@code items} 里每一行占用的库存加回它自己的 SKU。
     *
     * <p><b>★ 它不会抛异常，也不会因为某一行失败而中断其余行。</b>
     * 两类「还不了」的情况都只记 warn：
     * <pre>
     *   item.skuId == null            →  里程碑 13 之前的孤儿明细，本来就没有 SKU 这个概念
     *   increaseSkuStock 返回 0       →  SKU 已被硬删（order_item 故意没有外键）
     * </pre>
     * 两种都不是用户的错，<b>不能因此让「取消订单」「退款」这样的操作失败</b> ——
     * 否则订单卡在原地、库存永远占着、用户还看得见它。
     * SKU 没了是运维数据的问题，人工核对即可。
     *
     * <p>★ 也正因为它是「尽力而为」，调用方<b>不需要（也不能）用它的返回值
     * 去判断什么</b> —— 唯一的判断依据是抢边时的 {@code affected}。
     *
     * @param items 要归还的订单明细。<b>可以为空</b>（空列表就是什么都不做）。
     *              这里和 {@code batchInsert} 那种「空集合会拼出语法错误」
     *              的情况不同：循环一次都不进，天然安全
     * @param bizNo 出问题的日志里要打的业务单号（订单号或售后单号）——
     *              <b>错误路径上的日志必须是真话</b>，而「哪一单出问题了」
     *              是运维唯一能顺着往下查的东西
     */
    void restoreOrderItems(List<OrderItemVO> items, String bizNo);
}
