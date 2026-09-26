package com.example.mall.service.impl;

import com.example.mall.mapper.ProductSkuMapper;
import com.example.mall.service.StockRestoreService;
import com.example.mall.vo.OrderItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 归还库存的实现。★ 里程碑 17 从 {@code OrderServiceImpl.cancelInternal} 抽出来。
 *
 * <p>它的逻辑就是原来那段循环，<b>一个字都没有改</b> ——
 * 这是一次纯粹的搬移，不是一次重写。判断依据是
 * {@code test-order.py} / {@code test-order-list.py} 里
 * 「取消订单归还库存」那几条必须仍然全绿（那是本轮最高回归风险的一处）。
 *
 * <h3>★ 为什么它没有 {@code @Transactional}</h3>
 *
 * <p>因为它<b>必须在调用方的事务里跑</b>。三个调用者全都是
 * 「抢边 → 还库存 → 推订单终态」的事务，如果这里自己开一个
 * {@code REQUIRED}（默认传播行为）的事务，它会加入外层事务 —— 看起来没差别；
 * 但如果哪天有人把它调在事务外，一个「只包住还库存」的事务
 * 恰恰是最坏的结果：<b>边抢到了、库存也还了，然后外面回滚了</b>。
 *
 * <p>所以它的正确用法是<b>由调用方的事务覆盖</b>，
 * 而这一点靠注释说明，不靠注解 —— {@code @Transactional} 在这里
 * 表达不了「我不该被单独调用」这件事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRestoreServiceImpl implements StockRestoreService {

    private final ProductSkuMapper productSkuMapper;

    @Override
    public void restoreOrderItems(List<OrderItemVO> items, String bizNo) {
        for (OrderItemVO item : items) {
            // ★★ 里程碑 15 阶段 4：还库存的主语从【商品】换成了【规格】。
            //
            //    这一处是整轮里最不容易被测出来的一处：还错了 SKU，
            //    接口照样 200、订单状态照样变成「已取消」，
            //    只是库存加到了另一件商品（或另一个规格）上。
            //    test-sku.py 的 C 组专门为它准备了一条：
            //    「同商品两个规格，取消后 A 加回来 2、B 一个数都不动」。
            //
            //    ⚠️ 用 productId 归还的老代码会让那条断言翻红 ——
            //       因为 product_sku 的主键空间和 product 是两套独立的自增值，
            //       拿 productId 去 WHERE id 是会命中【别人的 SKU】的。
            //
            // ★ 先挡掉 skuId 为 null 的历史明细。
            //   这里【不】指望 increaseSkuStock(null, qty) 返回 0 就够了，
            //   是因为下面那条 warn 会打出「SKU 不存在（可能已被删除）」——
            //   而对 null 来说那句话是错的（它不是被删了，是这一行
            //   本来就没有 SKU 这个概念：里程碑 13 之前下的单、
            //   以及商品被硬删的孤儿明细）。**错误路径上的日志必须是真话**，
            //   否则运维会照着它去查一个根本不存在的「被删的 SKU」。
            if (item.getSkuId() == null) {
                log.warn("归还库存时该明细没有 skuId（历史订单或商品已被硬删），跳过: "
                                + "bizNo={}, orderItemId={}, productId={}, quantity={}",
                        bizNo, item.getId(), item.getProductId(), item.getQuantity());
                continue;
            }

            int restored = productSkuMapper.increaseSkuStock(item.getSkuId(), item.getQuantity());

            if (restored == 0) {
                // ★ 只记日志，不抛异常。
                //   order_item 故意没有外键（见 mall.sql 里那段说明），
                //   所以这个 SKU 有可能已经被硬删除（商家改了规格定义，
                //   旧的那一行被删了）。这不是用户的错，
                //   不能因此让「取消订单」「退款」这个操作失败 ——
                //   否则订单卡在原地、库存永远占着、用户还看得见它。
                //   SKU 没了是运维数据的问题，人工核对即可。
                //
                //   （这和 increaseStock 当年那句「商品已被硬删」是同一类情况，
                //     只是粒度细了一层。）
                log.warn("归还库存时 SKU 不存在（可能已被删除或换过规格），跳过: "
                                + "bizNo={}, skuId={}, quantity={}",
                        bizNo, item.getSkuId(), item.getQuantity());
            }
        }
    }
}
