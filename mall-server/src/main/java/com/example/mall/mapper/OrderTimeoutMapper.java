package com.example.mall.mapper;

import com.example.mall.entity.Order;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时订单扫描 —— <b>全项目唯一一个不按会员隔离的订单查询。</b>
 *
 * <h3>★ 为什么单独一个文件，而不是把它塞进 {@code OrderMapper}？</h3>
 *
 * <p>{@code OrderMapper} 的类注释是一份<b>契约</b>：
 * 「这里的查询方法<b>全部</b>带着 {@code memberId} 条件。
 * 想查订单，就必须先证明『这单是你的』。」
 *
 * <p>这份契约是用来说明「为什么这个接口里没有 {@code selectById(Long)}」的，
 * 它是一个安全承诺。如果在这一堆方法中间插一个「不带 memberId」的查询，
 * 那份承诺就<b>变成了假话</b> —— 而一句写着「本接口所有方法都安全」的注释，
 * 远比没有注释更危险，因为它会让人停止思考。
 *
 * <p><b>★ 提炼出来：例外要放在文件边界上，不要放在文件内部。</b>
 * 把例外隔离成一个独立的类之后有两件事同时成立：
 * <ul>
 *   <li>{@code OrderMapper} 的契约重新变成真的，一个字都不用改；</li>
 *   <li>「系统里有哪些查询绕过了会员隔离」变成一句 grep 就能审计的事情 ——
 *       只需要看这个类的名字出现的地方。</li>
 * </ul>
 * 这正是真实系统里把 admin / system 视角的查询<b>单独拆出去</b>的做法。
 * 例外不是不能有，但必须<b>数得清、找得到、看得见</b>。
 *
 * <h3>★ 它只负责「找出是谁」，不负责「改」</h3>
 *
 * <p>这是这个类最重要的设计约束。扫描出来的行里带着 {@code memberId}，
 * 所以后续的取消<b>完全可以复用 {@code OrderMapper.markCancelled(orderNo, memberId)}</b>。
 *
 * <p>这样做换来两件事：
 * <ul>
 *   <li><b>写入路径仍然带会员隔离。</b>隔离的例外只存在于「读」，不存在于「写」——
 *       而「写」才是真正改变数据、真正危险的那一半。</li>
 *   <li><b>用户主动取消和超时自动取消走的是同一段代码。</b>
 *       归还库存的逻辑只写一遍，也就不可能出现「用户取消会还库存、
 *       超时取消忘了还」这种漏。</li>
 * </ul>
 *
 * <h3>⚠️ 使用纪律</h3>
 *
 * <p><b>不要在 Controller 里调它，也不要在任何「会员请求」的调用链上调它。</b>
 * 它唯一的合法调用者是 {@code OrderServiceImpl.cancelTimeoutOrders()}，
 * 也就是定时任务那条系统视角的链路。它的返回值<b>永远不应该直接返回给客户端</b> ——
 * 那是一批别人订单的收货人姓名、电话、住址。
 */
public interface OrderTimeoutMapper {

    /**
     * 捞出所有「还是待付款、且下单时间早于 deadline」的订单。
     *
     * <p>{@code deadline} 由调用方算好传进来（= 此刻 − 支付时限），
     * 而不是在 SQL 里写 {@code NOW() - INTERVAL 30 MINUTE}：
     * 时限是可配置的（{@code mall.order.pay-timeout-minutes}），
     * SQL 读不到 Spring 的配置。<b>让调用方提供「截止时刻」这个绝对概念，
     * SQL 就只需要做一个纯粹的比较。</b>
     *
     * <p><b>★ 为什么必须带 {@code limit}？</b>
     * 因为这是一个定时任务。如果积压了很多单（比如服务停了三天），
     * 一条不限量的 SELECT 会把几十万行一次性拉进内存，
     * 而且是每 10 秒一次。{@code limit} 让单次扫描的内存占用有上界。
     * 积压会按 {@code limit / 扫描间隔} 的速度慢慢排掉 ——
     * 慢一点没关系，<b>定时清理任务宁可慢，不能把服务拖垮。</b>
     *
     * <p>⚠️ 用 {@code <include refid="OrderMapper.baseColumns"/>} 全限定引用
     * 另一个 mapper 的列清单片段，而不是在这里重抄一遍列名：
     * 两个文件查的是同一张表，列清单只该有一份。
     *
     * <p>★ 走 {@code idx_status} 或 {@code idx_create_time}（建表时就有了）。
     * 订单量小的时候足够了，<b>先别急着加联合索引</b> ——
     * 加索引要有 EXPLAIN 做依据，不能凭感觉。
     *
     * @param deadline 截止时刻，下单时间早于（或等于）它的待付款订单要被取消
     * @param limit    单次最多返回多少条，防止积压时一次拉太多
     * @return 候选订单列表（含 {@code memberId}，供后续带隔离的写操作用）。
     *         没有超时订单时返回<b>空列表</b>，不是 null
     */
    List<Order> selectTimeoutCandidates(@Param("deadline") LocalDateTime deadline,
                                        @Param("limit") int limit);
}
