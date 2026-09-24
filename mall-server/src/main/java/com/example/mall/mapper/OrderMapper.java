package com.example.mall.mapper;

import com.example.mall.dto.ShopOrderQueryDTO;
import com.example.mall.entity.Order;
import com.example.mall.vo.OrderVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单数据访问接口。
 *
 * <h3>★ 这里没有 {@code selectById(Long id)}，这是刻意的</h3>
 *
 * <p>和 {@code MemberAddressMapper} 同一个理由，但后果更严重：
 * 订单里装着收货人的<b>姓名、电话、住址</b>。
 * 如果存在一个「只按 id 查订单」的方法，那么任何人只要
 * 挨个试 {@code /api/shop/orders/1}、{@code /2}、{@code /3}……
 * 就能把全平台的收货信息翻出来。
 *
 * <p>所以这里的查询方法<b>全部</b>带着 {@code memberId} 条件。
 * 想查订单，就必须先证明「这单是你的」。
 *
 * <p><b>让错误的写法写不出来，比让正确的写法更容易记住更可靠。</b>
 * 这是本项目的第三次说这句话（前两次在 {@code MemberAddressMapper}
 * 和 {@code AddressServiceImpl}）—— 因为它确实是最有效的一招。
 *
 * <h3>★ 幂等查询为什么也必须带 memberId？</h3>
 *
 * <p>这一点比上面更微妙，值得单独说。
 *
 * <p>幂等键的唯一索引是 {@code (member_id, idempotency_key)}——
 * 按会员唯一。也就是说<b>不同会员可以用同一个键</b>。
 * 那么如果这里写成「只按幂等键查」：
 * <pre>
 *   会员 A 提交订单，幂等键用了 "abcdefgh"（B 之前用过的键）
 *   → 只按键查 → 查到了 B 的那笔订单
 *   → A 得到的响应里是 B 的收货人、电话、住址、买了什么
 * </pre>
 * <b>一个「查重」的查询，变成了一个"读别人订单"的漏洞。</b>
 *
 * <p>而且这个漏洞很难被发现：代码看起来完全正常，
 * 「查一下这个键有没有建过单」这个意图也完全合理。
 * 唯一的破绽就是少了一个 {@code AND member_id = ?}。
 *
 * <p><b>★ 提炼出来：「唯一性范围」和「查询范围」必须一致。</b>
 * 唯一索引说的是「同一个会员内不重复」，那么查重就必须
 * 查「同一个会员内有没有重复」。两者范围不一致的地方，
 * 就是漏洞。（这也是 {@code migration-08b} 把唯一索引
 * 从列级改成组合索引的同一个理由的另一面。）
 */
public interface OrderMapper {

    /**
     * 新增订单。
     *
     * <p>插入后自增主键会回填到 {@code order.getId()}，
     * 后面写明细行要用它当 {@code order_id}。
     *
     * @return 影响行数，正常为 1
     */
    int insert(Order order);

    /**
     * 按「会员 + 幂等键」查订单，用于幂等判断。
     *
     * <p>查得到 → 说明这个会员之前用这个键成功建过单，直接把它返回，
     * 不要再建一单。
     *
     * <p>⚠️ {@code memberId} 条件不能省，理由见类注释。
     *
     * @return 查不到时返回 null（正常情况：这是该键第一次提交）
     */
    Order selectByMemberAndKey(@Param("memberId") Long memberId,
                              @Param("idempotencyKey") String idempotencyKey);

    /**
     * 按「会员 + 订单号」查订单。
     *
     * <p>和 {@link #selectByMemberAndKey} 是同一个形状：<b>要查订单，
     * 就必须先证明「这单是你的」</b>。类注释里那条纪律在这里同样适用，
     * 而且这里的后果一样严重 —— 订单里装着收货人的姓名、电话、住址。
     *
     * <p>⚠️ 如果只按 {@code orderNo} 查，那么「查看订单详情」这个完全正常的
     * 功能就变成了「按订单号读任意订单」。订单号虽然长（20 位），
     * 但它是<b>会出现在 URL 里、会被人看到、会被分享</b>的东西 ——
     * 不是一个可以当密码用的秘密。
     *
     * @return 查不到时返回 null。<b>注意：订单不存在、和订单不属于你，
     *         这里返回的都是 null，调用方也应该对这两种情况返回同一个错误码</b> ——
     *         区分开来等于告诉对方「这单存在，只是不是你的」，
     *         那就把「某个订单号是有效的」这个信息泄露了。
     */
    Order selectByOrderNoAndMember(@Param("orderNo") String orderNo,
                                   @Param("memberId") Long memberId);

