package com.example.mall.service;

import com.example.mall.common.PageResult;
import com.example.mall.dto.BuyNowDTO;
import com.example.mall.dto.CartOrderDTO;
import com.example.mall.dto.OrderQueryDTO;
import com.example.mall.dto.OrderShipDTO;
import com.example.mall.dto.ShopOrderQueryDTO;
import com.example.mall.vo.AdminOrderVO;
import com.example.mall.vo.OrderVO;

/**
 * 下单业务接口 —— <b>整个项目里最需要小心的一个模块。</b>
 *
 * <h3>★ 为什么它最需要小心？</h3>
 *
 * <p>因为下单这个动作同时具备三个特点，而每一个都容易出事：
 *
 * <ol>
 *   <li><b>会改库存，而且一定会有人同时抢。</b>
 *       两个用户同时买最后一件商品，是必然发生的事，不是"万一"。
 *       「先查库存再扣」这种写法平时看着好好的，
 *       一到真实并发就会超卖 —— 而且超卖是**已经发货了才发现**，
 *       损失是真的钱。见 {@code ProductSkuMapper.decreaseSkuStock}。</li>
 *
 *   <li><b>用户会重复点。</b>
 *       网络卡了、手抖了、浏览器刷新了，用户都会再点一次提交。
 *       如果没有幂等保护，一次点击就变成两笔订单、两份库存、
 *       两次发货 —— 用户会投诉「我明明只买了一件」。</li>
 *   <li><b>它要写多张表，必须一起成功或一起失败。</b>
 *       订单主表、订单明细、商品库存，三处写入是一个整体。
 *       任何一处失败而其他两处成功，数据就坏了 ——
 *       比如「扣了库存但没有订单」，或者「有订单但没扣库存」。</li>
 * </ol>
 *
 * <h3>★ 两个入口，一套逻辑</h3>
 *
 * <p>{@link #createFromCart} 和 {@link #createByBuyNow} 的差别只有两点：
 * <b>数量从哪来</b>、<b>下单后要不要清购物车</b>。
 * 其余步骤（校验地址、取商品快照、算钱、扣库存、写订单、幂等）
 * 完全共用一份实现，理由见 {@code OrderSource} 的注释。
 *
 * <p><b>这一点很重要：扣库存那段代码绝对不能有两份。</b>
 * 两份就意味着「改了一份忘了另一份」，而漏掉的那份就是超卖漏洞。
 * 抽象在这里不是为了少写代码，是为了让危险的地方只有一个。
 */
public interface OrderService {

    /**
     * 购物车结算。
     *
     * <p>{@code POST /api/shop/orders}
     *
     * <p>结算哪些商品由 {@code dto.productIds} 指定（用户勾选的那几个），
     * 但<b>每种买几件由服务端从 Redis 购物车里读</b> ——
     * 请求里根本不提供数量，理由见 {@link CartOrderDTO}。
     *
     * <p>下单成功并且事务提交之后，会把这几种商品从购物车里移除。
     *
     * @return 新订单，或<b>幂等命中时</b>之前建立的那笔订单
     */
    OrderVO createFromCart(CartOrderDTO dto);

    /**
     * 立即购买。
     *
     * <p>{@code POST /api/shop/orders/buy-now}
     *
     * <p>数量来自请求参数（因为这条路上服务端没有别的真相来源），
     * 而且<b>完全不碰购物车</b> —— 既不加也不清。
     *
     * @return 新订单，或幂等命中时之前建立的那笔订单
     */
    OrderVO createByBuyNow(BuyNowDTO dto);

    // ==========================================================================
    // 里程碑 9：支付 / 取消 / 超时扫描
    //
    // ★ 这三个方法的共同点：它们都是「把一个订单从一个状态推到另一个状态」。
    //   和上面两个创建方法最大的区别是 —— 创建是【新增一行，而且可以无限次新增】，
    //   所以必须有幂等键；而状态迁移的副作用是【改一行，且只能从特定状态改】，
    //   所以幂等性天然由 `WHERE status = ?` 提供了。
    //   详见下面 pay / cancel 的注释。
    // ==========================================================================

    /**
     * 按订单号查订单详情（只能查自己的）。
     *
     * <p>{@code GET /api/shop/orders/{orderNo}}
     *
     * <p>这是用户端第一次能「回头看自己的订单」。之前只有创建接口，
     * 建完就再也查不到了 —— 所以里程碑 9 顺手把这个补上，
     * 否则收银台页打开时无从知道该显示什么。
     *
     * <p>⚠️ 订单不属于当前会员时，和订单不存在返回<b>同一个错误码</b>。
     * 区分开来等于确认了「这个订单号是存在的」。
     *
     * @return 订单详情（含明细）
     */
    OrderVO getByOrderNo(String orderNo);

    /**
     * 支付订单（模拟）。
     *
     * <p>{@code POST /api/shop/orders/{orderNo}/pay}
     *
     * <p>只有「待付款」且<b>未超过支付时限</b>的订单能支付。
     * 超过时限的订单即使定时任务还没扫到，也会被拒绝 ——
     * 理由见 {@code OrderMapper.markPaid} 的注释。
     *
     * @param payMethod 支付方式码，取值见 {@code PayMethod}
     * @return 支付后的订单
     */
    OrderVO pay(String orderNo, String payMethod);

