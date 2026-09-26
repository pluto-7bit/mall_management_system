package com.example.mall.service.impl;

import com.example.mall.common.AfterSaleWindow;
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
import com.example.mall.dto.OrderShipDTO;
import com.example.mall.dto.ShopOrderQueryDTO;
import com.example.mall.entity.MemberAddress;
import com.example.mall.entity.Order;
import com.example.mall.entity.OrderItem;
import com.example.mall.mapper.MemberAddressMapper;
import com.example.mall.mapper.OrderAdminMapper;
import com.example.mall.mapper.OrderItemMapper;
import com.example.mall.mapper.OrderMapper;
import com.example.mall.mapper.OrderTimeoutMapper;
import com.example.mall.mapper.ProductSkuMapper;
import com.example.mall.service.CartService;
import com.example.mall.service.OrderService;
import com.example.mall.service.ShopSkuService;
import com.example.mall.service.StockRestoreService;
import com.example.mall.util.OrderNoGenerator;
import com.example.mall.vo.AdminOrderVO;
import com.example.mall.vo.OrderItemVO;
import com.example.mall.vo.OrderVO;
import com.example.mall.vo.ShopSkuVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * ★ 里程碑 15 阶段 4：扣库存 / 还库存都走它。
     *
     * <p>⚠️ 这里原本依赖的是 {@code ProductMapper}，而且<b>已经整个去掉了</b> ——
     * 注意是「去掉」不是「换掉」：本轮之后这个类<b>一条 SQL 都不再碰 product 表</b>。
     * 下单要的商品信息全部来自 {@link #shopSkuService}（它内部才去查 product），
     * 而下单要改的库存全部在 {@code product_sku} 上。
     *
     * <p>★ 少一个依赖的价值不是「代码短了」，而是<b>少一条路</b>：
     * 只要 {@code ProductMapper} 还注入在这里，下一个人想读一下
     * {@code product.price} 就只是「顺手」的事；而本轮的不变量是
     * 「product 表上没有价格，也没有库存」——
     * 让那条路根本不存在，比每次 code review 都提醒一遍可靠。
     */
    private final ProductSkuMapper productSkuMapper;

    private final MemberAddressMapper addressMapper;
    private final CartService cartService;

    /**
     * ★ 里程碑 15 阶段 4：下单时查「买的是哪几个规格、现在多少钱、还有多少货」。
     *
     * <p>用它而不是直接用 {@code ProductSkuMapper}，是因为它才是
     * 「这个规格现在能不能买」那<b>唯一一处定义</b>（内部会去查商品、带上 status = 1）。
     * 直接查 mapper 拿到的是「这一行存在」，而下单要问的是「这一行可买」——
     * 两者在下架商品上给出完全不同的答案。
     */
    private final ShopSkuService shopSkuService;

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
     * ★★ 里程碑 17：归还库存的那段逻辑搬到了这里。
     *
     * <p>它原来就在下面的 {@code cancelInternal} 里，是一个遍历明细、
     * 逐条 {@code increaseSkuStock} 的循环。本轮「仅退款」和「退货退款」
     * 各要再调一次，<b>而这段代码绝对不能有两份</b> ——
     * {@code OrderService} 里那句原话：
     * 「扣库存那段代码绝对不能有两份。两份就意味着『改了一份忘了另一份』，
     * 而漏掉的那份就是超卖漏洞。」
     *
     * <p>★ 搬移是纯粹的：循环体一个字都没改（见
     * {@code StockRestoreServiceImpl}），而这个类的行为唯一的差别是
     * 「现在会调一个接口」。所以本轮最高回归风险的检查就是
     * {@code test-order.py} / {@code test-order-list.py} 里
     * 「取消订单归还库存」那几条 —— 它们必须仍然全绿。
     *
     * <p>⚠️ <b>顺序不能变</b>：它必须<b>在</b> {@code markCancelled}
     * 拿到 {@code affected = 1} <b>之后</b>才被调用。
     * 接口的契约就是「我不做任何资格判断」—— 见
     * {@link com.example.mall.service.StockRestoreService} 的类注释。
     */
    private final StockRestoreService stockRestoreService;

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
     * 固定运费（不满包邮门槛时收这个数）。★ 里程碑 17。
     *
     * <p><b>★ 为什么声明成 {@code String} 而不是 {@code BigDecimal}？</b>
     * 两个理由，第二个是真会咬人的那个：
     * <ol>
     *   <li>Spring 能把 {@code "10.00"} 直接转成 {@code BigDecimal}，
     *       所以从类型上其实两种都行；</li>
     *   <li>★ 但 {@code application.yml} 里的值<b>必须加引号</b>才活得下来 ——
     *       不加引号的 {@code 10.00} 会被 YAML 当成<b>浮点数</b>解析成 {@code 10.0}，
     *       再转成 BigDecimal 就得到 scale = 1 的 {@code 10.0}。
     *       拿 {@code String} 接住，再自己 {@code new BigDecimal(...)}，
     *       恰好和「引号让它从头到尾是个字符串」这件事对得上；
     *       声明成 BigDecimal 会让人以为「不用加引号也行」。</li>
     * </ol>
     * 本项目有一条铁律是<b>金额不走 double</b>（见 {@code BusinessRules.MONEY_SCALE}），
     * 上面这个是它在本轮的第一个落点。
     */
    @Value("${mall.order.freight-amount}")
    private String freightAmountRaw;

    /**
     * 满多少免运费（商品小计 ≥ 这个数就包邮）。★ 里程碑 17。
     *
     * <p>和上面同一个理由声明成 {@code String}。
     *
     * <p><b>★ 边界是一个决定，不是巧合：恰好等于门槛时【免运费】</b>
     * （{@code goodsAmount >= threshold}）。所以 {@code 99.00} 包邮、{@code 98.99} 不包邮，
     * 测试必须<b>两侧都测</b> —— 只测一侧的话，把 {@code >=} 写成 {@code >} 也照样全绿。
     */
    @Value("${mall.order.free-freight-threshold}")
    private String freeFreightThresholdRaw;

    /**
     * 售后申请时限（天），起点是「确认收货」。★ 里程碑 17。
     *
     * <p>它<b>只作用于「已完成」的订单</b> —— 已付款（未发货）和已发货
     * （未收货）的订单没有 {@code complete_time}，也就没有起点。
     * 完整的论证见 {@link #afterSaleDeadlineOf}。
     *
     * <p><b>★ 为什么声明成 {@code int}（不像上面两个金额那样用 String）？</b>
     * 因为它不是金额 —— 「金额不走 double」那条铁律管的是精度，
     * 而 7 天这个数不涉及小数。用一个 {@code @Value} 能直接转的整数类型
     * 反而更好：它把「这里进来的必须是个整数」写在了类型上。
     * <b>引号的规则只对金额那几个值成立，不要顺手推广到所有配置。</b>
     */
    @Value("${mall.order.after-sale-window-days}")
    private int afterSaleWindowDays;

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
     *
     * <p>★ 里程碑 15 阶段 4：{@code productId} 换成了 {@code skuId}。
     * <b>「下单的一行」到底是什么，这一轮才第一次答对</b> ——
     * 在 SKU 之前，「买 2 件 T 恤」这一行是说不清买的是哪个规格的，
     * 而扣库存、算单价、写快照全都要知道这件事。
     * 所以这个 record 的第一个字段必须是 skuId，它同时是这三件事的主语。
     */
    private record OrderLine(Long skuId, Integer quantity) {
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

        // ★ 差异一：数量从 Redis 购物车里读，不信客户端传的任何数量。
        //   传进来的是【勾选了哪几个规格】，不是"每种买几件"。
        List<OrderLine> lines = linesFromCart(dto.getSkuIds());
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
        List<OrderLine> lines = List.of(new OrderLine(dto.getSkuId(), dto.getQuantity()));
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
     * 从购物车里取出要结算的规格和数量，并校验「勾选的规格确实在车里」。
     *
     * <p><b>★ 为什么必须有这个校验？</b>
     * 前端会说"我要结算 204 号和 311 号"，但前端说的话不能直接信 ——
     * 用户可能在另一个标签页里把 311 号删了，也可能有人直接构造请求
     * 传一个自己车里根本没有（甚至不存在）的规格 id 过来。
     * <b>凡是客户端"声称"的事实，服务端都要能用自己手里的数据验证一遍。</b>
     */
    private List<OrderLine> linesFromCart(List<Long> skuIds) {
        // 去重：万一客户端传了 [204, 204]，不去重的话会生成两行明细、
        // 扣两次库存。而 Redis Hash 的 field 天然唯一，
        // 读出来的数量只有一份，所以重复的 id 前面会查出同一个数量，
        // 结果就是"同一规格买两份"，金额却只算了一份 —— 数据就不一致了。
        List<Long> distinctIds = skuIds.stream().distinct().toList();

        Map<Long, Integer> quantities = cartService.readQuantities(distinctIds);

        List<OrderLine> lines = new ArrayList<>(distinctIds.size());
        for (Long sid : distinctIds) {
            Integer qty = quantities.get(sid);
            if (qty == null) {
                // 勾了一个"购物车里没有"的规格。可能是：
                //   - 用户开了两个标签页，另一个把它删了
                //   - 有人直接构造请求
                // 不管哪种，都不能继续 —— 因为"买几件"无从得知，
                // 而拿不到数量就下单，等于凭空猜一个数字。
                throw new BusinessException(ResultCode.NOT_FOUND,
                        "购物车里没有这件商品，请刷新页面后重试");
            }
            lines.add(new OrderLine(sid, qty));
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

        // ---- 2. 批量查规格 ----
        // 用一次 IN 查询，不是循环查（那是 N+1）。
        // ★ listAvailable 内部会拿 SKU 行去查它们所属的商品，而那条查询
        //   自带 status = 1 —— 所以下架商品在这里就查不出来。
        //   「能买」这条规则仍然只有一处定义，这里只是复用它。
        List<Long> skuIds = lines.stream().map(OrderLine::skuId).toList();
        List<ShopSkuVO> skus = shopSkuService.listAvailable(skuIds);

        Map<Long, ShopSkuVO> skuMap = new HashMap<>(skus.size());
        for (ShopSkuVO s : skus) {
            skuMap.put(s.getId(), s);
        }

        for (OrderLine line : lines) {
            if (!skuMap.containsKey(line.skuId())) {
                // 购物车里的商品/规格可能在下单前被下架/删除了。
                // 和商品详情接口一样不区分"不存在"和"已下架"。
                throw new BusinessException(ResultCode.NOT_FOUND,
                        "有商品已下架或不存在，请刷新购物车后重试");
            }
            // ★ 单个规格的数量上限。放在这里而不是 DTO，理由见 BusinessRules：
            //   购物车结算这条路的前端根本不传数量，DTO 根本拦不到它 ——
            //   数量是从 Redis 读出来的，只有 Service 才拿得到。
            //   （这正是"业务规则要放在拿得到数据的那一层"。）
            if (line.quantity() > BusinessRules.MAX_QUANTITY_PER_ITEM) {
                ShopSkuVO s = skuMap.get(line.skuId());
                throw new BusinessException(ResultCode.CART_QUANTITY_LIMIT,
                        "「" + s.getProductName() + "」最多购买 "
                                + BusinessRules.MAX_QUANTITY_PER_ITEM + " 件");
            }
        }

        // ---- 3. 扣库存：★★ 防超卖的关键在这里 ----
        for (OrderLine line : lines) {
            int affected = productSkuMapper.decreaseSkuStock(line.skuId(), line.quantity());

            if (affected == 0) {
                // 影响 0 行 = WHERE 里的 stock >= ? 不成立 = 库存不够。
                // 注意这里【没有】在扣之前查过库存 —— 那会引入竞态。
                // 判断完全依赖数据库这条原子 UPDATE 的结果。
                //
                // ★ 失败路径上再查一次，是为了给出**准确**的原因和剩余量。
                //   上面 skuMap 里的 stock 是下单开始时读到的旧值，
                //   在并发场景下可能已经变了，报给用户就是错的数字。
                //   错误路径很少走到，多查一次库完全可以接受 ——
                //   **优化要针对热路径，错误路径上准确性比性能重要。**
                //
                // ★ 里程碑 15 阶段 4 换成了 listAvailable，而不是原来的
                //   productMapper.selectEntityById —— 两个理由：
                //     ① 它复用的还是「可买」那唯一一处定义（status = 1），
                //        不会在这里长出第二条判断商品状态的路径
                //     ② 顺手把 OrderServiceImpl 对 ProductMapper 的依赖整个去掉了
                //        （下面第 7 步和取消路径也都换成 SKU 了），
                //        少一个依赖就少一条「有人又从这里读写 product 表」的路
                List<ShopSkuVO> current = shopSkuService.listAvailable(List.of(line.skuId()));
                if (current.isEmpty()) {
                    throw new BusinessException(ResultCode.NOT_FOUND,
                            "有商品已下架，请刷新购物车后重试");
                }
                ShopSkuVO cur = current.get(0);
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH,
                        "「" + cur.getProductName() + "」库存不足，仅剩 " + cur.getStock() + " 件");
            }
        }

        // ---- 4. 算钱 ----
        // ★ 价格来自 skuMap（也就是【数据库里现在的价格】），
        //   不是客户端传的。这是"金额永远由服务端算"的落地。
        //
        // ★ 每一步都用 BigDecimal 的方法，不写成 price * quantity 这种
        //   运算符形式（BigDecimal 没有运算符重载，写不了，这是好事）。
        //   全程不出现 double，避免 0.1 + 0.2 那类精度问题。
        //
        // ★★ 里程碑 17：钱分三步走，别再合成一个变量。
        //      goodsAmount  各明细小计之和（= 商品合计）
        //      freightAmount 运费，由 goodsAmount 按【下单时的】配置规则算出来
        //      totalAmount = goodsAmount + freightAmount（= 实付，写进 orders）
        //   三个名字各自只有一个含义，所以「实付含不含运费」这种问题
        //   不用去猜 —— 这也正是 maven 那个 totalAmount 会骗人的教训
        //   （见 Order.totalAmount 的注释：它的语义在本轮变了）。
        BigDecimal goodsAmount = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>(lines.size());

        for (OrderLine line : lines) {
            ShopSkuVO s = skuMap.get(line.skuId());

            // 小计 = 单价 × 数量。单价是 DECIMAL(10,2)，乘出来的小数位数
            // 最多也就是 2 位，不会出现"两位以上的钱"。
            BigDecimal subtotal = s.getPrice().multiply(BigDecimal.valueOf(line.quantity()));
            goodsAmount = goodsAmount.add(subtotal);

            OrderItem item = new OrderItem();
            // ★ 追溯线索：买的是哪件商品、哪个规格，都记下来。
            //   两个都不是显示依据（显示以下面的快照为准），只是"去查原始商品"的入口。
            item.setProductId(s.getProductId());
            item.setSkuId(s.getId());
            // ★ 快照：把商品名、规格文本和单价抄进明细。
            //   商品明天改名、商家改规格定义、涨价 —— 这笔订单都不受影响。
            //
            //   ★ 里程碑 15 阶段 4 加了两样：
            //     skuId   —— 取消订单时要把库存还回【哪一行】。没有它就只能还到商品级的
            //                汇总库存上，而那一列已经不存在了。
            //     skuSpec —— 「买的是哪个规格」这句话。存文本不存 JSON 的理由
            //                见 OrderItem.skuSpec 的注释。
            item.setProductName(s.getProductName());
            item.setSkuSpec(s.getSpecText());
            item.setPrice(s.getPrice());
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

        // ★★ 里程碑 17：运费在这里算，算完【写进数据库】。
        //   之后所有展示（Pay.vue / 订单列表 / 退款上限）都读 freight_amount，
        //   一律不重算 —— 运费规则是运营随时能改的配置，
        //   而「这笔订单当时收了多少运费」是涉及金钱的历史事实。
        BigDecimal freightAmount = freightOf(goodsAmount);
        BigDecimal totalAmount = goodsAmount.add(freightAmount);
        order.setTotalAmount(totalAmount);
        order.setFreightAmount(freightAmount);
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
        // ★ 传的是 skuIds（就是上面第 2 步查规格用的那个列表）。
        //   ⚠️⚠️ 别在这里顺手传 productId —— 购物车的 Redis field 是 skuId，
        //   传 productId 不但删不掉，还可能把用户车里【另一行】删掉
        //   （两个表的 id 都是自增的，撞车几乎是必然的），
        //   而且这个回调里的异常只打日志，所以不会有任何报错。
        //   详见 CartService.removeItems 和 CartServiceImpl 的注释。
        if (source == OrderSource.CART) {
            registerCartCleanupAfterCommit(skuIds, order.getOrderNo(), memberId);
        }

        // ★ 里程碑 17：日志里把「商品合计 / 运费 / 实付」三个数都打出来。
        //   只打一个 totalAmount 的话，事后翻日志看到「实付 109」
        //   没法判断那 10 块是运费还是商品的价 —— 而这三个数之间的关系
        //   正是本轮最容易错的地方（见 test-after-sale.py 那条减法 vs 加法断言）。
        log.info("下单成功: source={}, memberId={}, orderNo={}, 商品种类={}, 商品合计={}, 运费={}, 实付={}",
                source.getText(), memberId, order.getOrderNo(), items.size(),
                goodsAmount, freightAmount, totalAmount);

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
    private void registerCartCleanupAfterCommit(List<Long> skuIds,
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
                    cartService.removeItems(skuIds);
                } catch (Exception e) {
                    // ★ 绝不能让它抛出去，理由见上面第 1 点
                    log.error("订单已创建但清理购物车失败，需人工核对: "
                                    + "orderNo={}, memberId={}, skuIds={}",
                            orderNo, memberId, skuIds, e);
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
     * （这和 {@code decreaseSkuStock} 的「影响行数就是判断结果」是同一个模式。）
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

            // ★★ 里程碑 17：那段循环搬到了 StockRestoreService。
            //
            //    为什么必须抽出去而不是在这里再写一遍：本轮「仅退款」和
            //    「退货退款」各要再调一次同样的逻辑，而
            //    「扣库存那段代码绝对不能有两份」——
            //    漏掉的那一份就是超卖漏洞（原话在 OrderService 里）。
            //
            //    ⚠️ 顺序没有变：仍然在 markCancelled 拿到 affected = 1 之后。
            //       接口的契约是「它不做任何资格判断」，
            //       所以「抢边」这个动作必须留在这一侧 —— 见
            //       StockRestoreService 的类注释。
            stockRestoreService.restoreOrderItems(items, orderNo);

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
     * {@code increaseSkuStock == 0 只记日志} 是<b>两个不同层次</b>的容错：
     * <pre>
     *   increaseSkuStock == 0  →  预期内的、已知无害的情况（SKU 被删了 / 历史明细没有 skuId），继续
     *   这里的 catch           →  预期外的、不知道原因的情况，跳过这一条并记 error
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
    public AdminOrderVO ship(String orderNo, OrderShipDTO dto) {
        // ★ 条件更新当闸门：WHERE 里有 status = 1。
        //   两个管理员同时点发货，只有一个拿到 affected = 1。
        //   ★ 里程碑 18：这一步同时写入承运商 + 单号，但它们不影响闸门 ——
        //     所以「不需要事务」这个结论【没有变】（仍然只有一个写）。
        int affected = orderAdminMapper.markShipped(
                orderNo, dto.getLogisticsCompany(), dto.getTrackingNo());

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
        // ★ 里程碑 17：实付（totalAmount）和运费是【两个列、两个字段】，
        //   照搬就行，绝不能在这里「顺手」用一个去推另一个 ——
        //   页面要显示「商品合计」的话是前端做减法，不是后端补一个字段。
        vo.setFreightAmount(order.getFreightAmount());
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

        // ---- 里程碑 18 加的两个字段 ----
        // ★★ 这两个是【补上】的，不是一开始就写在这里的 —— 值得记一笔，
        //   因为它恰好撞上了上面那段注释警告的那个坑：
        //   列表那条路走的是 `resultType="OrderVO"`（MyBatis 按列名直接映射，
        //   加了列就自动有了），而这条路是【手写逐字段搬】。
        //   于是「列加了、实体加了、VO 也加了」三件事都做完了，
        //   订单列表上单号显示得好好的，**而订单详情里它静默地没有** ——
        //   `non_null` 把整个 key 删掉，页面上看不出任何异常。
        //
        //   ⚠️ 现在读这两个字段的页面只有列表，所以这个漏洞没露出来；
        //      但详情页是最可能「顺手」加一个「查看物流」入口的地方。
        //
        //   ★ 判据（比记「要改哪几处」更耐用）：
        //     往 OrderVO 加的是【数据库里的列】→ 只需在这里搬一次
        //     （列表那条路靠 resultType=OrderVO 自动映射）；
        //     加的是【派生值】→ toVO 和 attachItems 两处都要算。
        vo.setLogisticsCompany(order.getLogisticsCompany());
        vo.setTrackingNo(order.getTrackingNo());

        vo.setPayDeadline(payDeadlineOf(order.getStatus(), order.getCreateTime()));

        // ★ 里程碑 17：售后申请截止时刻。和 payDeadline 一样是纯粹的派生值，
        //   不是数据库里的列，所以必须在这里（和 attachItems 里）补算。
        vo.setAfterSaleDeadline(
                afterSaleDeadlineOf(order.getStatus(), order.getCompleteTime()));

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

            // ★ 里程碑 17：afterSaleDeadline 是同一类派生值，
            //   所以走完全同一个重载补算。⚠️ 这里的代价比 payDeadline 那次更大：
            //   订单**列表**页是用户看得最多的页面，如果只在这儿漏算，
            //   症状是「订单列表里点不了申请售后、详情页能点」——
            //   和里程碑 12 那条「列表里点不了评价、详情里能点」是同一个形态，
            //   而它读 Java 代码根本看不出来（两个页面共用一个 VO）。
            vo.setAfterSaleDeadline(
                    afterSaleDeadlineOf(vo.getStatus(), vo.getCompleteTime()));
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
     * 算一笔订单的售后申请截止时刻 —— <b>只有「已完成」的订单才算，其他状态返回 null。</b>
     * ★ 里程碑 17。
     *
     * <h3>★★ 「已付款 / 已发货」的订单为什么不受时限约束？这不是疏漏，是唯一可能的选择</h3>
     *
     * <p>时限的起点是「确认收货」（{@code complete_time}），
     * 而未确认收货的订单<b>根本没有这个起点</b> ——
     * {@code complete_time} 是 NULL，而 NULL 和任何数比较都是 NULL，不是真。
     * 本项目没有「发货后 N 天自动确认收货」的定时任务，
     * 所以这个起点永远不会自己出现。
     *
     * <p>业务上也是对的：<b>钱在商家手里、货还在路上，
     * 不该因为「等太久」剥夺用户退款的权利</b> ——
     * 「多久没到货」是商家的责任，不是用户的。
     *
     * <pre>
     *   status = 1 (已付款，未发货)  →  complete_time 是 NULL  →  不受时限约束
     *   status = 2 (已发货，未收货)  →  complete_time 是 NULL  →  不受时限约束
     *   status = 3 (已完成)          →  起点 = complete_time  →  受 N 天约束
     * </pre>
     *
     * <h3>★ 边界是一个决定：<b>恰好等于 deadline 算「还在期限内」</b></h3>
     *
     * <p>也就是 {@code now > deadline} 才算超期（宽以待人）。
     * 和 {@code freightOf} 里那句「恰好等于门槛时免运费」是同一类决定 ——
     * <b>边界值往哪边靠是一个要写下来的决定，不是一个可以随手写 {@code >=} 的地方。</b>
     * 而且它<b>只在一个地方</b>：这个方法和 {@code AfterSaleServiceImpl} 里
     * 那句检查必须用同一个比较方向，否则会出现「按钮显示着、点了说超期」。
     *
     * <h3>★ 为什么参数是 status + completeTime，而不是整个 Order</h3>
     *
     * <p>和 {@link #payDeadlineOf} 完全一样的理由：调用方有两种
     * （{@code toVO} 手上是实体，{@code attachItems} 手上是已经转好的 VO），
     * 只取这两个字段当参数，两种调用方就能共用同一份实现。
     *
     * <p>★ 这也再次说明它确实是个<b>纯派生值</b>：算它只需要两个输入。
     * <b>刻意不把它提前算成列存进 {@code after_sale} 或 {@code orders}</b> ——
     * 它是「要用的时候算一次」的东西，不是需要被定格的历史事实。
     * 判据和 {@code payDeadlineOf} 一字不差：
     * <b>这个值将来会不会因为别的数据变了而变得不一致？会跟着配置走 → 不存。</b>
     * （对比 {@code freight_amount}：那个必须存，因为它涉及钱，
     *   而「当时收了多少运费」是一个历史事实。）
     */
    private LocalDateTime afterSaleDeadlineOf(Integer status, LocalDateTime completeTime) {
        // ★★ 算法本身在 AfterSaleWindow 里，不在这里。
        //   这里原来有一份完整的实现（判状态、判 null、plusDays），
        //   而 AfterSaleServiceImpl.apply 那边还要再判一次「超期了没有」。
        //   **两份实现隔着一次 HTTP 往返，症状是「按钮显示着、点了说超期」，
        //     而且配不出断言**（两边看的是各自的「现在几点」，
        //     断言只能问「此刻一样吗」—— 那恰恰是它们唯一一定一样的地方）。
        //   所以这一处不靠断言兜，靠「只有一份实现」。
        //   完整的论证写在 AfterSaleWindow 的类注释里。
        return AfterSaleWindow.deadlineOf(status, completeTime, afterSaleWindowDays);
    }

    /**
     * 算一笔订单的运费：<b>商品小计满门槛则免运费，否则收固定运费。</b>★ 里程碑 17。
     *
     * <h3>★ 这是全项目唯一一处需要把「商品小计」这个概念算出来的地方</h3>
     *
     * <p>算完立刻折算成「要么 0、要么固定运费」两种结果之一，写进
     * {@code orders.freight_amount}。<b>之后全项目只认那一个列</b> ——
     * 展示、退款上限、对账全都读它，<b>永不重算</b>。
     *
     * <p>理由是一条很具体的后果：运费规则是运营随时能改的<b>配置</b>
     * （{@code mall.order.free-freight-threshold}），而「这笔订单当时收了多少运费」
     * 是一件涉及金钱的<b>历史事实</b>。展示时重算，那么运营把门槛从 99 降到 59 的那一刻，
     * 一笔【已经付过 10 元运费】的历史订单会显示成：
     * <pre>
     *   商品 ¥89.00   运费 ¥0.00   合计 ¥99.00
     * </pre>
     * 两个数字自相矛盾，<b>而且全程没有任何一层会报错</b>。
     * 这和 {@code orders.receiver_address}（收货地址快照）、
     * {@code order_item.price}（成交价快照）是<b>完全同类</b>的故障：
     * 历史事实被今天的规则改写。
     *
     * <h3>★ 比较之前必须先 setScale</h3>
     *
     * <p>和 {@code BusinessRules.MONEY_SCALE} 那段是同一个判据：
     * 数据库会把 {@code 99.004} 存成 {@code 99.00}。拿未舍入的原值去比大小，
     * 会得出「该包邮」，而存进去的那个数却比门槛小 ——
     * 于是同一条规则，在比的时候和在存的时候给出了两个答案。
     * 而这里的输入是 {@code price × quantity}：{@code price} 是 {@code DECIMAL(10,2)}，
     * 乘个整数数量小数位不会超过 2 位，所以实际上<b>现在</b>不会触发；
     * 写上去是为了 <b>下一次有人改价改量时，这条规则不会突然开始骗人</b>。
     *
     * <h3>★ 为什么参数是 goodsAmount 而不是整个订单 / 明细列表？</h3>
     *
     * <p>因为它是个<b>纯函数</b>：一个数进、一个数出，不读数据库、不看上下文。
     * 纯函数才可能被测试直接推理（「恰好 99.00 免不免」这种问题，
     * 答案必须只由这一个入参决定）。
     */
    private BigDecimal freightOf(BigDecimal goodsAmount) {
        BigDecimal threshold = new BigDecimal(freeFreightThresholdRaw)
                .setScale(BusinessRules.MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal flat = new BigDecimal(freightAmountRaw)
                .setScale(BusinessRules.MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal goods = goodsAmount.setScale(BusinessRules.MONEY_SCALE, RoundingMode.HALF_UP);
        // ★ 边界的决定：恰好等于门槛时【免】。compareTo 而不是 equals ——
        //   BigDecimal 的 equals 连 scale 一起比，99.0 和 99.00 会被判成「不相等」，
        //   那是个和金额大小毫无关系的后果（同 BusinessRules 里的既有纪律）。
        if (goods.compareTo(threshold) >= 0) {
            return BigDecimal.ZERO.setScale(BusinessRules.MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return flat;
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
