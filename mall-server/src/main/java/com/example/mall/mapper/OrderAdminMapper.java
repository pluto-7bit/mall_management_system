package com.example.mall.mapper;

import com.example.mall.dto.OrderQueryDTO;
import com.example.mall.vo.AdminOrderVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理端订单数据访问接口 —— <b>全项目第二个绕过「会员隔离」的订单查询</b>。
 *
 * <h3>★ 为什么单独一个文件，而不是塞进 OrderMapper？</h3>
 *
 * <p>因为 {@code OrderMapper} 的类注释是一份<b>契约</b>：
 * 「这里的查询方法全部带着 {@code memberId} 条件」。
 * 在同一个文件里加一个不带 {@code memberId} 的方法，那份契约就变成假的了 ——
 * 下次有人打开 {@code OrderMapper} 想确认「查订单是不是都带会员隔离」，
 * 他会得到一个错误的答案。
 *
 * <p><b>★ 所以本项目的做法是：把例外隔离成文件边界。</b>
 * 这样「哪些查询绕过了会员隔离」变成一句 grep 就能审计的事，
 * 而不是要去逐行读每个方法。这是真实系统里隔离 admin / system 查询的常规做法。
 * 上一次这么做是 {@code OrderTimeoutMapper}（里程碑 9）。
 *
 * <h3>★★ 但这里的豁免理由，和 OrderTimeoutMapper 完全不是一回事</h3>
 *
 * <p>这一点必须讲清楚，否则「照抄上一个例外的注释」就会把话说错：
 *
 * <pre>
 *                      OrderTimeoutMapper          OrderAdminMapper（本文件）
 *   谁在用             定时任务（系统内部）           管理员（真人，通过 HTTP）
 *   代表谁的视角        系统                        管理员这个【身份】
 *   结果会到客户端吗    永远不会                    会（管理端页面直接渲染）
 *   靠什么挡住越权      「结果不出系统」这条纪律      AdminAuthInterceptor 对 /api/admin/** 的拦截
 * </pre>
 *
 * <p>特别注意<b>最后两行的区别</b>：
 * {@code OrderTimeoutMapper} 之所以安全，是因为它的查询结果
 * <b>从来不会被拼进任何给用户的响应里</b>；
 * 而这里的 {@code AdminOrderVO} 是<b>直接渲染到管理端页面上的</b>。
 * 如果把前者那句「结果不会返回给客户端」抄过来当挡箭牌，
 * 那就是一句<b>听起来很专业、实际上不成立</b>的注解 —— 比没有注解更危险。
 *
 * <p><b>★ 那这里真正靠什么挡住越权？靠拦截器，而不是靠数据本身。</b>
 *
 * <p>管理员这个身份的意义就是「不受数据归属约束」——
 * 「这条订单是不是我的」对管理员来说是个没有意义的问题，
 * 发货本来就是在操作<b>别人的</b>订单。所以查询里不能带 memberId，
 * 这不是疏忽，是语义上就不该有。
 *
 * <p>「谁算管理员」由 {@code AdminAuthInterceptor} 对 {@code /api/admin/**}
 * 一刀切判定（路径前缀 → 整个 Controller 都在保护范围内）。
 * <b>豁免不是没有边界，而是边界换了地方。</b>
 *
 * <p>⚠️ 本项目<b>没有管理员权限模型</b>：任何登录的管理员都能发货、
 * 能看全部订单。这是一个<b>明知的简化</b>，不是遗漏 ——
 * 真实系统里这里会是一个 {@code admin_role} / 权限点，
 * 但那是「权限系统」这个独立话题，不在本项目范围内。
 * 之所以要写出来，是因为<b>明知的简化可以接受，被误以为已经做了的简化不行</b>。
 *
 * <h3>★ 一条仍然有效的纪律</h3>
 *
 * <p>和 {@code OrderItemMapper} 一样：<b>不要直接从 Controller 调这个接口。</b>
 * 不是因为安全（它本来就该被 Controller 调），
 * 而是因为「发货」这件事的规则（<b>只有「已付款」能发货</b>）
 * 定义在 Service 里 —— 直接调 Mapper 就绕过了那层判断。
 * 规则只该有一个定义处。
 */
public interface OrderAdminMapper {

    /**
     * 分页查全部会员的订单（可按状态、订单号、会员模糊筛选）。
     *
     * <p>★ 返回 {@code AdminOrderVO}，比用户端那个多两个会员字段。
     *
     * <p>⚠️ 这里的返回类型是 VO 而不是实体，所以
     * {@code OrderMapper.baseColumns} 那份列清单可以直接复用 ——
     * 这也是里程碑 10 给那份清单的每列都加上 {@code o.} 前缀的原因：
     * 本查询要 {@code LEFT JOIN member}，而 orders 和 member
     * 都有 id / status / create_time，不加前缀会直接报
     * "Column 'id' in field list is ambiguous"。
     * 详见 {@code OrderMapper.xml} 里 baseColumns 的注释。
     *
     * <p>⚠️ 它<b>不查明细</b>。明细由 Service 用一次
     * {@code OrderItemMapper.selectByOrderIds} 批量装上。
     *
     * @return 不会为 null（没有数据时是空列表）
     */
    List<AdminOrderVO> selectAdminPage(OrderQueryDTO query);

