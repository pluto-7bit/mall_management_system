package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.BusinessRules;
import com.example.mall.common.LoginUser;
import com.example.mall.common.OrderSource;
import com.example.mall.common.OrderStatus;
import com.example.mall.common.PageResult;
import com.example.mall.common.PayMethod;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.dto.BuyNowDTO;
import com.example.mall.dto.CartOrderDTO;
import com.example.mall.dto.OrderBaseDTO;
import com.example.mall.dto.OrderQueryDTO;
import com.example.mall.dto.ShopOrderQueryDTO;
import com.example.mall.entity.MemberAddress;
import com.example.mall.entity.Order;
import com.example.mall.entity.OrderItem;
import com.example.mall.entity.Product;
import com.example.mall.mapper.MemberAddressMapper;
import com.example.mall.mapper.OrderAdminMapper;
import com.example.mall.mapper.OrderItemMapper;
import com.example.mall.mapper.OrderMapper;
import com.example.mall.mapper.OrderTimeoutMapper;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.service.CartService;
import com.example.mall.service.OrderService;
import com.example.mall.util.OrderNoGenerator;
import com.example.mall.vo.AdminOrderVO;
import com.example.mall.vo.OrderItemVO;
import com.example.mall.vo.OrderVO;
import com.example.mall.vo.ShopProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 下单业务实现。
 *
 * <h3>★ 这个类里最值得学的三件事</h3>
 *
 * <ol>
 *   <li><b>事务边界为什么画在那里</b> —— 见 {@link #doCreate}</li>
 *   <li><b>幂等为什么要写在事务外面</b> —— 见 {@link #create}</li>
 *   <li><b>清购物车为什么必须等事务提交</b> —— 见 {@code doCreate} 结尾</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final MemberAddressMapper addressMapper;
    private final CartService cartService;

    /**
     * ★ 只有超时扫描用它。之所以单独一个 Mapper 而不是复用 {@code OrderMapper}，
     * 理由见 {@code OrderTimeoutMapper} 的类注释 —— 它是全项目唯一一个
     * 不按会员隔离的订单查询，把它隔离成文件边界，
     * 才能让 {@code OrderMapper} 的「所有查询都带 memberId」那份契约
     * 重新变成真话。
     */
    private final OrderTimeoutMapper orderTimeoutMapper;

    /**
     * ★ 只有管理端的订单列表和发货用它。单独一个文件而不是复用
     * {@code OrderMapper}，理由见 {@code OrderAdminMapper} 的类注释。
     *
     * <p>⚠️ 注意它的豁免理由<b>和上面那个 {@code orderTimeoutMapper} 不一样</b>：
     * 那个靠「结果不出系统」，这个的结果<b>会</b>渲染在管理端页面上，
     * 靠的是 {@code AdminAuthInterceptor} 对 {@code /api/admin/**} 的拦截。
     * 两者都是「会员隔离的例外」，但不是同一类例外 —— 别把它们当成一件事。
     */
    private final OrderAdminMapper orderAdminMapper;

    /**
     * 待付款订单的支付时限（分钟）。超过它就自动取消。
     *
     * <p><b>★ 为什么做成配置而不是写成常量？</b>
     * 因为它是<b>业务参数</b>，不是技术参数 —— 真实项目里运营会改它
     * （大促的时候可能放宽到 2 小时，清库存的时候可能收紧到 15 分钟）。
     * 而且它同时被三个地方用到（支付校验、超时扫描、给前端的 payDeadline），
     * 做成配置才只有一处定义。
     *
     * <p>用 {@code @Value} 注入而不是绑定一个 {@code @ConfigurationProperties} 类：
     * 本项目的配置项一共就这几个，为它们建一个类反而是多余的抽象。
     * <b>项少的时候用 @Value，多到一屏看不完再考虑抽配置类。</b>
     */
    @Value("${mall.order.pay-timeout-minutes}")
    private int payTimeoutMinutes;

    /**
     * 超时扫描单次最多处理多少单。
     *
     * <p>{@code limit} 不是优化，是<b>自我保护</b>：积压了几十万单时，
     * 不限量的查询会把它们一次全拉进内存，而且每 10 秒一次。
     * 有上限之后积压会按 {@code limit / 扫描间隔} 的速度慢慢排掉。
     * 详见 {@code OrderTimeoutMapper.selectTimeoutCandidates}。
     */
    @Value("${mall.order.timeout-batch-limit}")
    private int timeoutBatchLimit;

    /**
     * ★ 用它来开事务，而不是给方法加 {@code @Transactional}。
     *
     * <p>理由是一个 Spring 的经典坑：<b>{@code @Transactional} 靠代理生效，
     * 而"自己调自己"不经过代理。</b>
     * <pre>
     *   public OrderVO create(...) {          // 没加注解
     *       return doCreate(...);             // ← this.doCreate()，不经过代理
     *   }
     *   &#64;Transactional
     *   private OrderVO doCreate(...) { ... } // ← 注解【完全无效】，事务不会开
     * </pre>
     * 这种失效是<b>静默的</b>：不报错、不警告，事务就是不生效。
     * 而后果很严重 —— 下单要写三张表，没有事务就意味着
     * 「扣了库存但订单没建成」这种脏数据会出现。
     *
     * <p>解决办法有三条路：
     * <ol>
     *   <li>把 {@code doCreate} 挪到另一个 Bean 里（要拆类，动静大）</li>
     *   <li>注入自己（{@code @Lazy OrderService self}）再调 —— 能用，但很别扭</li>
     *   <li><b>用 {@code TransactionTemplate} 显式地开事务</b>（本项目选的）</li>
     * </ol>
     *
     * <p>选第 3 条的好处不只是"绕开了坑"，还有两个实在的优点：
     * <pre>
     *   1. 事务边界【看得见】—— 读到 execute(...) 就知道这里开事务了，
     *      而 @Transactional 藏在注解里，读代码时容易漏掉
     *   2. 事务范围能精确控制 —— 下面 doCreate 之外还有"幂等查询"
     *      和"捕获唯一键冲突后重查"，这两步必须在事务【外面】，
     *      用注解很难表达；
     *   3. 能在 execute 外面包 try-catch 处理提交时的异常，
     *      而 @Transactional 的提交发生在方法返回【之后】，
     *      在方法内部根本 catch 不到提交阶段的异常。
     * </pre>
     * <b>注解说"用哪个"是次要的，说清"为什么不用另一个"才是重点。</b>
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 内部用来承载"买什么、买几件"的小结构。
     *
     * <p>用 record 而不是普通类：它只是个不可变的数据载体，
     * 没有行为，record 一行就能表达清楚，还自带构造器、
     * getter、equals、hashCode、toString。
     *
     * <p>★ 为什么不直接用 {@code Map<Long, Integer>}？
     * 因为那样的话，方法签名是 {@code create(Map<Long,Integer>)} ——
     * 读到的人得猜"这个 Map 的 key 是商品 id 吗？value 是数量吗？"。
     * <b>用一个有名字的类型，等于把"这个数据结构是什么意思"
     * 写进签名里，不用靠注释和记忆。</b>
     * （购物车那边返回 Map 是可以的，因为它是"读出来的原始数据"；
     *   这里是"下单要用的订单行"，有自己的含义，就该有自己的类型。）
     */
    private record OrderLine(Long productId, Integer quantity) {
    }

    // ==========================================================================
    // 两个入口：差异只在这里
    // ==========================================================================

    @Override
    public OrderVO createFromCart(CartOrderDTO dto) {
        Long memberId = currentMemberId();

        // ★★★ 第一步必须是幂等检查，不能排在"解析购物车"后面。
        //    这一条是被测试逼出来的，见下面 findExisting 的注释。
        OrderVO hit = findExisting(memberId, dto.getIdempotencyKey());
        if (hit != null) {
            return hit;
        }

        // ★ 差异一：数量从 Redis 购物车里读，不信客户端传的任何数量
        List<OrderLine> lines = linesFromCart(dto.getProductIds());
        // ★ 差异二由 OrderSource.CART 表达：下单成功后要清购物车
        return submit(OrderSource.CART, lines, dto, memberId);
    }

    @Override
    public OrderVO createByBuyNow(BuyNowDTO dto) {
        Long memberId = currentMemberId();

        OrderVO hit = findExisting(memberId, dto.getIdempotencyKey());
        if (hit != null) {
            return hit;
        }

        // ★ 差异一：数量只能来自请求参数 —— 这条路没经过购物车，
        //   服务端没有别的真相来源，所以只能采信客户端说的数字。
        //   ⚠️ 但"采信数量"不等于"采信价格"：
        //      价格永远是服务端现查的，见 doCreate。
        List<OrderLine> lines = List.of(new OrderLine(dto.getProductId(), dto.getQuantity()));
        return submit(OrderSource.BUY_NOW, lines, dto, memberId);
    }

    /**
     * 幂等检查：这个会员之前有没有用这个键成功下过单？
     *
     * <h4>★★★ 为什么这个方法必须在「解析请求」之前调用？</h4>
     *
     * <p>这是写这个模块时被测试抓出来的一个真实 bug，很值得记住。
     *
     * <p>最初的写法是把幂等检查放在了「读购物车」<b>之后</b>：
     * <pre>
     *   createFromCart(dto) {
     *       lines = linesFromCart(dto.productIds);   // ← 先读购物车
     *       if (已经有这个键的订单) return 它;        // ← 再查幂等
     *       ...
     *   }
     * </pre>
     * 看起来没问题，但第一次提交成功之后<b>购物车被清空了</b>
     * （这正是下单该做的事）。于是第二次提交时：
     * <pre>
     *   读购物车 → 空了 → 抛 1003「购物车里没有这件商品」❌
     *   幂等检查根本执行不到
     * </pre>
     * 结果就是：<b>用户网络超时重试，看到一句莫名其妙的报错，
     * 而他的订单其实已经建好了。</b>他很可能以为没成功，
     * 换个购物车再下一单 —— 于是真的买了两次。
     *
     * <h4>★ 提炼出来的规则</h4>
     *
     * <p><b>幂等检查是「这件事我做过了吗」，它必须在任何可能失败的
     * 前置校验之前。</b>原因很朴素：
     * <pre>
     *   重试的请求，面对的是一个【已经被第一次改动过的世界】。
     * </pre>
     * 第一次成功会消耗掉一些东西 —— 购物车被清了、库存被扣了、
     * 优惠券被用了。所以第二次来的时候，「购物车里有没有这件商品」
     * 这个问题的答案已经变了，前置校验必然失败。
     *
     * <p>而这正是幂等要解决的问题：<b>重试不应该重新走一遍判断，
     * 而应该直接返回第一次的结果。</b>
     * 「这件事做过没有」这个问题不依赖于任何别的东西，
     * 所以它必须排在最前面 —— 它本来就不该受那些会变的条件影响。
     *
     * <p>⚠️ 注意这不只是"顺序调整"那么简单。原来那份代码的写法
     * 会让人以为「幂等是下单流程里的一步」，
     * 而正确的理解是<b>「幂等是进入下单流程的一道门」</b>。
     * 门要在门口，不在客厅里。
     *
     * @return 之前下过的订单；没有则返回 {@code null}（正常情况，继续下单）
     */
    private OrderVO findExisting(Long memberId, String idempotencyKey) {
        Order existing = orderMapper.selectByMemberAndKey(memberId, idempotencyKey);
        if (existing == null) {
            return null;
        }
        log.info("幂等命中，直接返回已有订单: memberId={}, key={}, orderNo={}",
                memberId, idempotencyKey, existing.getOrderNo());
        return toVO(existing);
    }

    /**
     * 从购物车里取出要结算的商品和数量，并校验「勾选的商品确实在车里」。
     *
     * <p><b>★ 为什么必须有这个校验？</b>
     * 前端会说"我要结算 3 号和 7 号"，但前端说的话不能直接信 ——
     * 用户可能在另一个标签页里把 7 号删了，也可能有人直接构造请求
     * 传一个自己车里根本没有（甚至不存在）的商品 id 过来。
     * <b>凡是客户端"声称"的事实，服务端都要能用自己手里的数据验证一遍。</b>
     */
    private List<OrderLine> linesFromCart(List<Long> productIds) {
        // 去重：万一客户端传了 [3, 3]，不去重的话会生成两行明细、
        // 扣两次库存。而 Redis Hash 的 field 天然唯一，
        // 读出来的数量只有一份，所以重复的 id 前面会查出同一个数量，
        // 结果就是"同一商品买两份"，金额却只算了一份 —— 数据就不一致了。
        List<Long> distinctIds = productIds.stream().distinct().toList();

        Map<Long, Integer> quantities = cartService.readQuantities(distinctIds);

        List<OrderLine> lines = new ArrayList<>(distinctIds.size());
        for (Long pid : distinctIds) {
            Integer qty = quantities.get(pid);
            if (qty == null) {
                // 勾了一件"购物车里没有"的商品。可能是：
                //   - 用户开了两个标签页，另一个把它删了
                //   - 有人直接构造请求
                // 不管哪种，都不能继续 —— 因为"买几件"无从得知，
                // 而拿不到数量就下单，等于凭空猜一个数字。
                throw new BusinessException(ResultCode.NOT_FOUND,
                        "购物车里没有这件商品，请刷新页面后重试");
            }
            lines.add(new OrderLine(pid, qty));
        }
        return lines;
    }

    // ==========================================================================
    // 主流程
    // ==========================================================================

    /**
     * 提交下单 —— <b>★ 注意这个方法【没有】开事务，事务在它内部更小的范围里。</b>
     *
     * <p>调用它的前提是：幂等检查已经做过了（在 {@link #findExisting}），
     * 并且「买什么、买几件」已经确定（{@code lines}）。
     *
     * <h4>★★ 为什么"冲突之后的重查"必须在事务外面？</h4>
     *
     * <p>这是个很实际的细节。假设把整段都放进一个事务里：
     * <pre>
     *   &#64;Transactional
     *   下单() {
     *       if (已经有这个 key 的订单) return 它;
     *       insert 订单...
     *   }
     * </pre>
     * 两个并发请求同时进来，幂等检查都返回"没有"（因为都还没提交），
     * 于是都去 insert。数据库的唯一索引会让第二个 insert 失败并抛
     * {@code DuplicateKeyException}。
     *
     * <p>这时候我们想"既然有唯一索引拦着，那我捕获它、再查一次、
     * 把前一个请求建的订单返回" —— <b>但在事务里做不到。</b>
     * 因为 Spring 的事务里一旦抛出异常，事务就被标记为
     * {@code rollback-only}，后面再也做不了任何查询，
     * 即使你 catch 了异常，提交时也会抛
     * {@code UnexpectedRollbackException}。
     *
     * <p>所以结构必须是：<b>幂等检查和"冲突之后的重查"都在事务外，
     * 事务只包住"真正写库"的那一小段。</b>
     * 这就是用 {@code TransactionTemplate} 而不是
     * 方法级 {@code @Transactional} 的第三个好处（见字段注释）。
     *
     * <p><b>★ 那既然幂等已经在门口查过一次了，这里的重查是不是多余？</b>
     * 不多余，而且必须留着。两者的分工是：
     * <pre>
     *   findExisting 的查询  →  处理【串行】的重复提交（用户自己连点两次）
     *   这里 catch 后的重查  →  处理【并发】的重复提交（两个请求同时进来，
     *                          第一次查询时对方都还没提交，两边都查不到）
     * </pre>
     * 前者是常见情况（成本低，先查一次就挡住了），
     * 后者是罕见但必须正确的情况（只能靠唯一索引 + 重查兜住）。
     * <b>「先查一次」和「靠唯一索引兜底」不是二选一，而是要一起用。</b>
     */
    private OrderVO submit(OrderSource source, List<OrderLine> lines,
                           OrderBaseDTO dto, Long memberId) {

        // 这一行是防御性检查，防止把空列表传给 SQL 里的 IN ()。
        // 正常情况下走不到：CART 路的 productIds 有 @NotEmpty，
        // BUY_NOW 路固定一行，且 linesFromCart 要么返回非空要么抛异常。
        // ★ 但"正常情况下走不到"不等于"可以不写"—— 空的 IN () 是
        //   SQL 语法错误，会变成一个 500。宁可提前抛一个说得清的错误。
        if (lines.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有要结算的商品");
        }

        try {
            return transactionTemplate.execute(status -> doCreate(source, lines, dto, memberId));
        } catch (DuplicateKeyException e) {
            // 走到这里说明写库时撞了唯一索引。要分辨是哪一个索引撞的：
            Order won = orderMapper.selectByMemberAndKey(memberId, dto.getIdempotencyKey());
            if (won != null) {
                // ---- 情况一：幂等键撞了 ----
                // 说明在我们"查完没有"和"插入"之间，另一个并发的相同请求
                // 已经把这个键的订单建好了。这是**并发重复提交**，
                // 而不是错误 —— 把先到的那笔返回给用户，两次点击得到同一个结果。
                log.info("并发重复提交，返回先到的订单: memberId={}, key={}, orderNo={}",
                        memberId, dto.getIdempotencyKey(), won.getOrderNo());
                return toVO(won);
            }

            // ---- 情况二：订单号撞了 ----
            // 幂等键没查到，说明唯一索引被拒的原因是 uk_order_no ——
            // 生成的订单号撞上了一个已有的。概率极低（见 OrderNoGenerator），
            // 但既然发生了，换一个订单号重试一次就好，用户完全感知不到。
            //
            // ★ 为什么要区分这两种情况？
            //   因为它们的处理方式完全不同：一个是"直接返回已有订单"，
            //   一个是"换个号重来"。如果只catch不区分，
            //   订单号冲突就会被当成"重复下单"去查幂等键，
            //   查不到（因为压根不是幂等冲突），然后就不知道该怎么办了。
            //   **捕获了异常之后，必须能分辨"是谁抛的"。**
            log.warn("订单号冲突，换一个重试一次: memberId={}, key={}",
                    memberId, dto.getIdempotencyKey(), e);
            try {
                return transactionTemplate.execute(status -> doCreate(source, lines, dto, memberId));
            } catch (DuplicateKeyException retryEx) {
                // 连撞两次。这时不该再重试了 —— 要么是随机数生成有问题，
                // 要么是别的地方出了系统性问题，需要人来看日志。
                log.error("订单号连续两次冲突，放弃重试: memberId={}, key={}",
                        memberId, dto.getIdempotencyKey(), retryEx);
                throw new BusinessException(ResultCode.ERROR, "下单失败，请稍后重试");
            }
        }
    }

    /**
     * 真正写库的那一段。<b>★ 事务边界就是这一个方法。</b>
     *
     * <p>事务里做的事，按顺序：
     * <ol>
     *   <li>校验并快照收货地址</li>
     *   <li>批量查商品（只认上架商品）</li>
     *   <li>逐个扣库存（<b>原子操作，靠影响行数判断成败</b>）</li>
     *   <li>算总金额</li>
     *   <li>写订单主表 + 批量写明细</li>
     * </ol>
     *
     * <p><b>★ 为什么这几步必须在同一个事务里？</b>
     * 因为它们是一个整体。设想第 3 步扣了两件商品的库存，
     * 第 3.5 件发现库存不足抛了异常 —— 如果没有事务，
     * 前两件的库存就白白扣掉了，用户没下单成功却少了库存。
     * 有事务，抛异常 = 全部回滚，库存回到原样。
     *
     * <p><b>★ 那为什么"幂等查询"和"清购物车"不在事务里？</b>
     * 因为它们不属于"一起成功或一起失败"这个整体：
     * <pre>
     *   幂等查询是【读】，放进事务只是白白延长事务（多持有连接和锁）
     *   清购物车是【Redis 操作】，它不参与 MySQL 事务，
     *   而且它必须等 MySQL 提交成功之后再执行 —— 否则事务回滚了，
     *   购物车却已经清了，用户的东西凭空消失。
     * </pre>
     * <b>事务边界应该正好圈住"必须一起成败"的那些写入，多一步都是浪费，
     * 少一步就是脏数据。</b>
     */
    private OrderVO doCreate(OrderSource source, List<OrderLine> lines,
                             OrderBaseDTO dto, Long memberId) {

        // ---- 1. 收货地址：必须查"我的地址" ----
        // ★★ 用 selectByIdAndMember 而不是 selectById，这是安全边界。
        //    dto.addressId 是客户端能随便填的。只按 id 查的话，
        //    会员 A 下单时填一个 B 的地址 id，就会：
        //      - 把货寄到 B 家
        //      - 而且 A 的订单详情里会显示 B 的姓名和电话（信息泄漏）
        //    报错统一用"收货地址不存在"，不区分"不存在"和"不是你的"，
        //    理由和地址模块一致（不给外界探测 id 是否存在的机会）。
        MemberAddress address = addressMapper.selectByIdAndMember(dto.getAddressId(), memberId);
        if (address == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "收货地址不存在，请重新选择");
        }

        // ---- 2. 批量查商品 ----
        // 用一次 IN 查询，不是循环 selectShopById（那是 N+1）。
        // selectShopByIds 自带 status = 1，所以下架商品在这里就查不出来。
        List<Long> productIds = lines.stream().map(OrderLine::productId).toList();
        List<ShopProductVO> products = productMapper.selectShopByIds(productIds);

        Map<Long, ShopProductVO> productMap = new HashMap<>(products.size());
        for (ShopProductVO p : products) {
            productMap.put(p.getId(), p);
        }

        for (OrderLine line : lines) {
            if (!productMap.containsKey(line.productId())) {
                // 购物车里的商品可能在下单前被下架/删除了。
                // 和商品详情接口一样不区分"不存在"和"已下架"。
                throw new BusinessException(ResultCode.NOT_FOUND,
                        "有商品已下架或不存在，请刷新购物车后重试");
            }
            // ★ 单个商品的数量上限。放在这里而不是 DTO，理由见 BusinessRules：
            //   购物车结算这条路的前端根本不传数量，DTO 根本拦不到它 ——
            //   数量是从 Redis 读出来的，只有 Service 才拿得到。
            //   （这正是"业务规则要放在拿得到数据的那一层"。）
            if (line.quantity() > BusinessRules.MAX_QUANTITY_PER_ITEM) {
                ShopProductVO p = productMap.get(line.productId());
                throw new BusinessException(ResultCode.CART_QUANTITY_LIMIT,
                        "「" + p.getName() + "」最多购买 "
                                + BusinessRules.MAX_QUANTITY_PER_ITEM + " 件");
            }
        }

        // ---- 3. 扣库存：★★ 防超卖的关键在这里 ----
        for (OrderLine line : lines) {
            int affected = productMapper.decreaseStock(line.productId(), line.quantity());

            if (affected == 0) {
                // 影响 0 行 = WHERE 里的 stock >= ? 不成立 = 库存不够。
                // 注意这里【没有】在扣之前查过库存 —— 那会引入竞态。
                // 判断完全依赖数据库这条原子 UPDATE 的结果。
                //
                // ★ 失败路径上再查一次，是为了给出**准确**的原因和剩余量。
                //   上面 productMap 里的 stock 是下单开始时读到的旧值，
                //   在并发场景下可能已经变了，报给用户就是错的数字。
                //   错误路径很少走到，多查一次库完全可以接受 ——
                //   **优化要针对热路径，错误路径上准确性比性能重要。**
                Product current = productMapper.selectEntityById(line.productId());
                if (current == null || !Integer.valueOf(1).equals(current.getStatus())) {
                    throw new BusinessException(ResultCode.NOT_FOUND,
                            "有商品已下架，请刷新购物车后重试");
                }
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH,
                        "「" + current.getName() + "」库存不足，仅剩 " + current.getStock() + " 件");
            }
        }

        // ---- 4. 算总金额 ----
        // ★ 价格来自 productMap（也就是【数据库里现在的价格】），
        //   不是客户端传的。这是"金额永远由服务端算"的落地。
        //
        // ★ 每一步都用 BigDecimal 的方法，不写成 price * quantity 这种
        //   运算符形式（BigDecimal 没有运算符重载，写不了，这是好事）。
        //   全程不出现 double，避免 0.1 + 0.2 那类精度问题。
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>(lines.size());

        for (OrderLine line : lines) {
            ShopProductVO p = productMap.get(line.productId());

            // 小计 = 单价 × 数量。单价是 DECIMAL(10,2)，乘出来的小数位数
            // 最多也就是 2 位，不会出现"两位以上的钱"。
            BigDecimal subtotal = p.getPrice().multiply(BigDecimal.valueOf(line.quantity()));
            totalAmount = totalAmount.add(subtotal);

            OrderItem item = new OrderItem();
            item.setProductId(p.getId());
            // ★ 快照：把商品名和单价抄进明细。
            //   商品明天改名或涨价，这笔订单不受影响。
            item.setProductName(p.getName());
            item.setPrice(p.getPrice());
            item.setQuantity(line.quantity());
            item.setSubtotal(subtotal);
            items.add(item);
        }

        // ---- 5. 写订单主表 ----
        Order order = new Order();
        order.setOrderNo(OrderNoGenerator.generate());
        order.setMemberId(memberId);

        // ★ 收货信息快照。存的是"下单这一刻的地址"，
        //   之后用户改了或删了地址簿，这笔订单不受影响。
        order.setReceiverName(address.getReceiver());
        order.setReceiverPhone(address.getPhone());
        order.setReceiverAddress(address.fullAddress());
        // 来源 id 只作追溯用，故意不加外键（否则地址就删不掉了）
        order.setAddressId(address.getId());

        order.setTotalAmount(totalAmount);
        order.setStatus(OrderStatus.PENDING_PAY);
        order.setIdempotencyKey(dto.getIdempotencyKey());
        order.setRemark(dto.getRemark());

        orderMapper.insert(order);
        // insert 之后 order.getId() 已经被回填了（useGeneratedKeys），
        // 下面写明细要用它当 order_id

        // ---- 6. 批量写明细 ----
        for (OrderItem item : items) {
            item.setOrderId(order.getId());
        }
        orderItemMapper.batchInsert(items);

        // ---- 7. 注册"提交之后"的动作：清购物车 ----
        if (source == OrderSource.CART) {
            registerCartCleanupAfterCommit(productIds, order.getOrderNo(), memberId);
        }

        log.info("下单成功: source={}, memberId={}, orderNo={}, 商品种类={}, 总金额={}",
                source.getText(), memberId, order.getOrderNo(), items.size(), totalAmount);

        // ---- 8. 把数据库生成的时间列读回来 ----
        //
        // ★★ 这一步是【必须】的，不是可选优化。原因：
        //
        //   insert 的列清单里【故意没有】create_time / update_time
        //   （见 OrderMapper.xml 里的说明：下单时间要由数据库的时钟说了算，
        //     不能用应用服务器的时钟，否则多机部署时订单顺序会乱）。
        //   它们由数据库的 DEFAULT CURRENT_TIMESTAMP 填。
        //
        //   ⚠️ 但 MyBatis 的 useGeneratedKeys 只能把【自增主键】回填到对象上，
        //      读不到 DEFAULT 生成的列。所以 insert 之后，
        //      内存里的 order 对象【缺少 createTime / updateTime】——
        //      它是一个「数据库里那行的残缺投影」。
        //
        //   这个残缺一直是存在的，只是里程碑 9 之前没人用到 createTime，
        //   所以没暴露出来。里程碑 9 加了「支付截止时刻 = 下单时间 + 支付时限」
        //   之后，payDeadlineOf 去读 order.getCreateTime() 就直接空指针了
        //   —— 这是 test-pay.py 第一个用例就抓出来的。
        //
        //   ★ 记下来的教训：**「插入后对象和数据库不再一致」是一个真实存在的状态，
        //     而且是静默的**。任何依赖「插入后的对象等于那一行」的代码都是错的，
        //     除非显式地把缺的字段补回来。补的方式有两种：
        //       ① 在 Java 里自己 set 一遍（但这里不能 —— 时间必须由数据库定）
        //       ② 重新查一次（本项目选的，见下）
        //
        // ★ 为什么不干脆在 Java 里 setCreateTime(LocalDateTime.now())？
        //   那就把「下单时间由谁定」这件事分成了两处：insert 交给数据库、
        //   对象交给应用。两边的时钟一旦不一致，就会出现
        //   「响应里说 20:00，数据库里存的是 19:59:58」这种对不上的现象。
        //   **一个字段的定义者只能有一个。**
        //
        // ★ 多一次查询值得吗？值得。理由和 toVO 里那句一样：
        //   「廉价的一致性好过昂贵的优化」。order_no 上有唯一索引，
        //   这是一次索引查找，而且它发生在【下单】这条本来就要写三张表的路径上，
        //   多一次读完全不算什么。
        Order saved = orderMapper.selectByOrderNoAndMember(order.getOrderNo(), memberId);

        // saved 为 null 在这里理论上不可能（刚插进去、同一个事务内、同一个会员）。
        // 但万一真的发生了（比如将来有人把这段挪到事务外面、或者改了隔离级别），
        // 回退到 items 已经在内存里的 order 更安全 ——
        // 只是 createTime 是 null，payDeadline 会跟着是 null，
        // 前端倒计时区不显示。**降级而不是崩掉。**
        if (saved == null) {
            log.warn("下单后回查订单失败，createTime 等数据库生成的字段将为 null: orderNo={}",
                    order.getOrderNo());
            return toVO(order);
        }
        return toVO(saved);
    }

    /**
     * 注册一个回调：等订单事务<b>提交成功之后</b>，再把这几种商品从购物车里移除。
     *
     * <h4>★★ 为什么不能直接在这里调 {@code cartService.removeItems}？</h4>
     *
     * <p>因为 Redis 操作<b>不参与 MySQL 事务</b>，它一旦执行就生效了，
     * 事务回滚不会把它撤销。于是：
     * <pre>
     *   清了购物车 → 后面某步抛异常 → MySQL 事务回滚，订单没了
     *   结果：用户购物车空了，订单却没有 ❌
     * </pre>
     * 这是很典型的一类 bug：<b>把"不会被回滚的操作"和"会被回滚的操作"
     * 混在一个事务流程里。</b>
     *
     * <p>所以顺序必须是「先提交，后清理」。
     * Spring 提供了 {@link TransactionSynchronization#afterCommit()} 这个钩子，
     * 它保证在事务<b>真正提交之后</b>才执行。
     *
     * <h4>★ 三个必须注意的点</h4>
     *
     * <ol>
     *   <li><b>回调里必须自己 try-catch。</b>
     *       这时候订单已经提交成功了 —— 那是最重要的事实，
     *       已经不可撤销。如果清理购物车失败（Redis 挂了、网络断了）
     *       还把异常抛出去，用户会看到"下单失败"，
     *       但实际上订单建好了、库存也扣了。他会再下一单 ——
     *       于是重复下单。<b>Redis 出问题绝不能报告成"下单失败"。</b>
     *       记 error 日志，人工核对就好。</li>
     *
     *   <li><b>这个回调跑在同一条线程上。</b>
     *       所以 {@code cartService.removeItems()} 里用
     *       {@code UserContext.require()} 取会员 id 是**能取到**的
     *       （拦截器要等整个请求处理完才清 ThreadLocal）。
     *       ⚠️ 但这是一个**隐式依赖**：如果哪天有人把它改成异步
     *       （{@code @Async}、扔进线程池），新线程上没有 UserContext，
     *       这里就会抛 401。那时候必须改成显式传 memberId 进去，
     *       而不是让 removeItems 去猜。
     *       <b>"能跑通"和"依赖了什么才跑通"要一起记住。</b></li>
     *
     *   <li>只在 {@code source == CART} 时注册 ——
     *       立即购买压根没动过购物车，去清它是越权
     *       （用户车里可能正好有同一件商品，那是他自己加的，不该被清）。</li>
     * </ol>
     */
    private void registerCartCleanupAfterCommit(List<Long> productIds,
                                               String orderNo, Long memberId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 理论上不会发生（这个方法只在 transactionTemplate.execute 里被调）。
            // 但万一将来有人把它挪到事务外面调用，这里会安静地什么都不做 ——
            // 那是"购物车没被清"，比"抛异常导致下单失败"温和得多。
            log.error("清购物车回调注册失败：当前没有活动事务。orderNo={}, memberId={}",
                    orderNo, memberId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    cartService.removeItems(productIds);
                } catch (Exception e) {
                    // ★ 绝不能让它抛出去，理由见上面第 1 点
                    log.error("订单已创建但清理购物车失败，需人工核对: "
                                    + "orderNo={}, memberId={}, productIds={}",
                            orderNo, memberId, productIds, e);
                }
            }
        });
    }

    // ==========================================================================
    // 里程碑 9：支付 / 取消 / 超时扫描
    // ==========================================================================

    @Override
    public OrderVO getByOrderNo(String orderNo) {
        return toVO(requireOwnOrder(orderNo, currentMemberId()));
    }

    /**
     * 支付订单（模拟）。
     *
     * <h3>★★ 为什么这里【不需要】幂等键，而下单需要？</h3>
     *
     * <p>这是个很值得琢磨的对比，因为两个操作的「重复提交」问题看起来一模一样。
     *
     * <pre>
     *   下单：副作用是【新增一行】，而且可以无限次新增
     *         → 重复执行两次 = 两笔订单 = 两份库存被扣 = 真的出事了
     *         → 所以必须有一个外部提供的幂等键（idempotency_key）把它锁住
     *
     *   支付：副作用是【把一行从状态 0 推到状态 1】，这个迁移只能发生一次
     *         → WHERE status = 0 让第二次执行天然拿到 affected = 0
     *         → 状态本身就已经是幂等键了
     * </pre>
     *
     * <p><b>★ 提炼出来：当「目的状态已经达成」可以被检测出来时，就不需要额外的幂等键。</b>
     * 因为幂等键要解决的唯一问题是「我怎么知道这件事做过了」——
     * 如果答案就在数据里（status 已经是 1 了），再引入一个键就是多余的机制，
     * 而每一个多余的机制都会带来它自己的 bug（键谁来生成、存在哪、会不会被人抢注……
     * 这些坑本项目在 {@code migration-08b} 里全都踩过一遍）。
     *
     * <p><b>★ 代价是什么？</b>第二次点击会得到一个 1002 报错，
     * 而不是像下单那样静默返回第一次的结果。
     * 这是可以接受的，甚至可以说是更好的：用户连点两次支付，
     * 弹一句「订单当前是已付款，无法支付」比什么都不提示更不容易让人困惑。
     * 而且前端的按钮上有 {@code :loading}，第一道防线在那里。
     *
     * <p>（如果哪天真需要「重复支付静默成功」，改法也很简单：
     *  affected == 0 且重查发现 status 已经是 PAID 时，直接返回那个订单。
     *  但要有明确的理由再做 —— <b>不要为了「更幂等」而牺牲错误信息的准确性。</b>）
     */
    @Override
    public OrderVO pay(String orderNo, String payMethod) {
        // ★ 业务规则校验放在 Service 里，不是只在 DTO 的注解里。
        //   理由见 PayDTO 的注释：合法值清单只该有一份，而它属于业务规则。
        //   ⚠️ 这里必须先判 null 再判合法性，否则 isValid 里虽然也判了 null，
        //      但错误信息会含糊。**错误信息要精确到能直接告诉用户改什么。**
        if (!PayMethod.isValid(payMethod)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的支付方式");
        }

        Long memberId = currentMemberId();

        // ★ deadline = 此刻 − 支付时限。算好一个【绝对时刻】传给 SQL，
        //   这样 SQL 里不需要知道「30 分钟」这个数（它读不到 Spring 配置）。
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(payTimeoutMinutes);

        int affected = orderMapper.markPaid(orderNo, memberId, payMethod, deadline);

        if (affected == 0) {
            // ★★ 失败路径上重查一次，把「为什么失败」翻译成一句人话。
            //
            //   这条 UPDATE 的 WHERE 里有四个条件，任何一个不成立都会得到 0 行。
            //   直接抛一句「支付失败」是没用的 —— 用户不知道该怎么办。
            //   所以这里重查一次，把原因分辨出来。
            //   重查很便宜（order_no 上有唯一索引），而且这是一条【很少走到】的路径。
            //   **优化要针对热路径，错误路径上准确性比性能重要。**
            //   （同 doCreate 里扣库存失败后重查那一段。）
            Order current = orderMapper.selectByOrderNoAndMember(orderNo, memberId);

            if (current == null) {
                // 不存在 和 不属于你 共用这一个分支、这一个错误码。
                // 区分开来等于确认了「这个订单号存在」。
                throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
            }
            if (!Integer.valueOf(OrderStatus.PENDING_PAY).equals(current.getStatus())) {
                throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                        "订单当前是「" + OrderStatus.text(current.getStatus()) + "」，无法支付");
            }

            // 走到这里：订单存在、是你的、状态还是待付款 ——
            // 那么四个条件里唯一没过的只可能是 create_time > deadline。
            // ★ 注意这句判断是【推出来的】而不是查出来的，所以要保证推理完整：
            //   上面已经把「查不到」「不是你的」「状态不对」三种都排除掉了，
            //   剩下的可能性只有超时这一种。
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单已超过 " + payTimeoutMinutes + " 分钟支付时限，请重新下单");
        }

        log.info("支付成功: memberId={}, orderNo={}, payMethod={}",
                memberId, orderNo, PayMethod.text(payMethod));

        return toVO(requireOwnOrder(orderNo, memberId));
    }

    @Override
    public OrderVO cancel(String orderNo) {
        // ★ 用户主动取消 = 用【当前登录会员】的身份去取消。
        //   它只是 cancelInternal 一个薄壳，真正的逻辑在那里面 ——
        //   因为超时自动取消要走完全相同的逻辑（见 cancelTimeoutOrders）。
        return toVO(cancelInternal(orderNo, currentMemberId()));
    }

    /**
     * 取消一笔订单并归还库存。<b>★ 用户主动取消和超时自动取消共用这一段。</b>
     *
     * <h3>★★★ 为什么这里的步骤顺序绝对不能换</h3>
     *
     * <ol>
     *   <li>先查订单（要拿 id 去查明细，也要拿 status 去判断能不能取消）</li>
     *   <li><b>{@code markCancelled} —— 先抢状态</b></li>
     *   <li>{@code affected == 0} → 重查，按状态给出准确原因并抛 1002</li>
     *   <li><b>{@code affected == 1} → 这时才有资格去归还库存</b></li>
     * </ol>
     *
     * <p>如果把 2 和 4 换成「先还库存、再改状态」，那么两个并发的取消请求
     * <b>会各自都把库存还一遍</b> —— 因为在它们还库存的那一刻，
     * 订单状态都还是「待付款」，两边都觉得自己该还。
     * 结果是库存凭空翻倍，而且没有任何报错。
     *
     * <p><b>必须让条件更新当闸门：只有拿到 {@code affected = 1} 的那一个，
     * 才有资格往下走。</b> 仓库里没有超卖，靠的就是这一条。
     * （这和 {@code decreaseStock} 的「影响行数就是判断结果」是同一个模式。）
     *
     * <h3>★ 为什么这个方法必须开事务？</h3>
     *
     * <p>因为它有<b>多个写操作</b>：改订单状态 + 归还 N 件商品的库存。
     * 设想改完状态、还了第一件商品的库存、第二件失败了 ——
     * 如果没有事务，订单显示已取消，但第二件商品的库存永远少着。
     * <b>要么全成，要么全败。</b>
     *
     * <p>（对比一下 {@link #pay}：它只有一条 UPDATE，改完就完了，
     * 所以不需要显式事务 —— 单条 SQL 本身就是原子的。
     * <b>「要不要事务」的判断标准是「有没有多个必须一起成败的写」，
     * 不是「这个方法重不重要」。</b>）
     *
     * <h3>★ 为什么把 memberId 做成参数，而不是在里面调 currentMemberId()？</h3>
     *
     * <p>因为调用者有两种身份来源：
     * <pre>
     *   用户主动取消  → memberId 来自 JWT（UserContext）
     *   超时自动取消  → memberId 来自扫描出来的那一行数据（定时任务的线程上没有登录态，
     *                    调 UserContext.require() 会直接抛 401）
     * </pre>
     * 把身份<b>显式传进来</b>，这两种来源就能共用同一个实现。
     * 反过来如果在这里调 {@code currentMemberId()}，
     * 超时取消就必须再写一份「不带会员判断」的取消逻辑 ——
     * 而那份重复的代码里如果漏了归还库存，就是一个永远查不出来的库存泄漏。
     * <b>把变化的部分变成参数，是消除重复最便宜的办法。</b>
     *
     * @param memberId 订单的归属会员。<b>它同时是安全边界</b>：
     *                 {@code markCancelled} 会用它做条件，
     *                 传错了就取消不了（而不是取消错了）
     */
    private Order cancelInternal(String orderNo, Long memberId) {
        return transactionTemplate.execute(status -> {

            Order order = orderMapper.selectByOrderNoAndMember(orderNo, memberId);
            if (order == null) {
                // 和 pay 一致：不存在 和 不属于你 共用一个错误码
                throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
            }

            // ---- ★★ 第一步：抢状态。抢到了才有资格还库存 ----
            int affected = orderMapper.markCancelled(orderNo, memberId);

            if (affected == 0) {
                // 走到这里说明 status 已经不是「待付款」了。
                // 两种常见情况：
                //   1. 用户连点两次取消 / 两个标签页同时点了取消
                //   2. 用户点取消的同一瞬间，定时任务刚好扫到了这笔超时订单
                // 两种都不是错误，只是「别人先动手了」。
                Order current = orderMapper.selectByOrderNoAndMember(orderNo, memberId);
                // current 理论上不可能是 null（上面刚查过，而且事务里这行没被别人删）；
                // 但为了不写出可能 NPE 的代码，还是按「查不到就当不存在」处理。
                if (current == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
                }
                throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                        "订单当前是「" + OrderStatus.text(current.getStatus()) + "」，无法取消");
            }

            // ---- ★★ 第二步：抢到了，现在可以放心地归还库存 ----
            // 这里读明细用的是 order_id，而不是再走一次会员校验 ——
            // 上面 selectByOrderNoAndMember 已经证明过这笔订单的归属了。
            // 这正是 OrderItemMapper.selectByOrderId 注释里说的「分层安全」：
            // 不要求每个方法都校验，但保证每个调用链上都校验过。
            List<OrderItemVO> items = orderItemMapper.selectByOrderId(order.getId());

            for (OrderItemVO item : items) {
                int restored = productMapper.increaseStock(item.getProductId(), item.getQuantity());

                if (restored == 0) {
                    // ★ 只记日志，不抛异常。
                    //   order_item 故意没有外键（见 mall.sql 里那段说明），
                    //   所以商品有可能已经被硬删除。这不是用户的错，
                    //   不能因此让「取消订单」这个操作失败 ——
                    //   否则订单卡在待付款、库存永远占着、用户还看得见它。
                    //   商品没了是运维数据的问题，人工核对即可。
                    log.warn("归还库存时商品不存在（可能已被删除），跳过: orderNo={}, productId={}, quantity={}",
                            orderNo, item.getProductId(), item.getQuantity());
                }
            }

            log.info("订单已取消: memberId={}, orderNo={}, 归还商品数={}",
                    memberId, orderNo, items.size());

            return requireOwnOrder(orderNo, memberId);
        });
    }

    /**
     * 扫描并取消所有超时未付款的订单。给定时任务调。
     *
     * <h3>★★ 三个刻意的设计选择</h3>
     *
     * <h4>1. 单条失败不能中断整批</h4>
     *
     * <p>循环里每一条都包了 try-catch。如果一条订单因为某种原因老是失败
     * （比如它的某个商品数据坏了），异常冒出去会让<b>整批</b>都处理不了 ——
     * 于是后面所有订单永远超时不了，库存永远占着。
     * 一条坏数据拖垮整个系统，是定时任务里最常见的故障模式。
     * <b>定时任务里的循环，默认就该是「尽力而为」而不是「全有全无」。</b>
     *
     * <p>⚠️ 注意这里的 try-catch 和 {@code cancelInternal} 里的
     * {@code increaseStock == 0 只记日志} 是<b>两个不同层次</b>的容错：
     * <pre>
     *   increaseStock == 0  →  预期内的、已知无害的情况（商品被删了），继续
     *   这里的 catch        →  预期外的、不知道原因的情况，跳过这一条并记 error
     * </pre>
     * 前者继续处理<b>这笔订单剩下的商品</b>，后者跳过<b>整笔订单</b>。
     *
     * <h4>2. 一轮只处理一个 batchLimit，不做「循环扫到空」</h4>
     *
     * <p>写成 {@code while (true) { 捞一批; 处理; 如果这批为空就 break; }} 看起来更「彻底」，
     * 但它有一个严重的缺点：<b>如果某个 bug 让「捞出来但处理不掉」的订单一直留在
     * 候选集里，这个循环就永远不会结束。</b> 定时任务的线程被它占死，
     * 而且是在每 10 秒一次的调度里反复发生。
     *
     * <p>一轮处理一批，剩下的交给下一轮。积压会按
     * {@code limit / 扫描间隔} 的速度排掉 —— 比如 limit=200、间隔 10 秒，
     * 就是每秒 20 单。<b>慢一点没关系，有界比彻底重要。</b>
     *
     * <h4>3. 没有「当前会员」这个概念</h4>
     *
     * <p>所以这里<b>绝对不能</b>调 {@code UserContext.require()} ——
     * 定时任务的线程上没有登录态，会直接抛 401 把整轮扫描搞崩。
     * 会员身份是从扫描结果里读出来的（那条 SQL 返回了 member_id），
     * 然后显式传给 {@code cancelInternal}。
     * <b>这正是 cancelInternal 把 memberId 做成参数而不是内部获取的原因。</b>
     */
    @Override
    public int cancelTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(payTimeoutMinutes);

        List<Order> candidates = orderTimeoutMapper.selectTimeoutCandidates(deadline, timeoutBatchLimit);

        // ★ 没有超时订单时【不打印任何日志】。
        //   这个任务每 10 秒跑一次，如果无条件打一行 info，
        //   一天就是 8640 行噪音，真正有用的日志会被淹掉。
        //   「没事发生时不说话」是定时任务日志的基本修养。
        if (candidates.isEmpty()) {
            return 0;
        }

        int done = 0;
        for (Order candidate : candidates) {
            try {
                cancelInternal(candidate.getOrderNo(), candidate.getMemberId());
                done++;
            } catch (Exception e) {
                // 理由见上面第 1 点：跳过这一条，继续处理后面的。
                log.error("超时自动取消失败，跳过这一单: orderNo={}, memberId={}",
                        candidate.getOrderNo(), candidate.getMemberId(), e);
            }
        }

        log.info("超时自动取消完成: 本轮候选={}, 成功={}", candidates.size(), done);
        return done;
    }

    // ==========================================================================
    // 里程碑 10：订单列表 + 发货 / 确认收货
    // ==========================================================================

    /**
     * 分页查当前会员的订单。
     *
     * <p>结构和 {@code ShopProductServiceImpl.page} 一致：
     * {@code normalize()} → count → 为 0 就直接返回空页 → 查列表。
     */
    @Override
    public PageResult<OrderVO> pageMyOrders(ShopOrderQueryDTO query) {
        // ★ normalize() 是安全相关的一步，不能省：它把 pageSize 钳到 100 以内，
        //   否则前端传 pageSize=999999 就能一次把整个订单表拉走。
        //   （同 ShopProductServiceImpl.page 的注释。）
        query.normalize();

        // ★★ 查谁的订单由【JWT】决定，不由请求参数决定。
        //   ShopOrderQueryDTO 里没有 memberId 字段，所以「按前端传的会员查」
        //   在类型层面就写不出来；而 Mapper 那边 memberId 是一个独立参数，
        //   必须显式传 —— 两头一起卡住，这条路才真的堵死。
        Long memberId = currentMemberId();

        long total = orderMapper.countByMember(memberId, query);
        if (total == 0) {
            // 一条都没有就不必再发第二条 SQL。
            // PageResult.empty 保证 pageNum/pageSize 照常返回，
            // 前端的分页器不会因为少字段而报错。
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<OrderVO> list = orderMapper.selectPageByMember(memberId, query);

        // ★ 明细【批量】装，不在这里逐笔查 —— 见 attachItems。
        attachItems(list);

        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    /**
     * 分页查<b>全部会员</b>的订单 —— 管理端。
     *
     * <p>和 {@link #pageMyOrders} 结构对称，唯一的结构性差别是
     * <b>这里没有 memberId</b>。那不是漏了，是管理员的语义：
     * 「这条订单是不是我的」对管理员来说是个没有意义的问题。
     * 边界由 {@code AdminAuthInterceptor} 在路径层面提供，
     * 见 {@code OrderAdminMapper} 的类注释。
     */
    @Override
    public PageResult<AdminOrderVO> pageAdminOrders(OrderQueryDTO query) {
        query.normalize();

        long total = orderAdminMapper.countAdminQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<AdminOrderVO> list = orderAdminMapper.selectAdminPage(query);

        // ★ 用的是同一个 attachItems —— 它接受 List<? extends OrderVO>，
        //   所以 AdminOrderVO（子类）的列表直接就能传进来。
        //   **两个列表共享同一段装明细的逻辑**，这才不会有「用户端装了、
        //   管理端忘了装」这种一半的 bug。
        attachItems(list);

        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    /**
     * 发货 —— 管理端操作，作用于<b>别人的</b>订单。
     *
     * <h3>★ 为什么它不需要事务？</h3>
     *
     * <p>按本模块自己立下的判据：<b>「要不要事务，看有没有多个必须一起成败的写」</b>。
     * 这个方法<b>只有一个写</b>（那条 UPDATE），单条 SQL 本身就是原子的。
     *
     * <p>对比 {@code cancelInternal}：它有<b>两个</b>写（改状态 + 归还 N 件库存），
     * 所以必须事务。差别不在「重不重要」，在「有几个写」。
     *
     * <p>⚠️ <b>不要为了「和兄弟方法看起来一致」而给它套上 TransactionTemplate。</b>
     * 一个空事务不会让代码更安全，只会让下一个读代码的人以为这里有什么
     * 需要保护的东西 —— 而实际上没有。**没有理由的事务和没有理由的锁一样，是负债。**
     */
    @Override
    public AdminOrderVO ship(String orderNo) {
        // ★ 条件更新当闸门：WHERE 里有 status = 1。
        //   两个管理员同时点发货，只有一个拿到 affected = 1。
        int affected = orderAdminMapper.markShipped(orderNo);

        if (affected == 0) {
            // ★★ 失败路径上重查一次，把「为什么失败」翻译成人话。
            //   这是 markPaid / markCancelled 已经确立的写法：
            //   WHERE 有几个条件，就有几种失败的可能，直接抛「发货失败」
            //   用户（这里是管理员）不知道该怎么办。
            //   重查很便宜（order_no 上有唯一索引），而且这是一条很少走到的路径。
            //   **优化要针对热路径，错误路径上准确性比性能重要。**
            //
            //   ⚠️ 注意这次重查【没有 memberId 可以带】——
            //      管理员发货本来就是在操作别人的订单，这是语义，不是疏忽。
            AdminOrderVO current = requireOrderForAdmin(orderNo);

            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前是「" + OrderStatus.text(current.getStatus())
                            + "」，只有「已付款」的订单可以发货");
        }

        log.info("订单已发货: orderNo={}", orderNo);

        // ★ 重查一次拿到更新【之后】的数据（包含 ship_time），原样返回给前端。
        //   前端可以直接用这个响应刷新那一行，不用重查列表。
        AdminOrderVO vo = requireOrderForAdmin(orderNo);
        attachItems(List.of(vo));
        return vo;
    }

    /**
     * 确认收货 —— 用户操作<b>自己的</b>订单。
     *
     * <p>形状和 {@link #ship} 完全一样（条件更新 → affected = 0 就重查分辨原因），
     * 只有两处不同：<b>带 memberId</b>、以及下面这条。
     *
     * <h3>★★ 这里【绝不归还库存】—— 本里程碑最容易被改错的地方</h3>
     *
     * <p>看到 {@code cancelInternal} 要走「归还库存」那一步，
     * 很容易想给这里也对称地补一次。<b>那是超卖。</b>
     * <pre>
     *   取消     ：货从来没发出去，还在仓里      → 还回去是对的
     *   确认收货 ：货【已经寄到买家手里了】        → 还回去 = 凭空多出一件可以卖的货
     * </pre>
     * 「已完成」表示这笔交易结束了，不是「这笔交易没发生」。
     *
     * <p>★ 这两个迁移的条件更新形状一模一样，
     * 但<b>对库存的影响恰好相反</b>。形状相同不代表副作用相同 ——
     * 照着形状抄是最容易出事的一种 copy。
     *
     * <p>★ 为什么它也不需要事务？理由和 {@link #ship} 一样：
     * 只有一个写。而且它连「一不小心就写两个」的可能性都没有 ——
     * 因为这里根本没有第二个写要做。
     */
    @Override
    public OrderVO complete(String orderNo) {
        Long memberId = currentMemberId();

        int affected = orderMapper.markCompleted(orderNo, memberId);

        if (affected == 0) {
            // 三种失败原因（订单不存在 / 不是你的 / 状态不是已发货）
            // 由 requireOwnOrder 先分辨出前两种 —— 它抛 1003「订单不存在」，
            // 前两种共用一个错误码，理由见它的注释（区分开来等于确认了
            // 「这个订单号存在」）。走到下面这行就只剩状态不对这一种了。
            Order current = requireOwnOrder(orderNo, memberId);

            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前是「" + OrderStatus.text(current.getStatus())
                            + "」，只有「已发货」的订单可以确认收货");
        }

        log.info("订单已确认收货（已完成）: memberId={}, orderNo={}", memberId, orderNo);

        return toVO(requireOwnOrder(orderNo, memberId));
    }

    // ==========================================================================
    // 辅助方法
    // ==========================================================================

    /**
     * 把订单实体转成返回给前端的 VO。
     *
     * <p>明细<b>重新查一次库</b>，而不是让调用方把内存里的 items 传进来。
     * 多了一次查询，换来的是「只有一条转换路径」——
     * 新建订单、幂等命中、将来查订单详情，走的都是这个 {@code toVO(order)}，
     * 不会出现"某条路径少拼了一个字段"的不一致。
     *
     * <p><b>★ 「多查一次」和「代码只有一份」之间，这里选后者。</b>
     * 因为 order_id 上有索引，这次查询是一次索引查找，很便宜；
     * 而多一条转换路径，意味着将来加字段时要记得改两处 ——
     * 迟早会漏。**廉价的一致性好过昂贵的优化。**
     *
     * <p>⚠️ 里程碑 10 之后它只是<b>一个薄壳</b>（自己查一次明细），
     * 真正的字段赋值在同名的双参重载里。这样拆的原因是列表页
     * 拿不到「一笔一笔查明细」——见 {@code attachItems}。
     * 「只有一条转换路径」这条纪律仍然成立，只是那条路径现在
     * 由两个重载共同构成：<b>薄壳负责查明细，重载负责搬字段。</b>
     */
    private OrderVO toVO(Order order) {
        return toVO(order, orderItemMapper.selectByOrderId(order.getId()));
    }

    /**
     * ★ 真正干活的那一半：把实体字段逐个搬到 VO 上，明细由调用方给。
     *
     * <p>上面的 {@code toVO(order)} 是它的薄壳（自己查一次明细）。
     * 拆成两个重载是为了让<b>字段赋值只有一处</b> ——
     * 里程碑 10 往 {@code OrderVO} 加了 {@code shipTime} / {@code completeTime}
     * 和往这边加了 {@code attachItems}，如果它们各有一套赋值代码，
     * 加字段时就要记得改两处，而漏改的症状是
     * 「字段静默地从 JSON 里消失」（{@code non_null} 把它藏起来了，
     * 构建和 ESLint 都不会报）。<b>廉价的一致性好过昂贵的优化。</b>
     */
    private OrderVO toVO(Order order, List<OrderItemVO> items) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverAddress(order.getReceiverAddress());
        vo.setRemark(order.getRemark());
        vo.setCreateTime(order.getCreateTime());

        // ---- 里程碑 9 加的四个字段 ----
        vo.setPayTime(order.getPayTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setPayMethod(order.getPayMethod());

        // ---- 里程碑 10 加的两个字段 ----
        vo.setShipTime(order.getShipTime());
        vo.setCompleteTime(order.getCompleteTime());

        vo.setPayDeadline(payDeadlineOf(order.getStatus(), order.getCreateTime()));

        vo.setItems(items);
        return vo;
    }

    /**
     * ★★ 给一页订单<b>批量</b>装上明细 —— N+1 的修法。
     *
     * <h3>为什么必须批量？</h3>
     *
     * <p>原来的写法是「每转一个订单就查一次明细」（见上面的 {@code toVO}），
     * 单笔查询时完全没问题 —— 就一次索引查找。
     * 但一变成列表就是教科书级的 <b>N+1</b>：
     * <pre>
     *   一页 10 条  →  1 次 count + 1 次列表 + 10 次明细 = 12 次查询
     *   批量之后    →  1 次 count + 1 次列表 +  1 次明细 =  3 次查询
     * </pre>
     * 而且这个差距<b>随页大小线性增长</b>：页大小调到 100 就是 102 次查询。
     * <b>N+1 的特点不是「慢」，而是「慢得和你不相关的参数成正比」。</b>
     *
     * <h3>★ 为什么参数类型是 {@code List<? extends OrderVO>}？</h3>
     *
     * <p>因为用户端（{@code OrderVO}）和管理端（{@code AdminOrderVO}）
     * 两个列表都要用它。用通配符之后，<b>子类的列表可以直接传进来</b>，
     * 而不需要写第二个方法，也不需要把 {@code AdminOrderVO} 降级成 {@code OrderVO}
     * （那会丢掉会员字段）。
     *
     * <p><b>★ 参数用 {@code extends}（只读），是因为这里只写不读结构</b> ——
     * 我们只调 {@code setItems} / {@code setPayDeadline}，不往列表里加东西。
     * 编译器和下一个读代码的人都能从这里看出「这个方法是就地修改元素，
     * 不会动这个列表本身」。
     *
     * <h3>⚠️ 空集合必须在这里拦住</h3>
     *
     * <p>因为 <b>{@code IN ()} 是 SQL 语法错误，不是「返回 0 行」</b>。
     * 这是 MyBatis 拼动态 SQL 最经典的翻车点：
     * 本地测的时候列表里总有数据，一上线遇到空页就是 500。
     * <b>不能指望 SQL 兜底 —— 它兜不住。</b>
     */
    private void attachItems(List<? extends OrderVO> orders) {
        // ★ 空列表直接返回。见上面那段：IN () 会让整条 SQL 报语法错。
        if (orders.isEmpty()) {
            return;
        }

        List<Long> orderIds = orders.stream()
                .map(OrderVO::getId)
                .toList();

        // ★ 一次查询拿回【本页所有订单】的明细，然后在内存里按 orderId 分组。
        //   这就是「用一次查询换掉 N 次查询」的全部内容。
        Map<Long, List<OrderItemVO>> byOrderId = orderItemMapper.selectByOrderIds(orderIds)
                .stream()
                .collect(Collectors.groupingBy(OrderItemVO::getOrderId));

        for (OrderVO vo : orders) {
            // ★ getOrDefault(..., List.of()) 这一层【不是防御性编程】：
            //   order_item 故意没有外键（里程碑 8 的决定），
            //   所以「订单在、明细不在」在数据层面是【可能】的。
            //   少了它就是一个 NPE —— 而且是几十单里只有一单会出现的那种。
            vo.setItems(byOrderId.getOrDefault(vo.getId(), List.of()));

            // ★ payDeadline 是派生值，不是数据库里的列，所以分页查询取不到它，
            //   必须在这里补算。用的是和 toVO 完全同一个 payDeadlineOf 重载 ——
            //   两处各算一遍的话，迟早会出现「详情页有倒计时、列表页没有」。
            vo.setPayDeadline(payDeadlineOf(vo.getStatus(), vo.getCreateTime()));
        }
    }

    /**
     * 按订单号查订单，查不到就抛 1003 —— <b>管理端版本（不带会员条件）。</b>
     *
     * <p>它和 {@link #requireOwnOrder} 是刻意的两个方法，而不是一个带
     * {@code memberId} 参数的方法：管理端本来就<b>没有 memberId 可传</b>，
     * 强行合并只会逼出一个「传 null 就跳过会员校验」的分支 ——
     * 那种分支是安全漏洞最喜欢藏身的地方。
     * <b>两个语义就写两个方法，别用 null 去表示「这个条件不适用」。</b>
     */
    private AdminOrderVO requireOrderForAdmin(String orderNo) {
        AdminOrderVO order = orderAdminMapper.selectAdminByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    /**
     * 算一笔订单的支付截止时刻 —— <b>只有「待付款」的订单才算，其他状态返回 null。</b>
     *
     * <h3>★ 为什么其他状态不算？</h3>
     *
     * <p>因为对已付款/已取消的订单来说，「还能付多久」是个不存在的问题。
     * 而返回 null 之后，Jackson 会把这个字段<b>从 JSON 里整个删掉</b>
     * （{@code JacksonConfig} 里配了 {@code default-property-inclusion: non_null}）。
     *
     * <p>这不只是省几个字节。<b>一个「没有意义但存在」的字段，
     * 迟早会有人拿它去做判断</b> —— 比如前端哪天写
     * {@code if (order.payDeadline) { 显示倒计时 }}，
     * 那么已付款的订单也会显示一个倒计时，看起来还挺正常。
     * 让它干脆不存在，这种误用就没有机会发生。
     *
     * <h3>★ 为什么是 toVO 算，而不是在 SQL 里查出来？</h3>
     *
     * <p>因为 {@code create_time + 30分钟} 里的「30 分钟」是 Spring 配置，
     * SQL 读不到（这一点和 {@code markPaid} 的 deadline 是同一个问题）。
     * 而且它是一个<b>纯派生值</b> —— 完全可以由已有的字段推出来，
     * 存进数据库反而会引入「两个值不同步」的风险（改了配置，老数据还是老值）。
     * <b>能推出来的东西不要存；存了就要负责让它同步。</b>
     *
     * <p>（对比 {@code Order.totalAmount}：那个也是「算出来的值」，
     * 但它需要被<b>定格</b>在某个历史时刻，所以必须存。
     * 判断标准是：<b>这个值将来会不会因为别的数据变了而变得不一致？</b>
     * 会 → 存；不会 → 推。<b>支付截止时刻会跟着配置走，反而是不该存的那一类。</b>）
     *
     * <h3>★ 为什么参数是 status + createTime，而不是整个 Order？</h3>
     *
     * <p>因为调用方有两种：{@code toVO} 手上是实体（{@code Order}），
     * 而 {@code attachItems} 手上是<b>已经转好的 VO</b> ——
     * 分页查询的结果已经是 {@code OrderVO} 了，那里根本没有实体。
     * 只取这两个字段当参数，两种调用方就能共用同一份实现。
     *
     * <p>（这也再次说明它确实是个<b>纯派生值</b>：
     * 算它只需要两个输入，跟订单的其余部分没有任何关系。）
     */
    private LocalDateTime payDeadlineOf(Integer status, LocalDateTime createTime) {
        if (!Integer.valueOf(OrderStatus.PENDING_PAY).equals(status)) {
            return null;
        }
        // ★ 为什么这里要判 createTime 为 null？
        //   不是为了防御「不可能发生的事」，而是因为**确实有一条路径会传进来**：
        //   doCreate 里那句「下单后回查失败」的降级分支 ——
        //   它返回的是刚 insert 完、createTime 还是 null 的对象。
        //
        //   ⚠️ 而且返回 null 在这里是**正确的降级行为**，不是掩盖问题：
        //      payDeadline 只影响前端要不要显示倒计时（见 OrderVO 那段说明），
        //      能不能付款始终由服务端的 SQL 说了算。
        //      所以「倒计时不显示」是一个可接受的后果，
        //      而「整单下单成功却因为拼响应而报 500」是不可接受的。
        //   **降级要降在「用户还能继续用」的那一侧。**
        if (createTime == null) {
            return null;
        }
        return createTime.plusMinutes(payTimeoutMinutes);
    }

    /**
     * 取当前登录会员的 id。写法和理由同 {@code CartServiceImpl.currentMemberId}。
     *
     * <p>下单模块里这一点尤其重要：<b>订单的 memberId 必须来自 JWT，
     * 绝不能来自请求参数。</b>否则就等于"可以替别人下单"。
     */
    private Long currentMemberId() {
        LoginUser user = UserContext.require();
        return user.id();
    }

    /**
     * 查一笔「必须是自己的」订单，查不到就抛 1003。
     *
     * <p>把 {@code pay} / {@code cancelInternal} / {@code getByOrderNo}
     * 里重复出现的「查 + 判空 + 抛错」收成一个方法。<b>三个地方都写一遍的话，
     * 迟早会有一个地方忘了判空或者用错错误码。</b>
     *
     * <p>注意错误信息是「订单不存在」，<b>而不是「订单不存在或不属于你」</b>。
     * 后者听起来更诚实，但它等于确认了「这个订单号是存在的，只是不是你的」——
     * 攻击者拿它就能枚举出哪些订单号有效。
     * <b>安全相关的错误信息要「准确但不多说」。</b>
     *
     * <p>（这也是为什么不用一句「无权访问」了事：
     * 对绝大多数用户来说，「订单不存在」就是事实 ——
     * 他输错了一位数字而已。而攻击者得不到任何额外信息。
     * 兼顾体验和安全的最优解往往就是「用最普通的那个说法」。）
     */
    private Order requireOwnOrder(String orderNo, Long memberId) {
        Order order = orderMapper.selectByOrderNoAndMember(orderNo, memberId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }
}
