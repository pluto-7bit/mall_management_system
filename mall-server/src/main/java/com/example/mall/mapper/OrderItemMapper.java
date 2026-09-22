package com.example.mall.mapper;

import com.example.mall.entity.OrderItem;
import com.example.mall.vo.OrderItemVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单明细数据访问接口。
 */
public interface OrderItemMapper {

    /**
     * 批量插入订单明细。
     *
     * <p><b>★ 为什么是「一条 INSERT 插多行」，不是「循环调 insert」？</b>
     *
     * <p>一笔订单有几种商品就要插几行。如果循环调用单条 insert：
     * <pre>
     *   买 5 种商品 → 5 条 SQL → 5 次网络往返 → 5 次事务内的行锁操作
     * </pre>
     * 用 {@code <foreach>} 拼成一条：
     * <pre>
     *   INSERT INTO order_item (...) VALUES (...), (...), (...), (...), (...)
     * </pre>
     * 一次往返就完了。<b>和 {@code selectShopByIds} 用 IN 而不是循环查，
     * 是同一个道理：能一条 SQL 干完的，不要循环。</b>
     *
     * <p>在事务里这一点更重要：每条 SQL 都有开销，
     * 而事务是<b>持有数据库连接和锁</b>的。事务越长，
     * 别人等得越久，死锁的概率也越高。
     *
     * <p>⚠️ 批量插入要注意：MySQL 对单条 SQL 的长度有限制
     * （{@code max_allowed_packet}），商品种类特别多时
     * 一条 SQL 可能太大。真实项目会在「一次插多少行」上设个上限，
     * 分批插。本项目一笔订单的商品种类不可能多到那个程度，先不分批。
     *
     * @param items 明细列表。<b>调用方必须保证非空</b> ——
     *              空的 {@code VALUES} 是 SQL 语法错误
     * @return 影响行数，正常应等于 {@code items.size()}
     */
    int batchInsert(@Param("items") List<OrderItem> items);

    /**
     * 查一笔订单的所有明细。
     *
     * <p>直接返回 VO 而不是 Entity：前端要的就是这几个字段，
     * 而 {@code OrderItemVO} 里已经把它们对齐好了。
     * 中间再转一道（Entity → VO）在这里没有意义 ——
     * <b>「要不要转」的标准是有没有需要裁剪或补充的字段，
     * 不是「层与层之间必须转」。</b>
     *
     * <p>⚠️ 注意这个方法<b>只按 orderId 查，没有 memberId 条件</b>。
     * 它安全的前提是：调用方拿到 orderId 之前，
     * 已经通过 {@code OrderMapper} 用 memberId 校验过这笔订单的归属了。
     *
     * <p><b>★ 这是「分层安全」的一个真实例子：
     * 安全校验不需要每个方法都做一遍，但必须保证"每个调用链上都做了"。</b>
     * 所以这个方法的存在意味着一条纪律：
     * <b>不要直接从 Controller 调它。</b>
     *
     * <p>⚠️ 里程碑 10 起它也会返回 {@code orderId}（原来省掉了）。
     * 理由见 {@code OrderItemVO} 的类注释 —— 为了和下面那个批量方法形状一致。
     */
    List<OrderItemVO> selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 一次查多笔订单的明细 —— <b>里程碑 10 加的，用来消掉一个 N+1</b>。
     *
     * <h3>★ 它解决的是什么问题？</h3>
     *
     * <p>订单列表一页 10 笔订单。如果还是逐个调 {@code selectByOrderId}：
     * <pre>
     *   1 次「查订单列表」 + 10 次「查明细」 = 11 次数据库往返
     * </pre>
     * 一页 10 条、翻 10 页就是 110 次查询，而其中 100 次都在问同一件事。
     * 这就是 N+1：<b>一次查询拿到了 N 条主记录，然后为每条主记录又查了一次。</b>
     * 它不会报错，只是随着数据量变大而越来越慢 ——
     * 属于「在开发机上永远发现不了」的那类问题。
     *
     * <p>换成这个方法之后：<b>2 次查询，和这一页有多少笔订单无关。</b>
     *
     * <h3>★ 返回的是 VO，和单笔那个方法完全一致</h3>
     *
     * <p>没有走「批量查返回 Entity、Service 里再手工转成 VO」那条路。
     * 那样会在 Service 里造出<b>第二条转换路径</b>，
     * 而 {@code OrderServiceImpl.toVO} 的注释专门警告过这件事：
     * 第二条路径就是「将来加字段时漏改一处」的来源。
     * 两条查询返回同一种类型、同一套字段名，Service 里就能用同一段代码处理。
     *
     * <h3>⚠️ 调用方必须保证 {@code orderIds} 非空</h3>
     *
     * <p>空的 {@code IN ()} 是 <b>SQL 语法错误</b>，不是「返回 0 行」——
     * {@code <foreach>} 会把空集合拼成一个光秃秃的 {@code IN ()}。
     * 所以「这一页一条订单都没有」必须在 Java 里提前返回，
     * <b>不能指望 SQL 兜底</b>。这是 MyBatis 动态 SQL 最经典的翻车点之一。
     *
     * <h3>⚠️ 和 selectByOrderId 同样的纪律</h3>
     *
     * <p>它也只按 {@code order_id} 查、不带 {@code memberId}。
     * 安全前提同样是：调用方拿到这批 orderId 之前，
     * 已经通过 {@code OrderMapper} 校验过归属了。
     * <b>不要直接从 Controller 调它。</b>
     *
     * @param orderIds 订单 id 列表，<b>调用方必须保证非空</b>
     * @return 这些订单的全部明细，按 {@code order_id, id} 排序
     *         （每笔订单内部保持插入顺序，且同一笔的明细一定连在一起）
     */
    List<OrderItemVO> selectByOrderIds(@Param("orderIds") List<Long> orderIds);
}