    /**
     * 取消订单。
     *
     * <p>{@code POST /api/shop/orders/{orderNo}/cancel}
     *
     * <p>只有「待付款」的订单能取消，取消的同时<b>把库存还回去</b>。
     * 已付款的订单不能取消（要走退款流程，本项目不实现）。
     *
     * @return 取消后的订单
     */
    OrderVO cancel(String orderNo);

    /**
     * 扫描并取消所有超时未付款的订单 —— <b>给定时任务调，不是给用户调的。</b>
     *
     * <p>和上面两个方法放在同一个接口里，是因为它要做的事情本质上是
     * 「批量取消」，<b>和 {@link #cancel} 走的是同一段代码</b>。
     * 拆成两个接口反而会让「归还库存」的逻辑有被写第二遍的机会。
     *
     * <p>⚠️ 这个方法<b>没有当前会员的概念</b> —— 它代表系统视角，
     * 一次性处理所有会员的超时订单。所以它内部<b>不能</b>调用
     * {@code UserContext.require()}（定时任务的线程上没有登录态，会抛 401）。
     *
     * @return 这一轮成功取消了几个订单。返回 0 是常态，不是错误
     */
    int cancelTimeoutOrders();

    // ==========================================================================
    // 里程碑 10：订单列表 + 发货 / 确认收货
    //
    // ★ 这一组补齐了状态机剩下的两条边：
    //     已付款 ──发货──> 已发货 ──确认收货──> 已完成
    //   里程碑 9 之前，OrderStatus 里的 SHIPPED / COMPLETED 两个常量
    //   从来没有被任何一行代码写过 —— 是纯死代码（和当时 PAID 的处境一样）。
    //   到这里，OrderStatus 里画的那张状态流转图【每一条边都有真实代码了】。
    //
    // ★ 这两个状态迁移【都不需要事务】，理由见实现类里 ship / complete
    //   的注释：判据是「有没有多个必须一起成败的写」，而它们各自只有一个写。
    // ==========================================================================

    /**
     * 分页查当前会员的订单 —— 「我的订单」页面。
     *
     * <p>{@code GET /api/shop/orders}
     *
     * <p>★ 查的是谁由 JWT 决定，不由请求参数决定
     * （{@code ShopOrderQueryDTO} 里<b>没有</b> memberId 字段）。
     *
     * <p>★ 每一笔订单的明细都会装上，但<b>只用一次批量查询</b> ——
     * 逐笔去查就是 N+1。见实现类里的 {@code attachItems}。
     *
     * @param query 分页 + 可选的 status 筛选（null 表示「全部」）
     * @return 分页结果，没有订单时是空列表而不是 null
     */
    PageResult<OrderVO> pageMyOrders(ShopOrderQueryDTO query);

    /**
     * 分页查全部会员的订单 —— 管理端「订单管理」页面。
     *
     * <p>{@code GET /api/admin/orders}
     *
     * <p>★ 和 {@link #pageMyOrders} 最大的区别是<b>没有会员隔离</b>：
     * 管理员要看到所有人的订单（这是管理员的职责，不是漏洞）。
     * 安全边界由 {@code AdminAuthInterceptor} 对 {@code /api/admin/**}
     * 的拦截提供，见 {@code OrderAdminMapper} 的类注释。
     *
     * <p>★ 返回的 {@code AdminOrderVO} 比用户端多两个会员字段，好让管理员
     * 知道每一单是谁下的。
     *
     * @param query 分页 + 可选的 status / orderNo（精确）/ memberKeyword（模糊）
     * @return 分页结果，没有订单时是空列表而不是 null
     */
    PageResult<AdminOrderVO> pageAdminOrders(OrderQueryDTO query);

    /**
     * 发货 —— <b>管理端操作，作用于别人的订单</b>。
     *
     * <p>{@code POST /api/admin/orders/{orderNo}/ship}
     *
     * <p>只有「已付款」的订单能发货。其余状态（待付款、已发货、已完成、已取消）
     * 一律返回 1002。
     *
     * <p>⚠️ <b>不碰库存。</b> 扣库存在下单那一刻就发生了，
     * 发货只是「东西出库了」的状态变更，这里再动库存就是重复扣减。
     *
     * <p>⚠️ 这个操作<b>不可逆</b>：没有「取消发货」接口。
     * 所以前端要加二次确认（管理端页面已经这么做）。
     *
     * @param dto 承运商 + 快递单号。<b>★ 里程碑 18 起必填</b> ——
     *            这两个字段此前根本不存在（接口没有请求体）。
     *            为什么不允许留空，见 {@code OrderAdminMapper.markShipped}
     * @return 发货后的订单（含明细），前端可以直接用它刷新那一行，不用重查列表
     */
    AdminOrderVO ship(String orderNo, OrderShipDTO dto);

    /**
     * 确认收货 —— 用户操作自己的订单。
     *
     * <p>{@code POST /api/shop/orders/{orderNo}/complete}
     *
     * <p>只有「已发货」的订单能确认收货。已付款的还没发货，确认什么？
     *
     * <p><b>★★ 这里【绝不归还库存】—— 这是本方法最容易被改错的地方。</b>
     * 看到 {@link #cancel} 会还库存，很容易想给这里也对称地补一次。
     * <b>那是超卖</b>：
     * <pre>
     *   取消     ：货从来没发出去，还在仓里   → 还回去是对的
     *   确认收货 ：货【已经寄到买家手里了】    → 还回去 = 凭空多出一件可以卖的货
     * </pre>
     * 「已完成」表示这笔交易结束了，不是「这笔交易没发生」。
     *
     * @return 确认后的订单（含明细）
     */
    OrderVO complete(String orderNo);
}