    /**
     * 标记订单为已付款（条件更新：只有当前是「待付款」才付得了）。
     *
     * <p><b>★★ 这个方法返回的「影响行数」，是整个里程碑 9 的核心。</b>
     *
     * <h4>1. {@code AND status = 0} 就是并发防护本身</h4>
     *
     * <p>不需要额外的锁，也不需要先查一次状态再判断
     * （那是「先查后改」的竞态，和 {@code decreaseSkuStock} 注释里讲的是同一个坑）。
     * 两个人同时点「支付」，或者用户点支付的同一瞬间定时任务在扫超时 ——
     * 两条 UPDATE 争同一行的 InnoDB 行锁，<b>只有一个能拿到 {@code affected = 1}</b>，
     * 另一个拿到 0。
     *
     * <pre>
     *   影响行数 1 = 我抢到了状态迁移权，继续往下走
     *   影响行数 0 = 状态已经不是我期望的那个了，什么都没改
     * </pre>
     *
     * <h4>2. {@code AND member_id = #{memberId}} 是安全边界，不是过滤条件</h4>
     *
     * <p>少了它，任何人拿别人的订单号就能把别人的订单标记成已付款。
     * 和 {@code MemberAddressMapper} 里那几条 {@code AND member_id = #{memberId}}
     * 是同一个道理。
     *
     * <h4>3. {@code AND create_time > #{deadline}} 把「超时」做成了原子的</h4>
     *
     * <p>{@code deadline} 由调用方算好传进来（= 此刻 − 支付时限）。
     * 这一条的含义是：<b>已经过期的订单，即使定时任务还没跑到，也付不了款。</b>
     *
     * <p>这一点值得专门记：<b>「超过 30 分钟不能付款」是业务规则，
     * 规则在 SQL 里说一次就够了。</b> 定时任务只是「过后把库存还回去」的
     * 执行机制，不是规则的执行者。如果只在定时任务里判断超时，
     * 那么在「订单已过期」到「扫描跑到」之间的那段空窗期里，
     * 用户是可以付款成功的 —— 那是一个真实存在的漏洞窗口。
     *
     * <p>为什么用 {@code create_time} 而不是 {@code NOW()} 直接减？
     * 因为时限是可配置的（{@code mall.order.pay-timeout-minutes}），
     * 而 SQL 里读不到 Spring 的配置。让调用方算好一个绝对时刻传进来，
     * SQL 就只需要做一个纯粹的比较，不需要知道「30 分钟」这个数。
     *
     * @param deadline 支付时限的截止时刻，早于它的订单不允许支付
     * @return 影响行数。<b>1 = 支付成功；0 = 订单不存在 / 不属于该会员 /
     *         状态不是待付款 / 已过支付时限</b> —— 返回 0 时调用方要重查一次，
     *         好把上面这四种原因分辨成准确的错误信息
     */
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("memberId") Long memberId,
                 @Param("payMethod") String payMethod,
                 @Param("deadline") LocalDateTime deadline);

    /**
     * 标记订单为已取消（条件更新：只有当前是「待付款」才取消得了）。
     *
     * <p>并发防护和安全边界的道理和 {@link #markPaid} 完全一样，见那里的注释。
     * 这里只说两件它特有的：
     *
     * <h4>★ 为什么【没有】超时条件？</h4>
     *
     * <p>因为 {@code markPaid} 需要那条 {@code create_time} 是因为
     * 「过期了不许付」这条规则。而取消不一样：
     * <b>过期恰恰是取消的【理由】，不是取消的障碍。</b>
     * 用户主动取消自己的待付款订单，无论下了多久都应该允许。
     *
     * <h4>★ 调用方必须先调这个方法，抢到了才有资格归还库存</h4>
     *
     * <p>顺序绝对不能反。如果先归还库存再改状态，两个并发的取消请求
     * 会<b>都</b>把库存还一遍（因为两边在还的时候状态都还是「待付款」），
     * <b>库存凭空翻倍</b>。必须让这条条件更新当闸门：
     * 只有拿到 {@code affected = 1} 的那一个，才有资格往下走。
     *
     * <p>仓库里没有超卖，靠的就是这一条。
     *
     * @return 影响行数。<b>1 = 取消成功（且调用方现在应该去归还库存）；
     *         0 = 订单不存在 / 不属于该会员 / 状态不是待付款</b>
     */
    int markCancelled(@Param("orderNo") String orderNo,
                      @Param("memberId") Long memberId);

    // ==========================================================================
    // 以下是里程碑 10 加的
    // ==========================================================================

    /**
     * 分页查当前会员的订单（可按状态筛选）。
     *
     * <p>★ <b>它带 {@code member_id} 条件，所以放在这个文件里是合规的</b> ——
     * 类注释那条契约（「这里的查询方法全部带着 memberId」）没有被破坏，
     * 也就不需要像 {@code OrderTimeoutMapper} / {@code OrderAdminMapper}
     * 那样单独挪出去。这不是「加了一个例外」，
     * 而是这个文件本来就该有的查询。
     *
     * <p>⚠️ 和它配对的是 {@link #countByMember}，
     * 两者必须用<b>同一份</b> {@code <sql id="queryCondition">} 片段拼条件。
     * 这不是为了少写几行：如果列表和总数的条件不一致，
     * 症状是「页面显示 12 条、分页器说总共 8 条」，
     * 而且只在某些筛选组合下出现，属于很难查的那类 bug。
     *
     * <p>⚠️ 返回 {@code OrderVO} 而不是 {@code Order} 实体：
     * 列表页要显示什么，就是这几个字段，中间再转一道没有意义
     * （{@code OrderItemMapper.selectByOrderId} 的注释讲过这条标准：
     * <b>「要不要转」看有没有需要裁剪或补充的字段，不是「层与层之间必须转」</b>）。
     *
     * <p>⚠️ 它<b>不查明细</b>。明细由 Service 用一次
     * {@code OrderItemMapper.selectByOrderIds} 批量装上 ——
     * 否则就是一页 10 条查 10 次的 N+1。
     *
     * <h4>★★ 为什么 memberId 是一个独立参数，而不是 ShopOrderQueryDTO 的字段？</h4>
     *
     * <p>因为这两个值有一个<b>根本性的区别</b>：
     * <pre>
     *   status / pageNum / pageSize  →  【客户端】说什么就是什么，Spring MVC 从查询串绑定
     *   memberId                     →  【服务端】从 JWT 里读出来，客户端说了不算
     * </pre>
     * 如果把 {@code memberId} 也做成 {@code ShopOrderQueryDTO} 的字段，
     * 它就会<b>跟着其他字段一起被 Spring MVC 自动绑定</b> ——
     * 于是 {@code GET /api/shop/orders?memberId=999} 就能查到别人的订单。
     * 而且它是<b>静默生效</b>的：不需要改任何一行 Service 代码，
     * 因为 Service 本来就会把整个 query 对象传给 Mapper。
     *
     * <p><b>★ 一个会被「自动绑定」的对象，和一个只有服务端能填的对象，
     * 不能是同一个类。</b> 所以这里把身份单独列成参数 ——
     * 调用方必须<b>显式</b>写出「用谁的 id 来查」，
     * 而这个 id 从哪来，在 Service 里一眼可见（{@code currentMemberId()}）。
     *
     * <p>⚠️ 这也正是 {@link ShopOrderQueryDTO} 的注释里那句
     * 「结构上做不到的事，比代码里记得去做的事，可靠得多」的<b>落地方式</b>：
     * 光把字段从 DTO 里删掉是不够的，还得让 Mapper 能拿到 memberId ——
     * 否则这个查询根本写不出来。两件事必须一起做。
     *
     * @param memberId <b>安全边界</b>，必须来自 JWT（{@code UserContext}），
     *                 绝不能来自请求参数
     * @param query    分页参数 + 可选的 status 筛选（null 表示「全部」）
     * @return 不会为 null（没有数据时是空列表）
     */
    List<OrderVO> selectPageByMember(@Param("memberId") Long memberId,
                                     @Param("query") ShopOrderQueryDTO query);

    /**
     * 数当前会员有多少单（条件和 {@link #selectPageByMember} 完全一致）。
     *
     * <p>⚠️ 参数形状和它保持一样，包括 {@code memberId} 单独列出 ——
     * 两者必须共用同一份条件片段，参数形状不一致就没法共用了。
     *
     * @return 条数，没有时返回 0
     */
    long countByMember(@Param("memberId") Long memberId,
                       @Param("query") ShopOrderQueryDTO query);

    /**
     * 标记订单为已完成（用户确认收货）。
     *
     * <p>条件更新的道理和 {@link #markPaid} / {@link #markCancelled} 完全一样，
     * 这里只说它特有的两点。
     *
     * <h4>★★ 确认收货【绝不归还库存】—— 这是本方法最容易被改错的地方</h4>
     *
     * <p>看到 {@code markCancelled} 的调用方要去还库存，
     * 下一个读代码的人会很自然地想给这里「对称地」也补上一次归还。
     * <b>那是超卖。</b>
     *
     * <p>区别在于<b>东西还在不在你手上</b>：
     * <pre>
     *   取消（待付款 → 已取消）：货从来没发出去，库存在仓里 → 还回去 ✅
     *   确认收货（已发货 → 已完成）：货【已经寄到买家手里了】 → 还回去 = 凭空多出一件可以卖的货 ❌
     * </pre>
     * 「已完成」表示这笔交易结束了，不是「这笔交易没发生」。
     *
     * <p>★ 所以这两个方法虽然都是「把订单推向一个终态」，
     * <b>但对库存的影响恰好相反</b>。条件更新的形状一样，
     * 不代表副作用也一样 —— 照着形状抄是最容易出事的一种copy。
     *
     * <h4>★ 为什么条件是 {@code status = 2} 而不是别的？</h4>
     *
     * <p>因为「确认收货」在状态图里只有一条边：<b>已发货 → 已完成</b>。
     * 待付款、已付款、已完成、已取消的订单都不该能被确认收货
     * （已付款的还没发货，确认什么？）。
     * 这条约束<b>写在 SQL 里，不靠前端按钮是否显示</b> ——
     * 前端只是个界面，绕过它直接调接口是很容易的事。
     *
     * @return 影响行数。<b>1 = 确认成功；0 = 订单不存在 / 不属于该会员 /
     *         状态不是已发货</b> —— 返回 0 时调用方要重查一次，
     *         好把上面三种原因分辨成准确的错误信息
     */
    int markCompleted(@Param("orderNo") String orderNo,
                      @Param("memberId") Long memberId);
}
