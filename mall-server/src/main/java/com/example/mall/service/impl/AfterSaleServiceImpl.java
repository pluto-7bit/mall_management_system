package com.example.mall.service.impl;

import com.example.mall.common.AfterSaleReason;
import com.example.mall.common.AfterSaleStatus;
import com.example.mall.common.AfterSaleType;
import com.example.mall.common.AfterSaleWindow;
import com.example.mall.common.BusinessException;
import com.example.mall.common.LoginUser;
import com.example.mall.common.OrderStatus;
import com.example.mall.common.PageResult;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.dto.AdminAfterSaleQueryDTO;
import com.example.mall.dto.AfterSaleApplyDTO;
import com.example.mall.dto.AfterSaleQueryDTO;
import com.example.mall.dto.AfterSaleRejectDTO;
import com.example.mall.dto.AfterSaleReturnDTO;
import com.example.mall.entity.AfterSale;
import com.example.mall.entity.Order;
import com.example.mall.mapper.AfterSaleMapper;
import com.example.mall.mapper.OrderItemMapper;
import com.example.mall.mapper.OrderMapper;
import com.example.mall.service.AfterSaleService;
import com.example.mall.service.StockRestoreService;
import com.example.mall.util.AfterSaleNoGenerator;
import com.example.mall.vo.AdminAfterSaleVO;
import com.example.mall.vo.AfterSaleVO;
import com.example.mall.vo.OrderItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 售后业务实现。★ 里程碑 17 新增，<b>本轮的主交付点</b>。
 *
 * <h3>★★ 这个类里最值得学的三件事</h3>
 *
 * <ol>
 *   <li><b>「抢到边」是「有资格做副作用」的凭证</b> —— 见 {@link #refundOnlyRefund}
 *       与 {@link #afterRefundSucceeded}。归还库存挂在 {@code status → 3}
 *       那条条件 UPDATE 的<b>影响行数</b>上；顺序反了，并发下库存就翻倍。</li>
 *   <li><b>哪条边由 URL 决定，绝不由运行时参数决定</b> —— 见 {@link #approve}
 *       的两个分支。合并成一个「带 expectedFrom 参数」的方法，
 *       调用方传个 0 就能把「待买家寄回」的单直接结掉。</li>
 *   <li><b>申请时读到的东西，退款时要重新算一遍</b> —— 见 {@link #refundOf}。
 *       「退多少钱」取决于「这条退完之后是不是全退完了」，
 *       而那是一个<b>在申请时还不存在的事实</b>。</li>
 * </ol>
 *
 * <h3>★ 这个类一个运费配置都不读</h3>
 *
 * <p>退款金额里的运费部分来自 <b>{@code orders.freight_amount} 这个快照</b>，
 * 不是「拿当前规则重算一遍」。
 *
 * <p>为什么这一条要特意写出来：{@code OrderServiceImpl} 读
 * {@code mall.order.freight-amount} 和 {@code free-freight-threshold}，
 * 很自然会觉得这里也该读一遍。而<b>重算的后果是具体的</b>：
 * 运营把包邮门槛从 99 降到 59 之后，一笔当时付了 10 元运费的历史订单，
 * 退款时会按新规则算出「包邮」，于是<b>少退用户 10 元</b> ——
 * 不报错、不告警，只有用户会发现。
 *
 * <p>所以这个类的字段清单里<b>没有任何 {@code mall.order.*}</b>，
 * 除了那个时限天数 —— 它不是金额，而且它本来就是「现在生效的规则」，
 * 用它的地方是<b>申请那一刻</b>，不是退款那一刻。见 {@link #checkWindow}。
 *
 * <h3>★ 所有失败路径的形状都是同一个</h3>
 *
 * <p>和 {@code OrderServiceImpl.ship} / {@code complete} 一字不差：
 * <pre>
 *   条件 UPDATE  →  affected == 0  →  重查一次  →  把「为什么失败」翻译成人话
 * </pre>
 * <b>WHERE 有几个条件，就有几种失败的可能。</b>直接抛一句「操作失败」，
 * 用户（或管理员）不知道该怎么办。重查很便宜（{@code after_sale_no} 上有唯一索引），
 * 而且这是一条很少走到的路径 —— <b>优化要针对热路径，错误路径上准确性比性能重要。</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleServiceImpl implements AfterSaleService {

    private final AfterSaleMapper afterSaleMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final StockRestoreService stockRestoreService;

    /**
     * ★ 用它来开事务，而不是给方法加 {@code @Transactional}。
     *
     * <p>理由和 {@code OrderServiceImpl} 里那个完全一样（{@code @Transactional}
     * 靠代理生效，而「自己调自己」不经过代理 —— 这个类里
     * {@code approve} 调 {@code refundOnlyRefund} 正是这种情况）。
     * <b>而且这里比订单那边更需要它</b>：申请售后要分辨
     * {@code DuplicateKeyException} 是哪个唯一索引抛的，
     * 那就必须在事务外做一次重查 —— 见 {@link #apply}。
     * 用方法级 {@code @Transactional} 的话，异常一抛出事务就被标成
     * rollback-only，后面连查询都做不了，提交时抛 {@code UnexpectedRollbackException}。
     * （这一段完整的论证写在 {@code OrderServiceImpl.submit} 的注释里。
     *   一行没改地适用于这里 —— <b>但它是第二次出现，所以是抄了一整段论证，
     *   不是抄了一行代码。</b>）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 售后申请时限（天）。<b>★ 这个类里唯一读配置的地方。</b>
     *
     * <p>为什么它读得到，而运费读不得：时限约束的是<b>「申请」这个动作</b>，
     * 它是一个「现在生效的规则」—— 运营今天把 7 天改成 15 天，
     * 今天之后申请的人就该享受 15 天。而退款金额里的运费是
     * <b>「当时收了多少」这个历史事实</b>，它不该被今天的规则改写。
     * <b>同一个类里，一个读配置、一个读快照 —— 这不是不一致，是两种东西。</b>
     *
     * <p>⚠️ 它必须和 {@code OrderServiceImpl.afterSaleWindowDays}
     * （给前端的 {@code afterSaleDeadline}）<b>来自同一个配置项</b>，
     * 否则「按钮显示着、点了说超期」。两边都写
     * {@code ${mall.order.after-sale-window-days}} 是刻意的 ——
     * <b>同名的配置项只有一个定义者，但如果哪天它被改名，
     * 这两个 {@code @Value} 会一起在启动时炸</b>（占位符解析失败 = 起不来），
     * 而不是静默地各读各的。这正是要的结果。
     */
    @Value("${mall.order.after-sale-window-days}")
    private int afterSaleWindowDays;

    // ==========================================================================
    //  用户端 · 申请
    // ==========================================================================

    /**
     * 申请售后。<b>一次可以申请多条明细，全成或全败。</b>
     *
     * <h3>★ 七个检查的顺序，以及为什么是这个顺序</h3>
     *
     * <p>这是一个「从最外层往里收」的漏斗 —— <b>先问「这个请求本身合法吗」，
     * 再问「这个人有权吗」，再问「这笔订单允许吗」，最后才问「这几行允许吗」</b>：
     * <pre>
     *   1. 类型/原因是不是合法取值        →  400   （协议层，和谁都无关）
     *   2. 同一个明细有没有传两遍         →  400   （请求自身矛盾）
     *   3. 订单是不是我的                 →  1003  （鉴权）
     *   4. 订单状态支不支持这种售后类型   →  1002  （订单级）
     *   5. 过没过申请时限                 →  1014  （订单级，且只有「已完成」才有）
     *   6. 明细是不是真属于这笔订单       →  1003  （请求与事实不符）
     *   7. 这些明细上有没有进行中/已退款  →  1012 / 1013（行级）
     * </pre>
     * <b>越靠前的检查越便宜、越不依赖数据库，也越不可能因为并发而变化。</b>
     * 把行级的检查放在最后，是因为只有走到了那一步，「前面全都没问题」
     * 才是已知的。
     *
     * <h3>★★ 为什么第 7 步查了一次，catch 里还要再查一次？</h3>
     *
     * <p>和下单的幂等完全同构，理由见 {@code OrderServiceImpl.submit}：
     * <pre>
     *   第 7 步的查询   →  处理【串行】的重复申请（用户自己连点两次），
     *                      顺便给出一句「已经在售后处理中」的人话
     *   catch 里的重查  →  处理【并发】的重复申请（两个请求同时进来，
     *                      第 7 步时对方都还没提交，两边都查不到）
     * </pre>
     * 而且 catch 里那次重查还多一个任务：<b>分辨是哪个唯一索引撞的</b>。
     * <ul>
     *   <li>查到一张<b>进行中</b>的单 → 是 {@code uk_order_item_active}，
     *       翻译成 1012（并发重复申请，正常情况）</li>
     *   <li>一张进行中的都没有 → 只可能是 {@code uk_after_sale_no}（单号撞了），
     *       换一个号重试一次，用户完全感知不到</li>
     * </ul>
     * ★ 这两个分支是<b>互斥且穷尽</b>的：已关闭的单 {@code active_token = id ≠ 0}，
     * 结构上不可能参与 {@code (order_item_id, 0)} 那次冲突。
     */
    @Override
    public List<AfterSaleVO> apply(AfterSaleApplyDTO dto) {
        Long memberId = currentMemberId();

        // ---- 1. 白名单。★ DTO 的 @NotNull 只管「填了没有」，不管「填的是不是 1 或 2」——
        //          理由见 AfterSaleApplyDTO.type 的注释（同一个用户动作该得到同一个错误码）。
        if (!AfterSaleType.isValid(dto.getType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "售后类型不正确");
        }
        if (!AfterSaleReason.isValid(dto.getReason())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择申请原因");
        }

        // ---- 2. 同一个明细不能在这一次请求里出现两遍 ----
        //   ★ 这一句【不是】并发防护（那个由唯一索引负责），是协议检查。
        //     传 [12, 12] 的话我们会建两张单，第二张撞 uk_order_item_active，
        //     然后用户收到一句「有商品已经在售后处理中」——
        //     而它明明是本请求自己刚建的。**错误信息说了一句不是原因的原因。**
        //   ★ 用 Set 不用「排序后比相邻」：重复可能不相邻（[12,13,12]）。
        List<Long> itemIds = dto.getOrderItemIds();
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "同一件商品只能申请一次售后");
        }

        // ---- 3. 订单必须是我的 ----
        //   明细 id 是自增的、可枚举的，不能单独承担「这单是不是你的」这个判断。
        Order order = requireOwnOrder(dto.getOrderNo(), memberId);

        // ---- 4. 订单状态决定 type ----
        checkTypeMatchesStatus(order, dto.getType());

        // ---- 5. 时限 ----
        checkWindow(order);

        // ---- 6. 明细必须属于这笔订单 ----
        //   ★ 一次查全部明细（一次 IN/索引查找），而不是逐个 selectById ——
        //     那是 N+1，而且 OrderMapper 里根本没有「只按 id 查明细」这种口子。
        //   ★ 顺序按【请求里的顺序】返回：这样日志和返回值的顺序和用户勾选的一致，
        //     排查时不用去猜对应关系。
        List<OrderItemVO> items = itemsOf(dto.getOrderItemIds(), order.getId());

        // ---- 7. 给一句人话（不负责拦住 —— 拦住是唯一索引的事）----
        checkNoActiveAfterSale(dto.getOrderItemIds());

        try {
            return transactionTemplate.execute(st -> doApply(order, items, dto, memberId));
        } catch (DuplicateKeyException e) {
            // 见方法注释：重查一次，分辨是哪个唯一索引撞的。
            List<AfterSale> rows = afterSaleMapper.selectByOrderItemIds(dto.getOrderItemIds());
            boolean hasActive = rows.stream()
                    .anyMatch(r -> !AfterSaleStatus.isClosed(r.getStatus()));
            if (hasActive) {
                log.info("并发重复申请，已有进行中的售后单: memberId={}, orderNo={}",
                        memberId, dto.getOrderNo());
                throw new BusinessException(ResultCode.AFTER_SALE_EXISTS,
                        "有商品已经在售后处理中，请先等它处理完");
            }

            // 一张进行中的都没有 → 是售后单号撞了（uk_after_sale_no）。
            // 概率极低（见 AfterSaleNoGenerator），换一个号重试一次就好。
            log.warn("售后单号冲突，换一个重试一次: memberId={}, orderNo={}",
                    memberId, dto.getOrderNo(), e);
            try {
                return transactionTemplate.execute(st -> doApply(order, items, dto, memberId));
            } catch (DuplicateKeyException retryEx) {
                // 连撞两次，不该再重试：要么随机数生成有问题，
                // 要么别的地方出了系统性问题，需要人来看日志。
                log.error("售后单号连续两次冲突，放弃重试: memberId={}, orderNo={}",
                        memberId, dto.getOrderNo(), retryEx);
                throw new BusinessException(ResultCode.ERROR, "申请失败，请稍后重试");
            }
        }
    }

    /**
     * 真正写库的那一段 —— <b>N 个 INSERT 必须在同一个事务里。</b>
     *
     * <p>★ 为什么整单退要用一个事务而不是循环调 N 次 apply？
     * 因为「退了 1 行、剩下 2 行不知道退没退」是一个用户<B>处理不了</B>的状态：
     * 他不知道该重新申请哪几行（重新申请已成功的那行会拿到 1012）。
     * <b>全成或全败，用户面对的才永远是一个说得清的状态。</b>
     *
     * <p>★ 返回的是刚建好的那几张单的<b>完整样子</b>，不是单号列表 ——
     * 「一个操作之后前端马上要用到的数据，就该由这个操作直接返回」
     * （{@code ShopOrderController} 的类注释）。申请完那几行要就地变成
     * 「售后处理中」，前端不该为了拿这个再查一次列表。
     * 这里用的是 {@code selectVOByNoAndMember}，和列表页共用
     * {@code voColumns} 片段，所以形状只有一个定义者。
     */
    private List<AfterSaleVO> doApply(Order order, List<OrderItemVO> items,
                                      AfterSaleApplyDTO dto, Long memberId) {
        List<String> nos = new ArrayList<>(items.size());
        for (OrderItemVO item : items) {
            AfterSale as = new AfterSale();
            as.setAfterSaleNo(AfterSaleNoGenerator.generate());
            as.setOrderId(order.getId());
            as.setOrderNo(order.getOrderNo());
            as.setOrderItemId(item.getId());
            // ★ memberId 直接从 JWT 来，不从请求体来，也不从「明细属于哪笔订单」
            //   反查一遍 —— 订单已经校验过归属了，再查一次是多余的第二条路径。
            as.setMemberId(memberId);
            as.setType(dto.getType());
            as.setReason(dto.getReason());
            as.setDescription(dto.getDescription());
            // ★ 只设这九个字段。status / active_token 由 SQL 里写死（0 / 0），
            //   退款四件套一个都不设 —— 它们要到退款那一刻才有值。
            afterSaleMapper.insert(as);
            nos.add(as.getAfterSaleNo());
        }

        log.info("售后申请已创建: memberId={}, orderNo={}, 售后单数={}, type={}",
                memberId, order.getOrderNo(), nos.size(), AfterSaleType.text(dto.getType()));

        return nos.stream()
                .map(no -> afterSaleMapper.selectVOByNoAndMember(no, memberId))
                .toList();
    }

    // ==========================================================================
    //  用户端 · 查询与两个动作
    // ==========================================================================

    @Override
    public PageResult<AfterSaleVO> pageMyAfterSales(AfterSaleQueryDTO query) {
        // ★ normalize() 是安全相关的一步：它把 pageSize 钳到 100 以内，
        //   否则前端传 pageSize=999999 就能一次把整个售后表拉走。
        query.normalize();

        // ★★ 查谁的售后由【JWT】决定，不由请求参数决定。
        //   AfterSaleQueryDTO 里没有 memberId 字段，所以「按前端传的会员查」
        //   在类型层面就写不出来；而 Mapper 那边 memberId 是一个独立参数，
        //   必须显式传 —— 两头一起卡住，这条路才真的堵死。
        Long memberId = currentMemberId();

        long total = afterSaleMapper.countByMember(memberId, query);
        if (total == 0) {
            // 一条都没有就不必再发第二条 SQL。PageResult.empty 保证
            // pageNum/pageSize 照常返回，前端的分页器不会因为少字段而报错。
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<AfterSaleVO> list = afterSaleMapper.selectPageByMember(memberId, query);
        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    /**
     * 撤销申请（T7：0 → 5）。
     *
     * <p>★ <b>不需要事务</b>：只有一个写，单条 SQL 本身就是原子的。
     * 判据见 {@code OrderServiceImpl.ship} 的注释 ——
     * <b>「不要为了和兄弟方法看起来一致而给它套上 TransactionTemplate。
     * 一个空事务不会让代码更安全，只会让下一个读代码的人以为这里有什么
     * 需要保护的东西。」</b>
     */
    @Override
    public AfterSaleVO cancel(String afterSaleNo) {
        Long memberId = currentMemberId();

        // ★ 先查一次不是为了「拦住」，是为了两件事：
        //   ① 拿 id（T7 的 WHERE 用的是 id + member_id，不是单号）；
        //   ② 复核这句失败时能说出「当前是待审核吗」。
        //   它和下面的条件 UPDATE 之间可以并发 —— 但没关系，
        //   闸门是 UPDATE 的 WHERE，不是这次查询。
        AfterSale as = requireOwn(afterSaleNo, memberId);

        int affected = afterSaleMapper.markCancelledByMember(as.getId(), memberId);
        if (affected == 0) {
            throw notAllowed(afterSaleNo, "只有「待审核」的售后单可以撤销");
        }

        log.info("售后申请已撤销: memberId={}, afterSaleNo={}", memberId, afterSaleNo);
        return afterSaleMapper.selectVOByNoAndMember(afterSaleNo, memberId);
    }

    /**
     * 买家填写寄回物流（T5：1 → 2）。
     *
     * <p>★ <b>不退款、不还库存</b>。这一条边唯一的效果是把「球」从买家手里
     * 传给卖家 —— 货还在路上，凭什么还库存？见 {@link #receive}。
     *
     * <p>★ 不需要事务，理由同 {@link #cancel}。
     */
    @Override
    public AfterSaleVO submitReturn(String afterSaleNo, AfterSaleReturnDTO dto) {
        Long memberId = currentMemberId();

        AfterSale as = requireOwn(afterSaleNo, memberId);

        int affected = afterSaleMapper.markReturned(as.getId(), memberId,
                dto.getReturnCompany(), dto.getReturnTracking());
        if (affected == 0) {
            throw notAllowed(afterSaleNo, "只有「待买家寄回」的售后单可以填写寄回物流");
        }

        log.info("买家已填写寄回物流: memberId={}, afterSaleNo={}, company={}, tracking={}",
                memberId, afterSaleNo, dto.getReturnCompany(), dto.getReturnTracking());
        return afterSaleMapper.selectVOByNoAndMember(afterSaleNo, memberId);
    }

    // ==========================================================================
    //  管理端
    // ==========================================================================

    @Override
    public PageResult<AdminAfterSaleVO> pageForAdmin(AdminAfterSaleQueryDTO query) {
        query.normalize();

        long total = afterSaleMapper.countAdminQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<AdminAfterSaleVO> list = afterSaleMapper.selectAdminPage(query);
        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    /**
     * 同意。★★ <b>这是一条 URL 背后的两个业务操作</b>，也是本轮最贵的那个决定。
     *
     * <pre>
     *   仅退款   （0 → 3）  同意即退款：退款 + 归还库存 + 可能推订单终态
     *   退货退款 （0 → 1）  ★ 只把球交给买家。不退款、不还库存。
     * </pre>
     *
     * <h3>★★ 为什么「同意退货」和「确认收到退货」必须是两个状态</h3>
     *
     * <p>合成一个的话，管理员能在<b>用户还没寄回</b>时就点「确认收到」——
     * <b>钱退了，货还在买家手里</b>，而且没有任何一层会报错。
     * 两个状态的全部意义：
     * <pre>
     *   状态 1（待买家寄回）  只有买家能动（填寄回单号）
     *   状态 2（待卖家收货）  只有管理员能动（确认收到）
     *   库存归还在 2 → 3 上，不在 0 → 1 上
     * </pre>
     *
     * <h3>★ 为什么这里能安全地「按会员查订单」</h3>
     *
     * <p>{@link #requireOrderOf} 用的是
     * {@code orderMapper.selectByOrderNoAndMember(orderNo, as.getMemberId())}，
     * 而那个 {@code memberId} <b>来自我们自己库里的 after_sale 行</b>，
     * 不是客户端传的 —— 所以它不是安全漏洞。
     *
     * <p>为什么不给 {@code OrderMapper} 开一个「只按 id 查」的口子：
     * 那个方法的类注释明确禁止（订单里装着收货人姓名电话住址，
     * 一个按 id 查的方法会让任何人挨个试数字翻出全平台的收货信息）。
     * <b>「管理员本来就能看到所有人的订单」不是拆掉那道禁令的理由 ——
     * 那道禁令保护的是「别让这条路存在」，而不是「别让管理员走这条路」。</b>
     */
    @Override
    public AfterSaleVO approve(String afterSaleNo) {
        AfterSale as = requireForAdmin(afterSaleNo);
        Order order = requireOrderOf(as);

        // ★ 分支看的是【库里这张单的类型】，不是请求参数 ——
        //   这个方法的签名里连一个参数都没有，所以「传错类型」在类型层面就写不出来。
        if (as.getType() == AfterSaleType.ONLY_REFUND) {
            return refundOnlyRefund(as, order);
        }
        return approveReturnRefund(as, order);
    }

    /** 同意「退货退款」（T3：0 → 1）。★ 不退款、不还库存，所以不需要事务。 */
    private AfterSaleVO approveReturnRefund(AfterSale as, Order order) {
        int affected = afterSaleMapper.markApprovedForReturn(as.getId());
        if (affected == 0) {
            throw notAllowed(as.getAfterSaleNo(), "只有「待审核」的售后单可以同意");
        }

        log.info("退货退款已同意，等买家寄回: afterSaleNo={}, orderNo={}",
                as.getAfterSaleNo(), order.getOrderNo());
        return afterSaleMapper.selectVOByNoForAdmin(as.getAfterSaleNo());
    }

    /**
     * 同意「仅退款」（T2：0 → 3）—— <b>同意即退款，本轮副作用最重的一条路径。</b>
     *
     * <h3>★★ 三个写，顺序绝不能反</h3>
     *
     * <pre>
     *   1. markRefundedForOnlyRefund   抢边。affected == 1 才往下走
     *   2. afterRefundSucceeded        还库存 + 推订单终态
     *   3. 重查返回最新样子
     * </pre>
     *
     * <p><b>抢边必须在前。</b>反过来的话，两个并发请求都会「还库存」——
     * 而 {@code StockRestoreService} 的契约明确写着它<b>不做任何资格判断</b>
     * （「谁在这里加一个 if，谁就把闸门从数据库搬回了 Java」）。
     * 顺序反了 = 库存凭空翻倍，且没有任何一层会报错。
     *
     * <h4>★ 为什么这里需要事务，而 {@link #cancel} 不需要</h4>
     *
     * <p>因为它有<b>三个</b>写：抢边、还 N 件库存、推订单终态。
     * 它们必须一起成败 —— 设想「退款成功了但库存没还」：
     * 那件货<b>永远卖不出去也永远回不来</b>，而且售后单显示「已完成」，
     * 谁都不会再去碰它。<b>判据是「有几个写」，不是「重不重要」。</b>
     *
     * <h4>★ 为什么 {@code markOrderRefundedIfAllRefunded} 不看返回值</h4>
     *
     * <p>见 {@link #afterRefundSucceeded} 的注释 —— 一句话：
     * 那条 UPDATE 的 WHERE 里没有「被竞争的资源」，{@code affected = 0}
     * 的正常含义是「还没全退完」，<b>不是错误</b>。
     */
    private AfterSaleVO refundOnlyRefund(AfterSale as, Order order) {
        // ★ 明细在事务【外面】读。它是不可变快照（price / subtotal 都是下单时定格的），
        //   所以「读到哪一版」不构成问题；而真正会变的那个输入
        //   （「别的行退完没有」）在 refundOf 里、在事务里面数 —— 见那个方法。
        OrderItemVO item = requireOrderItem(as);

        return transactionTemplate.execute(st -> {
            // ★ 金额必须在 UPDATE 之前算好：它和 status = 3 是【同一条 UPDATE 的两个列】。
            Refund refund = refundOf(as, item, order.getFreightAmount());

            int affected = afterSaleMapper.markRefundedForOnlyRefund(
                    as.getId(), refund.amount(), refund.freight(), order.getPayMethod());
            if (affected == 0) {
                // ★ 抛在这里 = 事务回滚。此时什么都还没做（副作用在下面），
                //   所以回滚是干净的 —— 这也是「抢边在前」的第三个好处。
                throw notAllowed(as.getAfterSaleNo(), "只有「待审核」的售后单可以同意");
            }

            afterRefundSucceeded(as, item);

            log.info("仅退款完成: afterSaleNo={}, orderNo={}, 退款额={}, 其中运费={}",
                    as.getAfterSaleNo(), order.getOrderNo(),
                    refund.amount(), refund.freight());
            return afterSaleMapper.selectVOByNoForAdmin(as.getAfterSaleNo());
        });
    }

    /**
     * 确认收到退货 → 退款（T6：2 → 3）。
     *
     * <p>形状和 {@link #refundOnlyRefund} 完全一样，只有两处不同：
     * <b>调的那条 UPDATE 不同</b>，以及失败时那句话不同。
     *
     * <h3>★★ 为什么是两条方法，而不是一条带 {@code expectedFrom} 参数的方法</h3>
     *
     * <p>因为<b>哪条边由 URL 决定</b>（{@code /approve} 还是 {@code /receive}），
     * 绝不能由一个运行时参数决定。合并的话，调用方传个 0 就能把
     * 「待卖家收货」的单直接结掉 —— <b>一个参数写错就等于跳过了整个退货流程，
     * 而那是「货没回来钱先退了」。</b>
     *
     * <p>★ 那为什么 {@link #afterRefundSucceeded} 反而要共用一个方法？
     * 因为那里是<b>两个副作用，不是两条边</b> ——
     * 副作用一模一样（还库存 + 推订单终态），而且漏掉任何一个都是
     * §4 那张表里的第 4 条静默故障。**分岔要分在「本来就不同」的地方，
     * 合并要合在「本来就相同」的地方。** 判据不是「像不像」，是「是不是同一件事」。
     */
    private AfterSaleVO receiveReturnRefund(AfterSale as, Order order) {
        OrderItemVO item = requireOrderItem(as);

        return transactionTemplate.execute(st -> {
            Refund refund = refundOf(as, item, order.getFreightAmount());

            int affected = afterSaleMapper.markReceivedAndRefunded(
                    as.getId(), refund.amount(), refund.freight(), order.getPayMethod());
            if (affected == 0) {
                throw notAllowed(as.getAfterSaleNo(), "只有「待卖家收货」的售后单可以确认收到");
            }

            afterRefundSucceeded(as, item);

            log.info("退货退款完成: afterSaleNo={}, orderNo={}, 退款额={}, 其中运费={}",
                    as.getAfterSaleNo(), order.getOrderNo(),
                    refund.amount(), refund.freight());
            return afterSaleMapper.selectVOByNoForAdmin(as.getAfterSaleNo());
        });
    }

    /**
     * 确认收到退货（T6：2 → 3）。★ 它就是 {@link #receiveReturnRefund} 对外的样子。
     */
    @Override
    public AfterSaleVO receive(String afterSaleNo) {
        AfterSale as = requireForAdmin(afterSaleNo);
        Order order = requireOrderOf(as);
        return receiveReturnRefund(as, order);
    }

    /**
     * 拒绝（T4：0 或 1 → 4）。
     *
     * <p>★ <b>不允许从 {@code status = 2} 拒</b>：那时货已经在路上了，
     * 拒绝会让买家陷入「东西寄走了、钱没退、单还被拒了」。
     * 完整的论证在 {@code AfterSaleMapper.markRejected} 的注释里。
     *
     * <p>★ <b>不还库存</b>。拒绝的两种情形（审查阶段拒、同意后拒）
     * 货都还在买家手里 —— 和 {@link #cancel} 一起，它们是测试 C 组的对照组。
     *
     * <p>★ 不需要事务，理由同 {@link #cancel}。
     */
    @Override
    public AfterSaleVO reject(String afterSaleNo, AfterSaleRejectDTO dto) {
        AfterSale as = requireForAdmin(afterSaleNo);

        int affected = afterSaleMapper.markRejected(as.getId(), dto.getRejectReason());
        if (affected == 0) {
            throw notAllowed(afterSaleNo, "只有「待审核」或「待买家寄回」的售后单可以拒绝");
        }

        log.info("售后申请已被拒绝: afterSaleNo={}, 理由={}", afterSaleNo, dto.getRejectReason());
        return afterSaleMapper.selectVOByNoForAdmin(afterSaleNo);
    }

    // ==========================================================================
    //  退款成功之后的两件事（★ T2 和 T6 共用这一份）
    // ==========================================================================

    /**
     * ★★ 退款成功之后的两个副作用 —— <b>T2 和 T6 共用这一份，这是刻意的。</b>
     *
     * <h4>1. 归还库存 —— 有且只有这一次</h4>
     *
     * <p>它挂在这条路径上，而不是挂在「申请」上，是因为
     * <b>调用方刚刚抢到了 {@code status → 3} 那条边</b>（{@code affected == 1}）。
     * 「这条边只可能成功一次」⟹「库存只可能被还一次」——
     * 幂等性不靠 {@code StockRestoreService} 里的判断，靠数据库的行锁 + 条件。
     *
     * <p>⚠️ <b>所以这个方法的调用位置必须紧跟在 {@code affected == 0} 的判断之后。</b>
     * 谁把它挪到前面，谁就把「只还一次」这个保证扔掉了，
     * 而 {@code StockRestoreService} 会照做（它的契约就是不做任何资格判断）。
     *
     * <h4>2. 推订单终态（T8）—— 只有一处会调它</h4>
     *
     * <p><b>★ 这里【不看返回值】。</b>判据是：这条 UPDATE 的 WHERE 里
     * 有没有「被竞争的资源」？没有。{@code affected = 0} 的正常含义是
     * 「还没全退完」，<b>不是错误</b>。检查它只会制造假的失败 ——
     * 而那个假失败发生在一个事务里，会把「退款成功、库存已还」整个回滚掉。
     *
     * <p>★★ 那为什么要把它放进这个事务？因为漏掉它的后果是
     * §4 那张表里的第 4 条：订单永远停在「已付款」，管理端一直显示「发货」按钮 ——
     * <b>点了就把退过款的东西发出去，钱货两空。</b>
     * 而「T2/T6 成功 → 推终态」是一个<b>必须一起成败</b>的配对，
     * 所以它和退款在同一个事务里，不是「反正也会被调到」。
     *
     * <h4>★ 为什么这个方法不是 {@code public}，也不在 {@code AfterSaleMapper} 之上多包一层</h4>
     *
     * <p>因为它只有两个调用者，而它们都在这个类里。抽成 Bean 只会多一个
     * 「谁还能调它」的问题 —— 而这一步<b>绝不能有第三个调用者</b>：
     * 每一个调用者都意味着「又有一条边会推订单终态」，
     * 而 {@code orders.status = 5} 那条断言的正确性依赖于
     * 「只有退款成功会推它」。<b>下一轮如果有人要加调用者，
     * 该改的是 §4.3 那条断言，不是这里。</b>
     */
    private void afterRefundSucceeded(AfterSale as, OrderItemVO item) {
        stockRestoreService.restoreOrderItems(List.of(item), as.getAfterSaleNo());
        afterSaleMapper.markOrderRefundedIfAllRefunded(as.getOrderId());
    }

    /**
     * ★★ 算这次退款该退多少钱 —— <b>「整单退才退运费」那条规则的唯一实现处。</b>
     *
     * <p>T2 和 T6 都调它，所以两条边<b>不可能退成两个数</b>。
     *
     * <h3>★ 为什么「运费退不退」要在这里现算，不能申请时就定下来</h3>
     *
     * <p>因为它的输入是「<b>这条明细退完之后，这笔订单里还有没有别的没退完的明细</b>」——
     * 而那是一个<b>在申请时还不存在的事实</b>。申请时用户可能勾了 2 行、
     * 后一行被拒了；也可能只勾了 1 行、剩下那行隔了一周才退。
     * <b>用「事后的、可验证的事实」而不是「事前的、用户的意图」来触发钱的流动。</b>
     *
     * <p>★ 所以 {@code after_sale.refund_amount} 是 {@code DEFAULT NULL}：
     * 申请时不写（「还没算出来」是<b>缺席</b>），只在 T2/T6 那一条 UPDATE 里
     * 和 {@code status = 3} 一起写一次。
     *
     * <h3>★★ 为什么数的时候要把自己排除掉</h3>
     *
     * <p>因为调用它的时刻，这条明细<b>还没有</b>被标成已退款（退款和状态
     * 是同一条 UPDATE 的两个列，不能先写一半再补）。
     * 所以问法是「<b>除了我，还有谁没退完</b>」——
     * 0 就说明「我退完就全退完了」，于是运费一并退还。
     *
     * <h3>⚠️ 并发下这个判断会「少退」，不会「多退」</h3>
     *
     * <p>诚实地说清楚：一笔 2 件的订单，两条明细的售后单<b>同时</b>被两个管理员
     * 确认收到时，两边数出来的「别的没退完的」都是 1，于是<b>两边都不退运费</b>——
     * 用户少收 ¥10。
     *
     * <p>要不要修？修它需要在这笔订单上取一把锁（{@code SELECT ... FOR UPDATE}
     * 或把 {@code orders} 行也 UPDATE 一次），而本项目<b>全库没有一把显式的锁</b>，
     * 所有并发都靠「条件 UPDATE + 唯一索引」解决。为了一个需要
     * 「两条明细的售后单在同一瞬间被审批」的窗口去引入第一把锁，
     * 代价大于收益 —— 而<b>少退的方向是安全的那一侧</b>：
     * §6.3(a) 那条断言（{@code SUM(refund_amount) <= total_amount}）
     * 保证的正是「绝不会多退」。<b>降级要降在「用户还能继续用」的那一侧。</b>
     *
     * <p>★ 还有一层保证：这个数只在<b>一次</b> UPDATE 里写下去，
     * 之后再也不重算（运费用的是快照，不是重算）。所以「少退」会稳定地
     * 停在「少退」，不会过一会儿变成另一个数。
     *
     * @param freightAmount 订单的 {@code freight_amount} 快照 ——
     *                      ★ 是<b>当时收了多少</b>，不是拿今天的规则重算的。
     *                      这就是这个类一个运费配置都不读的原因
     */
    private Refund refundOf(AfterSale as, OrderItemVO item, BigDecimal freightAmount) {
        int otherUnrefunded = afterSaleMapper.countOtherUnrefundedItems(
                as.getOrderId(), as.getOrderItemId());

        // ★ 整单退才退运费。理由：运费的商业含义是「把这批货运到你这儿」的成本，
        //   整单都退了 = 这笔运输没产生价值 = 退；部分退 = 那次运输仍然发生了 = 不退。
        BigDecimal freight = (otherUnrefunded == 0) ? freightAmount : BigDecimal.ZERO;

        // ★ 退款的商业含义就是「还给他一笔钱」。货款来自 order_item.subtotal
        //   （不可变快照，和当时实付的那部分严格相等）。
        return new Refund(item.getSubtotal().add(freight), freight);
    }

    /**
     * {@link #refundOf} 的两个输出。
     *
     * <p>★ 用一个 record 包着，而不是写两个方法：<b>它们必须来自同一次判断。</b>
     * 拆成 {@code amountOf()} 和 {@code freightOf()} 的话，两次调用各自
     * 去数一遍 {@code countOtherUnrefundedItems}，中间隔着一条 UPDATE ——
     * 于是可能算出「总额含运费、运费那一栏是 0」，而 §6.3(b) 那条原子性断言
     * 查不出这个（它只看得到 {@code status = 3} 时的运费栏）。
     * <b>一个值一旦和另一个值来自同一次观察，它们就该一起被返回。</b>
     */
    private record Refund(BigDecimal amount, BigDecimal freight) {
    }

    // ==========================================================================
    //  辅助方法
    // ==========================================================================

    private Long currentMemberId() {
        LoginUser user = UserContext.require();
        return user.id();
    }

    /**
     * 查一笔「必须是自己的」订单，查不到就抛 1003。
     *
     * <p>形状和 {@code OrderServiceImpl.requireOwnOrder} 一样，但<b>没有复用那个方法</b>：
     * 它是 {@code OrderServiceImpl} 的 private 方法，而跨 Service 调另一个 Service 的
     * 内部辅助方法意味着两件事 —— 要么把它变 public（那就成了接口的一部分，
     * 而它只是两句判断），要么让售后 Service 依赖订单 Service（售后已经
     * 依赖 {@code OrderMapper} 了，多一个 Service 依赖只是绕路）。
     *
     * <p>★ <b>这是本项目里第三份「requireXxx」</b>（{@code OrderServiceImpl} 两份、
     * 这里一份）。它们看着很像，但每一份的三句话都是<b>关于不同东西</b>的：
     * 订单说「订单不存在」，售后说「售后单不存在」。
     * <b>重复的判据是「同一事实的两份实现会不会分岔」，不是「代码看着像不像」</b>——
     * 这里没有分岔的机会，因为「订单不存在」这句话只有一个可能的写法。
     */
    private Order requireOwnOrder(String orderNo, Long memberId) {
        Order order = orderMapper.selectByOrderNoAndMember(orderNo, memberId);
        if (order == null) {
            // ★ 不区分「不存在」和「不是你的」，同 OrderServiceImpl.requireOwnOrder：
            //   区分开来等于告诉对方「这个订单号是存在的」。
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private AfterSale requireOwn(String afterSaleNo, Long memberId) {
        AfterSale as = afterSaleMapper.selectByNoAndMember(afterSaleNo, memberId);
        if (as == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "售后单不存在");
        }
        return as;
    }

    private AfterSale requireForAdmin(String afterSaleNo) {
        AfterSale as = afterSaleMapper.selectByNoForAdmin(afterSaleNo);
        if (as == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "售后单不存在");
        }
        return as;
    }

    /**
     * 按售后单自己记着的订单号 + 会员 id 把订单查回来。
     *
     * <p>★ 那两个值都来自<b>我们自己库里的 after_sale 行</b>，
     * 不是客户端传的 —— 所以走「按会员查」这条路不是安全问题。
     * 完整的论证见 {@link #approve} 的注释。
     */
    private Order requireOrderOf(AfterSale as) {
        return requireOwnOrder(as.getOrderNo(), as.getMemberId());
    }

    /**
     * 把要申请的那几条明细查出来，<b>按请求里的顺序</b>。
     *
     * <p>★ 一次查全部（{@code selectByOrderId} 走 {@code order_id} 索引），
     * 而不是逐个 {@code selectById} —— 那是 N+1，而且 {@code OrderItemMapper}
     * 根本没有「只按明细 id 查」的口子（那种方法会绕过「明细属于哪笔订单」
     * 这个判断，而这里恰恰需要它，见下一段）。
     *
     * <p>★ 顺带完成第 6 项检查：明细 id 是自增的、可枚举的，
     * 光凭「这条明细存在」不能说明它属于这笔订单 ——
     * 必须和订单自己的明细列表对一遍。
     * 对不上就是 1003，和「订单不存在」共用一个码（同样不给探测的机会）。
     */
    private List<OrderItemVO> itemsOf(List<Long> orderItemIds, Long orderId) {
        List<OrderItemVO> all = orderItemMapper.selectByOrderId(orderId);

        List<OrderItemVO> picked = new ArrayList<>(orderItemIds.size());
        for (Long id : orderItemIds) {
            OrderItemVO found = all.stream()
                    .filter(it -> it.getId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "订单明细不存在");
            }
            picked.add(found);
        }
        return picked;
    }

    /**
     * 查这张售后单对应的订单明细。
     *
     * <p>★ 用在 T2 / T6 上 —— 那里的 {@code orderItemId} 来自我们自己的售后单，
     * 所以「查不到」是不该发生的事（{@code order_item} 行被硬删了）。
     * 但<b>「不该发生」不等于「可以不写」</b>：不写的话，
     * 一个被硬删的明细会让后面的 {@code reduce} 抛
     * {@code NoSuchElementException}，用户看到「服务器错误」。
     *
     * <p>⚠️ <b>它查不到时【不能】降级成「不还库存、照样退款」。</b>
     * 那样做看起来「更宽厚」，但会把一个数据问题变成一笔静默的账目问题
     * （钱退了，库里那条明细的库存永远回不来）。抛 1003 让人来看是对的 ——
     * <b>降级要降在「用户还能继续用」的那一侧，而这里没有「用户还能用」的选项。</b>
     */
    private OrderItemVO requireOrderItem(AfterSale as) {
        return orderItemMapper.selectByOrderId(as.getOrderId()).stream()
                .filter(it -> it.getId().equals(as.getOrderItemId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "订单明细不存在"));
    }

    /**
     * 订单状态支不支持这种售后类型。
     *
     * <h3>★★ 为什么这里【不】顺手把 type 改成正确的值，而是报错</h3>
     *
     * <p>「状态是 1 就只能仅退款」这件事，服务端完全算得出来，所以
     * 「干脆忽略请求里的 type、以状态为准」看起来更健壮。不做，因为：
     * <b>type 在请求体里，是用户意图的表达</b> ——
     * 静默纠正会让「用户以为是仅退款、系统做的是退货退款」这件事发生，
     * 而且不留任何痕迹。一致性检查会抓住一个<b>把两种类型搞反了的前端</b>，
     * 静默纠正则会让那个 bug 一直藏到某天有人发现「退货不用寄回」。
     */
    private void checkTypeMatchesStatus(Order order, Integer type) {
        // ★ 一次拆箱，后面全用 int 比 —— 这一列是 NOT NULL，不会 NPE。
        int status = order.getStatus();

        if (status == OrderStatus.PAID) {
            if (type != AfterSaleType.ONLY_REFUND) {
                throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                        "订单还没发货，只能申请「仅退款」");
            }
            return;
        }
        if (status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED) {
            if (type != AfterSaleType.RETURN_REFUND) {
                throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                        "订单已经发货，只能申请「退货退款」");
            }
            return;
        }
        // 待付款、已取消、已退款 —— 三种都不能申请。
        // ★ 已退款（5）落在这里是承重的：那张订单的**全部**明细都退过款了，
        //   所以「已退款的行不能再申请」这条行级规则在订单级就已经能拦住了。
        //   但这【不是】重复检查 —— 订单级的拦住的是「整单都退完」的订单，
        //   部分退款（订单 status 还是 1/2/3）时，那一行的拦截靠下面的
        //   checkNoActiveAfterSale（1013）。
        throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                "订单当前是「" + OrderStatus.text(status) + "」，不能申请售后");
    }

    /**
     * 过期了吗 —— 只有「已完成」的订单会被判。
     *
     * <p>★★ <b>「算法」和「边界」都不在这个方法里，而在 {@code AfterSaleWindow}。</b>
     * 因为前端那一侧（{@code OrderServiceImpl.afterSaleDeadlineOf} 算出的
     * {@code afterSaleDeadline}）要用同一个算法，而两处不一致的症状是
     * 「按钮显示着、点了说超期」—— 且<b>配不出断言</b>
     * （两边看的是各自的「现在几点」）。完整的论证写在 {@code AfterSaleWindow} 的类注释里。
     *
     * <p>★ 这里只看 {@code status} 和 {@code completeTime}，
     * 特意<b>不</b>把 deadline 提前算成列存进 {@code after_sale} ——
     * 它是「申请那一刻算一次」的东西，不是需要被定格的历史事实。
     * 判据和 {@code payDeadlineOf} 完全一样：<b>会跟着配置走 → 不存。</b>
     */
    private void checkWindow(Order order) {
        LocalDateTime deadline = AfterSaleWindow.deadlineOf(
                order.getStatus(), order.getCompleteTime(), afterSaleWindowDays);

        if (AfterSaleWindow.isExpired(deadline, LocalDateTime.now())) {
            throw new BusinessException(ResultCode.AFTER_SALE_EXPIRED,
                    "已超过售后申请期限（确认收货后 " + afterSaleWindowDays + " 天）");
        }
    }

    /**
     * 这几条明细上有没有「进行中」或「已退款」的售后单。
     *
     * <p>★★ <b>它给的是「一句人话」，不是「一道闸门」。</b>
     * 两个并发的申请都能通过这里（都查到「没有进行中的单」），
     * 然后数据库只让一个进去 —— 那不是缺陷，是设计：
     * {@code ProductReviewServiceImpl.create} 已经用血写过结论
     * <b>「Java 里的『先查后写』永远挡不住并发，唯一索引才是真正的闸门」</b>。
     *
     * <p>分清楚这两件事，才不会有人觉得「既然查过了，{@link #apply} 里那个
     * {@code catch} 就是多余的」把它删掉 —— 删掉之后，并发申请会变成一个
     * 500（{@code DuplicateKeyException} 没人接），而不是 1012。
     *
     * <p>★ 一次查一批（{@code IN}），不是逐条查 —— 整单退会一次提交 N 条明细。
     * 和 {@code OrderItemMapper.selectByOrderIds} 消掉的那个 N+1 是同一个问题。
     */
    private void checkNoActiveAfterSale(List<Long> orderItemIds) {
        for (AfterSale row : afterSaleMapper.selectByOrderItemIds(orderItemIds)) {
            if (!AfterSaleStatus.isClosed(row.getStatus())) {
                throw new BusinessException(ResultCode.AFTER_SALE_EXISTS,
                        "有商品已经在售后处理中，请先等它处理完");
            }
            if (row.getStatus() == AfterSaleStatus.REFUNDED) {
                // ★ 已退款是【终态】：它挡的不只是「再申请一次」，
                //   还挡评价（见里程碑 17 的 G 组）。所以错误信息不说「请稍后再试」——
                //   没有「稍后」，入口是永久去掉的。
                throw new BusinessException(ResultCode.ORDER_ITEM_REFUNDED,
                        "有商品已经退款，不能再申请售后");
            }
        }
    }

    /**
     * 把「条件 UPDATE 没抢到边」翻译成一个说得清的错误。
     *
     * <p><b>返回而不是抛</b>，这样调用点写成 {@code throw notAllowed(...)}，
     * 一眼能看出这一句一定会抛 —— 而 {@code throw helper()} 里
     * 「helper 返回的是异常」这件事在调用点也仍然看得见（{@code throw} 在那儿）。
     *
     * <p>★ 重查一次是为了分辨「不存在」和「状态不对」：
     * <pre>
     *   查不到        →  1003（它在这期间被删了？—— 几乎不会，但说得清）
     *   查得到        →  1002 + 「当前是『待买家寄回』」
     * </pre>
     * 后者才是常见的那一种，而<b>「当前是『待买家寄回』」这句话，
     * 是调用方唯一能据以行动的信息</b> —— 只说「操作失败」的话，
     * 管理员只能反复点，或者去数据库里翻。
     */
    private BusinessException notAllowed(String afterSaleNo, String message) {
        AfterSale current = afterSaleMapper.selectByNoForAdmin(afterSaleNo);
        if (current == null) {
            return new BusinessException(ResultCode.NOT_FOUND, "售后单不存在");
        }
        return new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                "售后单当前是「" + AfterSaleStatus.text(current.getStatus()) + "」，" + message);
    }
}
