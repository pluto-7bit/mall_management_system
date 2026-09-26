package com.example.mall.mapper;

import com.example.mall.dto.AdminAfterSaleQueryDTO;
import com.example.mall.dto.AfterSaleQueryDTO;
import com.example.mall.entity.AfterSale;
import com.example.mall.vo.AdminAfterSaleVO;
import com.example.mall.vo.AfterSaleVO;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 售后数据访问接口。★ 里程碑 17 新增。
 *
 * <h3>★★ 这个文件里最重要的是那条「条件更新」的纪律</h3>
 *
 * <p>从 {@code OrderMapper.markPaid} 开始，本项目所有的<b>状态迁移</b>都是
 * 一条带 {@code WHERE 当前状态 = ?} 的条件 UPDATE，
 * 靠<b>影响行数</b>当闸门。售后这边有七条边（T1~T7），每条都是这个形状。
 *
 * <p>★ 而本轮比之前多了一层：<b>「抢到边」不只是「改状态」，
 * 它同时是「有资格做副作用」的凭证。</b>
 * 「还库存」这个动作挂在 {@code status → 3} 那两条 UPDATE 上
 * （{@link #markRefundedForOnlyRefund} 和 {@link #markReceivedAndRefunded}），
 * 因为「这条边只可能成功一次」，所以库存只可能被还一次。
 * <b>顺序反了（先还库存再抢边）＝ 并发下库存翻倍。</b>
 *
 * <h3>★ 为什么这里可以按「主键 id」或「售后单号」查，而订单那边不行？</h3>
 *
 * <p>{@code OrderMapper} 的类注释写着「这里没有 {@code selectById(Long id)}，
 * 这是刻意的」，理由是订单里装着收货人的姓名、电话、住址，
 * 一个「只按 id 查订单」的方法会让任何人挨个试数字翻出全平台的收货信息。
 *
 * <p><b>售后单里没有这类信息</b>（只有 member_id、金额、状态、物流单号），
 * 所以那道禁令在这里不适用。但纪律仍然在，只是换了形式：
 * <pre>
 *   用户端：{@link #selectByNoAndMember}  ← 带着 member_id，
 *           所以「售后单不存在」和「不是你的」查出来都是 null
 *   管理端：{@link #selectByNoForAdmin}   ← 不带 member_id，因为管理员
 *           本来就该看到所有人的售后单（同 {@code OrderAdminMapper}）
 * </pre>
 * <b>两个语义就写两个方法，不用一个「传 null 就跳过会员校验」的方法</b> ——
 * 那种分支是安全漏洞最喜欢藏身的地方（同 {@code OrderServiceImpl.requireOrderForAdmin}
 * 的注释）。
 */
public interface AfterSaleMapper {

    // ======================================================================
    //  T1 申请
    // ======================================================================

    /**
     * 插入一张售后单（T1：无 → 0 待审核）。
     *
     * <p>★ 插入时 {@code active_token = 0}（进行中），
     * {@code status = 0}，{@code refund_amount} 等四个字段<b>全部不写</b>
     * （它们要到退款那一刻才有值，见 {@code AfterSale.refundAmount}）。
     *
     * <p>★★ <b>真正的闸门是 {@code uk_order_item_active} 唯一索引，不是这个 INSERT。</b>
     * 同一行的第二次插入（两张进行中的单）会被数据库直接拒绝，
     * 调用方必须 {@code catch (DuplicateKeyException)} 并翻译成 1012。
     * <b>不要在这里加任何「先查一次再插」的逻辑</b> ——
     * Java 里的先查后写永远挡不住并发，这一点
     * {@code ProductReviewServiceImpl.create} 已经用血写过结论。
     *
     * <p>⚠️ 同理，{@code uk_after_sale_no} 也可能撞（同一秒内两个申请），
     * 调用方要换一个单号重试一次。两个唯一索引，两处 catch，缺一不可。
     *
     * @return 影响行数，正常为 1
     */
    int insert(AfterSale afterSale);

    /**
     * 查这些订单明细上<b>全部</b>的售后单（进行中的 + 已关闭的）。
     *
     * <h4>★ 它用来干什么：给正常路径一句干净的提示</h4>
     *
     * <p>申请之前要能回答两个问题：
     * <pre>
     *   这条明细已经有一张进行中的售后单了吗？  →  1012「这一行已经在处理中了」
     *   这条明细已经退过款了吗？                →  1013「这一件已经退款了」
     * </pre>
     *
     * <p>★★ <b>但它不负责「防止重复申请」，那个由唯一索引负责。</b>
     * 两个并发的申请都能通过这里（都查到「没有进行中的单」），
     * 然后数据库只让一个进去。这不是缺陷 ——
     * <b>这里查一次是为了给第 2 个人一句人话，不是为了拦住他。</b>
     * 分清楚这两件事，才不会有人觉得「既然查过了，catch 就是多余的」把它删掉。
     *
     * <h4>★ 为什么一次查一批，而不是逐条查</h4>
     *
     * <p>整单退会在一次请求里提交 N 条明细。逐条查就是 N 次往返 ——
     * 和 {@code OrderItemMapper.selectByOrderIds} 消掉的那个 N+1 是同一个问题。
     *
     * <p>⚠️ 返回的是<b>实体</b>而不是 VO：这里要的是
     * {@code order_item_id} + {@code status} + {@code active_token} 三个值，
     * 给前端看的那些字段一个都不用。投影成 VO 只会让人以为
     * 「这个方法也是给接口用的」，进而把它加进某个接口的装配路径。
     *
     * <p>⚠️ 调用方必须保证 {@code orderItemIds} 非空 ——
     * 空的 {@code IN ()} 是 SQL 语法错误，不是「返回 0 行」。
     */
    List<AfterSale> selectByOrderItemIds(@Param("orderItemIds") List<Long> orderItemIds);

    // ======================================================================
    //  查询
    // ======================================================================

    /**
     * 按「售后单号 + 会员」查一张售后单（<b>用户端</b>）。
     *
     * <p>{@code memberId} 是安全边界，不是过滤条件：少了它，
     * 任何人拿别人的售后单号就能撤销别人的申请（T7）。
     * 订单号/售后单号是<b>会出现在分享链接、客服聊天记录里的标识符，
     * 不是密码</b>。
     *
     * @return 查不到时返回 null。<b>「售后单不存在」和「不是你的」
     *         这里返回的都是 null，调用方也应该对这两种情况返回同一个错误码（1003）</b> ——
     *         区分开来等于告诉对方「这张单是存在的」。
     */
    AfterSale selectByNoAndMember(@Param("afterSaleNo") String afterSaleNo,
                                  @Param("memberId") Long memberId);

    /**
     * 按售后单号查一张售后单（<b>管理端</b>）。
     *
     * <p>管理员本来就该处理所有人的售后单，所以没有会员条件 ——
     * 和 {@link #selectByNoAndMember} 是刻意的两个方法，理由见类注释。
     *
     * @return 查不到时返回 null
     */
    AfterSale selectByNoForAdmin(@Param("afterSaleNo") String afterSaleNo);

    /**
     * 按「售后单号 + 会员」查一张售后单的<b>完整 VO</b>（用户端）。
     *
     * <h4>★ 为什么要有「实体」和「VO」两套单行查询？</h4>
     *
     * <p>因为它们被用在两个不同的时刻，而这两个时刻需要的东西不一样：
     * <pre>
     *   实体（selectByNoAndMember）  →  动作【之前】。
     *        Service 要读 status 和 type 才能说出「当前是『待买家寄回』，
     *        无法撤销」这种话，还要拿 member_id / order_id 往下走。
     *        这几个字段一个都不给前端看，所以不该走 VO 那条列清单。
     *
     *   VO（这个方法）             →  动作【之后】。
     *        接口要把这一行最新的样子还给调用方（前端不刷新就能就地更新那一行）。
     *        「一个操作之后前端马上要用到的数据，就该由这个操作直接返回」——
     *        这句话写在 ShopOrderController 的类注释里。
     * </pre>
     *
     * <p>★ 这两条查询共用 {@code <sql id="voColumns">}，
     * 所以「返回的形状」只有一个定义者 —— 加字段时不会出现
     * 「列表里有、详情里没有」。**这是敢开两条查询的前提。**
     *
     * <p>⚠️ 它们<b>不</b>共用 {@code <sql id="queryCondition">}：
     * 那个片段里有一句 {@code #{query.status}}，要一个分页 DTO 参数，
     * 而这个方法是「按唯一键查一行」，既不分页也不筛选。
     * 所以这里的 WHERE 和 {@link #selectByNoAndMember}（它的孪生兄弟）
     * <b>逐字相同</b>——「查得到」这件事必须两边完全一致，
     * 而「查得出什么字段」才是它们的差别。
     *
     * <p>★ 顺带记一条踩过的坑：MyBatis 按<b>名字</b>绑参数，
     * 名字对不上是<b>运行期</b>错误 —— 编译不报、启动不报，
     * 只有这个语句第一次真的被执行时才抛 {@code Parameter 'query' not found}。
     * 所以这种错只有「走到这条路径」的测试才抓得住，
     * 而它当初就是躲在「申请成功」那条正常路径后面的。
     *
     * @return 查不到时返回 null（不存在 / 不是你的，刻意合并）
     */
    AfterSaleVO selectVOByNoAndMember(@Param("afterSaleNo") String afterSaleNo,
                                      @Param("memberId") Long memberId);

    /**
     * 按售后单号查一张售后单的<b>完整 VO</b>（管理端）。
     *
     * <p>和 {@link #selectVOByNoAndMember} 是刻意的两个方法，理由同
     * {@link #selectByNoForAdmin}（管理员处理所有人的售后单，没有会员条件）。
     */
    AfterSaleVO selectVOByNoForAdmin(@Param("afterSaleNo") String afterSaleNo);

    /**
     * 分页查当前会员的售后单（可按状态筛选）。
     *
     * <p>★ 它带 {@code member_id} 条件，所以放在这个文件里是合规的。
     * 和管理端那两条一样，它<b>不查明细</b>（明细由 SQL 里那个
     * 一对一的 join 直接带出来，见 XML 的说明）。
     *
     * <p>⚠️ 和 {@link #countByMember} 必须用<b>同一份</b>
     * {@code <sql id="queryCondition">} 片段拼条件 ——
     * 不一致的症状是「页面显示 12 条、分页器说总共 8 条」。
     *
     * @param memberId <b>安全边界</b>，必须来自 JWT
     * @param query    分页参数 + 可选的 status 筛选（null 表示「全部」）
     */
    List<AfterSaleVO> selectPageByMember(@Param("memberId") Long memberId,
                                         @Param("query") AfterSaleQueryDTO query);

    /** 数当前会员有多少张售后单（条件和 {@link #selectPageByMember} 完全一致） */
    long countByMember(@Param("memberId") Long memberId,
                       @Param("query") AfterSaleQueryDTO query);

    /**
     * 分页查全部会员的售后单（管理端）。
     *
     * <p>⚠️ 它<b>不</b>带 {@code member_id} 条件，这是这个方法的特别之处，
     * 理由和 {@code OrderAdminMapper.selectAdminPage} 一样：
     * 管理员就该看到所有人的售后单。<b>安全边界在路径前缀</b>——
     * {@code /api/admin/**} 整个被 {@code AdminAuthInterceptor} 拦着。
     *
     * <p>⚠️ 和 {@link #countAdminQuery} 共用 {@code <sql id="adminQueryCondition">}。
     *
     * <p>★★ <b>这个参数【不加】{@code @Param("query")}</b>，这是有意的，
     * 而且和 {@code OrderAdminMapper.selectAdminPage(OrderQueryDTO query)} 逐字一致。
     *
     * <p>判据是 MyBatis 的一处规则：<b>方法只有一个参数、且它是 POJO 时，
     * 参数可以不加注解 —— 此时 MyBatis 把它的【属性】直接摊开</b>，
     * 于是 XML 里写 {@code #{status}} / {@code #{offset}} 就能取到值。
     * 反过来，一旦加了 {@code @Param("query")}，取法就变成 {@code #{query.status}}，
     * 裸写 {@code #{status}} 会抛 {@code Parameter 'status' not found}。
     *
     * <p>⚠️ 而那个报错是<b>运行期</b>的：编译不报、启动不报，
     * 这个语句第一次真的被执行时才炸。所以两种写法必须全项目统一 ——
     * 统一在「单 POJO 参数不加注解，XML 裸写属性名」这一边，
     * 因为分页片段 {@code PageQueryDTO} 里的 {@code offset} 本来就是裸用的。
     *
     * <p>⚠️ 对比 {@link #selectPageByMember}：它有<b>两个</b>参数，所以必须
     * {@code @Param("query")}，XML 里也相应地写 {@code #{query.status}}。
     * <b>「加不加注解」不是风格问题，它决定了 XML 里那个名字怎么写。</b>
     */
    List<AdminAfterSaleVO> selectAdminPage(AdminAfterSaleQueryDTO query);

    /**
     * 数全部会员有多少张售后单（条件和 {@link #selectAdminPage} 完全一致）。
     *
     * <p>★ 参数写法同 {@link #selectAdminPage}：单 POJO，不加 {@code @Param}。
     */
    long countAdminQuery(AdminAfterSaleQueryDTO query);

    // ======================================================================
    //  T2 ~ T7：七条边里的六条条件更新
    // ======================================================================

    /**
     * <b>T2 · 同意「仅退款」= 同意即退款</b>（0 待审核 → 3 退款完成）。
     *
     * <h4>★★ {@code AND type = 1} 是这一条的命门</h4>
     *
     * <p>没有它，对一张「退货退款」的单调用这个方法也会成功 ——
     * 于是<b>货还在买家手里，钱已经退了</b>，而且没有任何一层会报错。
     * 客服会以为「我点的是同意，怎么钱就没了」。
     *
     * <p>★ <b>它是冗余守卫，不是选边机制。</b>Service 会先读一次行
     * （为了鉴权和一句准确的中文提示，那本来就是必须的），
     * 然后调对应的那一条。形状和 {@code markPaid} 里
     * 「Java 算了 deadline、SQL 里也带 {@code create_time > deadline}」完全一样。
     * <b>冗余的条件留在 WHERE 里，因为 WHERE 才是闸门，Java 那层只是提示。</b>
     *
     * <h4>★ 为什么这一条和 T6 是两条 SQL，而不是一条带 expectedFrom 的方法</h4>
     *
     * <p>因为<b>哪条边由 URL 决定</b>（{@code /approve} 还是 {@code /receive}），
     * 绝不能由一个运行时参数决定。合并成一个方法的话，
     * 调用方传个 0 就能把「待买家寄回」的单直接结掉 ——
     * 一个参数写错就等于跳过了整个退货流程。
     *
     * <h4>★ 退款三件套 + {@code active_token} 在同一条 UPDATE 里</h4>
     *
     * <p>{@code status = 3}、{@code refund_amount}、{@code refund_time}、
     * {@code refund_method}（外加 {@code refund_freight}）
     * <b>必须是同一次写入</b>——否则会出现「状态说退了、金额还是 null」，
     * 而那正是 §6.3(b) 那条原子性断言要抓的形态。
     *
     * <p>{@code active_token = id} 和 {@code status = 3} 也是同一次写入。
     * <b>「关闭」和「释放令牌」结构上不可能分开</b>，所以不存在
     * 「该释放没释放 → 那一行永远申请不了售后」这个错误。
     *
     * @param refundAmount  本行货款 +（整单退时的）运费，<b>由 Service 算好传进来</b>。
     *                      SQL 不参与算钱 —— 它读不到 {@code orders.freight_amount}
     *                      和「别的行退完没有」这两个输入
     * @param refundFreight 其中属于运费的部分；部分退时是 0
     * @param refundMethod  退款去向 = 该订单 {@code pay_method} 的快照
     * @return 影响行数。<b>1 = 我抢到了这条边，现在有资格归还库存；
     *         0 = 不存在 / 状态不是待审核 / 这张单不是仅退款</b>
     */
    int markRefundedForOnlyRefund(@Param("id") Long id,
                                  @Param("refundAmount") BigDecimal refundAmount,
                                  @Param("refundFreight") BigDecimal refundFreight,
                                  @Param("refundMethod") String refundMethod);

    /**
     * <b>T3 · 同意「退货退款」</b>（0 待审核 → 1 待买家寄回）。
     *
     * <p>★ 它<b>不做任何副作用</b>：不退款、不还库存。
     * 这是本轮最贵的一个决定 —— 见 {@code AfterSaleStatus} 类注释里
     * 那段「同意退货 和 确认收到退货 必须是两个状态」。
     *
     * <p>★ 管理端操作别人的单，所以<b>没有 {@code member_id}</b>
     * （同 {@code OrderAdminMapper.markShipped}）。
     *
     * <p>★ {@code AND type = 2} 的理由同 T2，方向相反。
     *
     * @return 影响行数。<b>1 = 同意成功（等买家寄回）；0 = 不存在 /
     *         状态不是待审核 / 这张单不是退货退款</b>
     */
    int markApprovedForReturn(@Param("id") Long id);

    /**
     * <b>T4 · 拒绝</b>（0 待审核 <b>或</b> 1 待买家寄回 → 4 已拒绝）。
     *
     * <h4>★ 为什么允许从 {@code status = 1} 拒，却<b>不允许</b>从 {@code status = 2} 拒</h4>
     *
     * <p>{@code status = 1} 是「我已经同意了，但还没收到货」——
     * 管理员看了买家发来的照片后反悔，是合理的。
     * 而 {@code status = 2}（待卖家收货）意味着<b>货已经在路上了</b>：
     * 这时候拒绝，买家会陷入「东西寄走了、钱没退、单还被拒了」的状态，
     * 而正确的操作只有一个 —— 确认收到（然后如果货不对，那是另一个流程的事）。
     * <b>「货在途」这个事实让「拒绝」不再是一个可选项。</b>
     *
     * <h4>★ 它也不还库存</h4>
     *
     * <p>拒绝有两种：审查阶段拒（货还在买家手里）和同意后拒（货也还在买家手里）。
     * 两种都<b>不接触库存</b> —— 和 {@link #markCancelledByMember} 一起，
     * 它们正好是测试 C 组的对照组。
     *
     * @param rejectReason 必填理由，见 {@code AfterSaleRejectDTO}
     * @return 影响行数。<b>1 = 拒绝成功；0 = 不存在 / 状态不是 0 或 1</b>
     */
    int markRejected(@Param("id") Long id,
                     @Param("rejectReason") String rejectReason);

    /**
     * <b>T5 · 买家填寄回物流</b>（1 待买家寄回 → 2 待卖家收货）。
     *
     * <p>★ 这是<b>用户端</b>的动作，所以带 {@code member_id} 安全边界 ——
     * 只有买家自己能填。管理员填不了（他不知道买家寄了什么）。
     *
     * <p>★ 它<b>不接触库存</b>，也不退款。这一条边唯一的效果是
     * 把「球」从买家手里传给卖家。
     *
     * @return 影响行数。<b>1 = 填写成功；0 = 不存在 / 不是该会员的 /
     *         状态不是待买家寄回</b>
     */
    int markReturned(@Param("id") Long id,
                     @Param("memberId") Long memberId,
                     @Param("returnCompany") String returnCompany,
                     @Param("returnTracking") String returnTracking);

    /**
     * <b>T6 · 确认收到退货 → 退款</b>（2 待卖家收货 → 3 退款完成）。
     *
     * <p>★★ <b>这是整轮里唯一一处「库存归还」和「退货」真正相遇的地方。</b>
     * 货在买家手里待了一路，直到管理员确认收到，它才回到仓库 ——
     * 所以归还库存挂在这一条边上，不在 T3 上。
     *
     * <p>★ 管理端动作，没有 {@code member_id}（同 T3）。
     *
     * <p>★ 没有 {@code AND type = 2}：{@code status = 2} 这个状态
     * <b>只可能由 T3 产生</b>，而 T3 已经带了 {@code type = 2}。
     * 再加一遍是一条不会红的检查（判据：一条不会红的检查等于没有检查）。
     * ⚠️ 但如果哪天有了第二条进入 {@code status = 2} 的边，
     * 这里就必须补上 —— 所以这句话写在这里。
     *
     * <p>{@code receive_time} 和退款三件套同一次写入：它是「货回来了」的凭据，
     * 而钱是因为货回来了才退的，两者分开写就会出现「退了钱但没有收货时间」。
     *
     * @param refundAmount  本行货款 +（整单退时的）运费
     * @return 影响行数。<b>1 = 我抢到了这条边，现在有资格归还库存；
     *         0 = 不存在 / 状态不是待卖家收货</b>
     */
    int markReceivedAndRefunded(@Param("id") Long id,
                                @Param("refundAmount") BigDecimal refundAmount,
                                @Param("refundFreight") BigDecimal refundFreight,
                                @Param("refundMethod") String refundMethod);

    /**
     * <b>T7 · 用户撤销申请</b>（0 待审核 → 5 已撤销）。
     *
     * <h4>★ 为什么只允许从 {@code status = 0} 撤</h4>
     *
     * <p>因为 {@code status = 1}（管理员已同意退货）之后，
     * 买家手上的货已经在往回寄的路上了 —— 这时候「撤销」是什么意思？
     * 东西退回来还是不退？钱退不退？每个答案都需要一段新的规则。
     * 所以这里只给最干净的那条边：<b>还没人动手的时候，可以反悔。</b>
     *
     * <p>★★ <b>它还是「唯一一条永不接触库存的关闭边」</b>，
     * 这一点是承重的：它让测试 C 组能拿一条真正走完的完整流程
     * 去当「库存一个数都没动」的对照组 ——
     * 如果撤销也还库存，那个对照组就失去意义了（因为两边都动了库存，
     * 断言「没动」会失败，而失败的原因无法区分是「不该动却动了」
     * 还是「本来就该动」）。
     *
     * <p>★ 用户端动作，带 {@code member_id}。★ 没有 {@code reject_reason}
     * 之类的字段：撤销不需要解释（和「同意」不需要参数是同一条）。
     *
     * @return 影响行数。<b>1 = 撤销成功；0 = 不存在 / 不是该会员的 /
     *         状态不是待审核</b>
     */
    int markCancelledByMember(@Param("id") Long id,
                              @Param("memberId") Long memberId);

    // ======================================================================
    //  T8：订单终态（★ 全项目只有一处会调它）
    // ======================================================================

    /**
     * 算「这笔订单里，除了这一条明细，还有几条没有退完」。
     *
     * <h4>★★ 它存在的理由只有一个：决定这次退款要不要带上运费</h4>
     *
     * <p>运费的商业含义是「把这批货运到你这儿」的成本。
     * <b>整单都退了 = 这笔运输没产生价值 = 退；部分退 = 那次运输仍然发生了 = 不退。</b>
     *
     * <p>★ 所以退款<b>之前</b>就得知道「退完这一条之后，是不是全退完了」——
     * 因为退款金额要和状态在同一条 UPDATE 里写下去（不能先写一半再补），
     * 而那时候这一条自己还没被标记成已退款。所以要<b>把自己排除在外</b>再数。
     *
     * <h4>★ 它和 {@link #markOrderRefundedIfAllRefunded} 的子查询是同一个谓词，只是时刻不同</h4>
     *
     * <p>两句 SQL 里 {@code NOT EXISTS (SELECT 1 FROM after_sale a
     * WHERE a.order_item_id = i.id AND a.status = 3)} 逐字相同 ——
     * 一句问「退款前，还有谁没退完（不含我）」，另一句问「退款后，还有谁没退完」。
     * <b>这是同一事实的两份实现</b>（本项目的老毛病），
     * 所以配了一条断言：F 组「整单退退货款 + 运费」和 I 组「T8 双向」必须同时成立 ——
     * 两边判错的方向是相反的（多退一份运费 / 漏推订单终态），
     * 一起测才盖得住。
     *
     * <p>⚠️ 返回的是 {@code COUNT} 而不是 boolean：调用方判 {@code == 0}。
     * 用 boolean 的话错误信息里说不出「还有 2 件没退完」，
     * 而这句话在排查时是有用的。
     *
     * @return 还没退完的明细条数（<b>不含 {@code excludeOrderItemId} 自己</b>）。
     *         0 = 这是最后一件
     */
    int countOtherUnrefundedItems(@Param("orderId") Long orderId,
                                  @Param("excludeOrderItemId") Long excludeOrderItemId);

    /**
     * <b>T8 · 把订单推到终态「已退款」</b>（{@code orders.status} 1/2/3 → 5）。
     *
     * <h4>★★ 它赚到位置的原因：它同时是 {@code markShipped} 和 {@code markCompleted} 的闸门</h4>
     *
     * <p>一张「全部明细都已退款」的已付款订单如果不加终态，会<b>永远停在「已付款」</b>，
     * 而 {@code OrderAdminMapper.markShipped} 的 {@code WHERE status = 1} 就会放行 ——
     * <b>管理员点一下「发货」，就把已经退过款的东西发出去了，钱货两空，
     * 而且没有任何一层会报错。</b>同理用户端的「确认收货」也会在它上面继续显示。
     *
     * <h4>★★ 不要检查影响行数</h4>
     *
     * <p>判据是：<b>这条 UPDATE 的 WHERE 里有没有「被竞争的资源」？</b>没有。
     * {@code affected = 0} 的正常含义是「还没全退完」，<b>不是错误</b>。
     * 检查它只会制造假的失败 —— 而那个假失败发生在一个事务里，
     * 会把「退款成功、库存已还」整个回滚掉。
     *
     * <h4>★ 为什么「重算」在这里不违反「不加汇总列」那条铁律</h4>
     *
     * <p>漂移的定义是<b>「两个写入者给出不同的答案」</b>。
     * 这里只有一个写入者、一个方向 —— 售后状态只会往 3 走、不会回头，
     * 所以「全退完了」<b>一旦成立就永久成立</b>，重复执行任意次结果相同。
     * <b>它可以被重算，因为它不会抖动。</b>
     *
     * <h4>⚠️ MySQL 语法提醒：子查询里不能出现 {@code orders}</h4>
     *
     * <p>下面查的是 {@code order_item} 和 {@code after_sale}，安全。
     * 下一个人如果在这里加一个 {@code JOIN orders}，会得到
     * {@code ERROR 1093 (HY000): You can't specify target table 'o' for update
     * in FROM clause}。<b>会炸是好事</b>，但提前说一句省一次调试。
     *
     * @return 影响行数。<b>1 = 订单被推到终态；0 = 还没全退完 / 订单已经不在流程里了。
     *         两种都不是错误，调用方不该看这个返回值。</b>
     */
    int markOrderRefundedIfAllRefunded(@Param("orderId") Long orderId);
}