    /**
     * 数全部会员有多少单（条件和 {@link #selectAdminPage} 完全一致）。
     *
     * <p>⚠️ 它<b>不 join member 表</b>，即使 {@link #selectAdminPage} join 了。
     * 会员筛选条件写成子查询（{@code member_id IN (SELECT id FROM member WHERE ...)}）
     * 而不是 {@code m.username LIKE ...}，就是为了让同一份条件片段
     * 能同时用在「有 join 的列表查询」和「没 join 的 count 查询」里。
     * 详见 {@code OrderAdminMapper.xml}。
     *
     * @return 条数，没有时返回 0
     */
    long countAdminQuery(OrderQueryDTO query);

    /**
     * 按订单号查订单，<b>不带会员条件</b>，返回带会员字段的 {@code AdminOrderVO}。
     *
     * <p><b>★ 为什么这个方法必须存在？</b>
     *
     * <p>因为它服务于 {@link #markShipped} ——
     * 条件更新拿到 {@code affected = 0} 时，必须<b>重查一次</b>
     * 才能给出准确的原因（「订单不存在」还是「当前状态不允许发货」），
     * 这是 {@code markPaid} / {@code markCancelled} 已经确立的写法。
     *
     * <p>而这次重查<b>没有 memberId 可以带</b>：管理员发货本来就是在
     * 操作别人的订单，他不知道也不关心这单是谁的。
     *
     * <p>⚠️ 为什么不拆成「先用会员维度的查询查出 memberId，
     * 再用带 memberId 的方法查」？因为那要<b>多读一次数据库</b>，
     * 而且中间隔着一个时间窗（TOCTOU）—— 两次查询之间订单可能已经被改了。
     * 为了「形式上更隔离」，换来了一个更慢、而且更不准确的实现。
     * <b>形状上的洁癖不该以正确性为代价。</b>
     *
     * <p>★ <b>为什么它返回 AdminOrderVO 而不是 Order？</b>
     * 因为发货成功后 Service 要把这笔订单<b>原样返回给管理端页面</b>，
     * 而那个页面要显示「这单是谁的」——
     * 如果只返回 {@code Order}，Service 就还要再拼一次会员信息，
     * 或者前端只能重新拉一遍列表。
     * <b>一次查询同时满足「诊断失败原因」和「构建响应」两个用途</b>，
     * 比开两个方法各查一次干净。
     *
     * @return 查不到时返回 null
     */
    AdminOrderVO selectAdminByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 标记订单为已发货。
     *
     * <p>条件更新的道理和 {@code OrderMapper.markPaid} 完全一样，
     * 这里只说它特有的三点。
     *
     * <h4>1. 没有 {@code memberId} 条件 —— 这是本方法的重点</h4>
     *
     * <p>这是全项目<b>唯一一个不带会员隔离的【写】操作</b>。
     * 理由见类注释：管理员发货就是在操作别人的订单，
     * 加 memberId 反而是语义错误。安全边界由
     * {@code AdminAuthInterceptor} 在路径层面提供。
     *
     * <p>（⚠️ 顺带更正 {@code OrderTimeoutMapper} 注释里那句
     * 「隔离的例外只存在于『读』，不存在于『写』」——
     * 那句话在里程碑 10 之后<b>只对那个文件成立</b>了。
     * 它没有失效，但它的适用范围比原来小了，所以两处都补了说明。）
     *
     * <h4>2. {@code status = 1} 是状态机约束，也是并发闸门</h4>
     *
     * <p>发货在状态图里只有一条边：<b>已付款 → 已发货</b>。
     * 待付款的订单还没收到钱，发什么货？已取消的更不用说。
     * 这条约束<b>写在 SQL 里，不靠前端隐藏按钮</b> ——
     * 前端只是个界面，绕过它直接调接口是很容易的事。
     *
     * <p>同时它也是并发闸门：两个管理员同时点发货，
     * 只有一个能拿到 {@code affected = 1}，另一个拿到 0（→ 报「状态不允许发货」），
     * 不会出现「发两次货、写了两次 ship_time」。
     *
     * <h4>3. 不碰库存 —— 和 {@code markCompleted} 一样</h4>
     *
     * <p>发货只是「东西出库了」的状态变更，扣库存发生在下单那一刻
     * （{@code decreaseSkuStock}）。这里再去动库存就是重复扣减。
     *
     * @return 影响行数。<b>1 = 发货成功；0 = 订单不存在 / 状态不是已付款</b> ——
     *         返回 0 时调用方要用 {@link #selectAdminByOrderNo} 重查一次，
     *         好把两种原因分辨成准确的错误信息
     */
    int markShipped(@Param("orderNo") String orderNo);
}
