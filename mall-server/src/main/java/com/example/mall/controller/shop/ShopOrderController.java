package com.example.mall.controller.shop;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.BuyNowDTO;
import com.example.mall.dto.CartOrderDTO;
import com.example.mall.dto.PayDTO;
import com.example.mall.dto.ShopOrderQueryDTO;
import com.example.mall.service.OrderService;
import com.example.mall.vo.OrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（<b>用户端</b>）。
 *
 * <h3>★ 路径 {@code /api/shop/orders} 没有被排除出拦截器</h3>
 *
 * <p>和 {@code /api/shop/cart/**} 一样。<b>订单是纯私人数据</b>
 * （收货人姓名、电话、住址），而且下单还会扣库存 ——
 * 没有任何一个接口该对游客开放。
 *
 * <p>注意 {@code WebMvcConfig} 里排除的是 {@code /api/shop/products/**}
 * 和 {@code /api/shop/categories}，那是给游客浏览商品用的。
 * <b>同样是 {@code /api/shop/} 开头，安全等级完全不同 ——
 * 看一个接口要不要登录，唯一可信的依据是排除列表，不是路径的"大前缀"。</b>
 *
 * <h3>★ 为什么下单接口的响应里返回整个订单？</h3>
 *
 * <p>按"只有当服务端会算出前端不知道的东西时才返回数据"这条原则
 * （见 {@code ShopAddressController.update}），下单显然符合 ——
 * 订单号、订单 id、算出来的总金额、状态，都是前端事先不知道的。
 *
 * <p>那为什么不只返回一个 {@code orderId} 让前端再查一次详情？
 * 因为<b>下单结果页需要立刻展示这些信息</b>：
 * 「订单提交成功，订单号 xxxxx，应付 ¥123.00」。
 * 只给 id 的话，前端要立刻再发一个请求，多一次往返 ——
 * 而且那一次请求如果失败，用户会看到"下单成功但页面报错"，
 * 体验和语义都很糟。
 *
 * <p><b>★ 这里有个通用判断：一个操作之后前端马上要用到的数据，
 * 就该由这个操作直接返回，不要让它再查一次。</b>
 * 对比购物车的"删除"接口返回 {@code Result<Void>} ——
 * 删完之后前端手上的数据已经够了（少了一条而已），
 * 不需要服务端再告诉它什么，所以那边不返回数据是对的。
 * <b>标准是"前端还要不要再问一次"，不是"统一都要返回"或"统一都不返回"。</b>
 *
 * <h3>★ 两个接口，一个 Service 方法</h3>
 *
 * <p>这两个方法的实现都是一行 —— 因为真正干活的逻辑在
 * {@code OrderServiceImpl} 里合成了同一个方法（见 {@code OrderSource} 的注释）。
 *
 * <p><b>Controller 这一层就该这么薄。</b>它的职责只有三件事：
 * <pre>
 *   1. 定义 URL 和方法（HTTP 语义）
 *   2. 声明参数校验（@Valid）
 *   3. 把 Service 的结果包成统一响应格式（Result）
 * </pre>
 * 任何一行业务判断出现在 Controller 里，都说明它放错了地方 ——
 * 因为 Controller 层拿不到事务，也不该被别的入口（定时任务、
 * 管理端代下单）复用。
 */
@RestController
@RequestMapping("/api/shop/orders")
@RequiredArgsConstructor
public class ShopOrderController {

    private final OrderService orderService;

    /**
     * 购物车结算下单。
     *
     * <p>{@code POST /api/shop/orders}
     *
     * <p>请求体里只有「勾选了哪几种商品」+「寄到哪个地址」+
     * 「幂等键」+「备注」，<b>没有数量、没有金额</b>：
     * <pre>
     *   {
     *     "productIds": [3, 7],
     *     "addressId": 1,
     *     "idempotencyKey": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
     *     "remark": "工作日送"
     *   }
     * </pre>
     * 数量由服务端从 Redis 购物车读，金额由服务端按商品现价算。
     * 理由见 {@code CartOrderDTO} 和 {@code OrderBaseDTO} 的注释。
     *
     * <p>⚠️ 为什么用 {@code POST} 而不是 {@code PUT}？
     * 因为它的语义是「新增一笔订单」，不是「把某个已有的资源改成某个状态」。
     * 而且它不是幂等的 —— 同一个请求发两次（换个幂等键）就该产生两笔订单。
     * （"幂等键"解决的是"同一次下单意图被重复提交"，
     *   不是"这个接口本身幂等"。这两件事容易混，见下面的说明。）
     *
     * <h4>★ 「幂等键」和「接口幂等」是两个不同的概念</h4>
     *
     * <p>这一点值得单独说清楚，因为很容易觉得"有了幂等键，这接口就幂等了"：
     * <pre>
     *   接口幂等   =  同一个请求发 N 次，效果和发 1 次一样（HTTP 语义层面）
     *   幂等键     =  客户端给"一次下单意图"编号，服务端靠它识别重复提交
     * </pre>
     * 下单接口<b>不是</b>接口幂等的：不带幂等键发两次，就是两笔订单，
     * 这是对的（用户确实想买两次）。
     * 幂等键保护的是<b>同一次意图</b>被重复发送 ——
     * 用户点了一次提交，但网络超时让他又点了一次。
     *
     * <p><b>所以幂等键的作用范围完全取决于客户端怎么生成它</b>
     * （要在进结算页时生成，不是点提交时生成），
     * 见 {@code OrderBaseDTO.idempotencyKey} 的注释。
     * 服务端只能保证"同一个键不建两单"，
     * 保证不了"用户想买的两次不会被当成一次"。
     */
    @PostMapping
    public Result<OrderVO> createFromCart(@Valid @RequestBody CartOrderDTO dto) {
        return Result.success(orderService.createFromCart(dto));
    }

    /**
     * 立即购买（不经过购物车）。
     *
     * <p>{@code POST /api/shop/orders/buy-now}
     *
     * <pre>
     *   {
     *     "productId": 3,
     *     "quantity": 2,
     *     "addressId": 1,
     *     "idempotencyKey": "...",
     *     "remark": ""
     *   }
     * </pre>
     *
     * <p><b>★ 为什么路径是 {@code /orders/buy-now} 而不是
     * 单独开一个 {@code /api/shop/buy-now}？</b>
     * 因为"立即购买"和"购物车结算"<b>产生的是同一种东西 —— 订单</b>，
     * 只是入口不同。把两个产生订单的入口挂在同一个路径树下：
     * <pre>
     *   /api/shop/orders            购物车结算
     *   /api/shop/orders/buy-now    立即购买
     * </pre>
     * 好处是<b>权限规则只需要写一条</b> —— 拦截器按 {@code /api/shop/orders/**}
     * 拦截，两个入口自动都受保护。
     * 如果拆到 {@code /api/shop/buy-now}，就要记得在排除列表那里
     * 多想一次"这条要不要加进去"，而漏想的后果是那个接口裸奔。
     *
     * <p><b>★ 路径的组织方式本身就是一种安全设计。</b>
     * 把"会改数据的操作"按业务实体归类到同一棵树，
     * 比按"页面在哪"归类要安全得多 —— 因为权限是按树配的。
     *
     * <p>⚠️ 注意 {@code /buy-now} 这个路径段和 {@code POST /orders} 不冲突：
     * 一个匹配两段，一个匹配三段，Spring 按路径段数匹配
     * （同 {@code ShopAddressController} 里 {@code /default} 和 {@code /{id}} 的讨论）。
     *
     * <h4>★ 里程碑 9 之后这里【有】路径变量了，要重新确认一遍不冲突</h4>
     *
     * <p>下面新加的 {@code /{orderNo}} 引入了本 Controller 的第一个路径变量。
     * 所以那句「这个 Controller 里没有 {@code /{id}}，所以连 buy-now 被当成 id
     * 的机会都没有」已经不再成立，必须重新对一遍。
     * <b>加路径变量的同时，要检查所有已有的固定路径段 —— 这是常规动作。</b>
     *
     * <p>对完的结果是仍然安全，但理由是新的：
     * <pre>
     *   POST /orders/buy-now     两段，固定字面量
     *   POST /orders             一段
     *   GET  /orders/{orderNo}   两段，有变量
     *   POST /orders/{orderNo}/pay     三段
     *   POST /orders/{orderNo}/cancel  三段
     * </pre>
     * 和 {@code buy-now} 会撞的只有「两段 + POST」，而 {@code /{orderNo}}
     * 是<b>两段 + GET</b> —— <b>HTTP 方法不同，Spring 不会把它们当成同一个映射</b>。
     * 所以 {@code POST /orders/buy-now} 永远匹配到
     * {@code createByBuyNow}，不会掉进 {@code getOne}。
     *
     * <p>⚠️ 但这里有个更隐蔽的隐患值得记下来：如果哪天有人写了一个
     * 「两段 + POST」的接口（比如 {@code POST /orders/{orderNo}/refund} 写成两段），
     * 那么 Spring 会优先匹配<b>字面量</b>而不是路径变量 ——
     * 也就是说 {@code buy-now} 仍然安全，但 {@code POST /orders/xxx}
     * 这种请求就会落进那个新接口。
     * <b>「字面量优先于路径变量」这条规则保证了已有接口不会被新变量抢走，
     * 但要小心的是反过来的方向：新加的固定路径段可能抢走本该由变量处理的路由。</b>
     */
    @PostMapping("/buy-now")
    public Result<OrderVO> createByBuyNow(@Valid @RequestBody BuyNowDTO dto) {
        return Result.success(orderService.createByBuyNow(dto));
    }

    // ==========================================================================
    // 里程碑 9：查详情 / 支付 / 取消
    //
    // ★ 这三个接口的权限规则【一个字都不用改】：
    //   WebMvcConfig 里排除出拦截器的是 /api/shop/products/** 和
    //   /api/shop/categories，/api/shop/orders/** 不在其中，
    //   所以新接口自动落在 MemberAuthInterceptor 后面。
    //   —— 这正是上面说的「按业务实体归类到同一棵树」的好处：
    //      新增接口天然继承保护，不需要每次都去安全配置里想一遍。
    //      ⚠️ 反过来，如果哪天有人把 /api/shop/orders 加进排除列表，
    //         「查订单详情」就会变成任何人只要知道订单号就能读别人的
    //         姓名电话住址。改那个列表之前必须看这里。
    // ==========================================================================

    /**
     * 查订单详情。
     *
     * <p>{@code GET /api/shop/orders/{orderNo}}
     *
     * <p>用户端第一次能「回头看自己的订单」—— 之前只有创建接口，
     * 建完就再也查不到了。收银台页（{@code /pay/:orderNo}）打开时
     * 就是靠这个接口知道该显示「待付款」还是「已付款」。
     *
     * <p><b>★ 为什么用订单号（orderNo）而不是自增 id？</b>
     * 因为自增 id 是<b>连续的、可枚举的</b> —— 1、2、3……
     * 拿它当 URL 参数，等于告诉所有人「我们有多少订单、你是第几单」，
     * 而且让人忍不住去试下一个数字。
     * 订单号是 20 位的业务编号（见 {@code OrderNoGenerator}），
     * 猜不出来，也不泄露任何规模信息。
     *
     * <p>⚠️ 但<b>不要因此以为「猜不出来」就是安全措施</b>。
     * 真正的安全边界是 SQL 里的 {@code AND member_id = #{memberId}} ——
     * 订单号会出现在浏览器历史、分享链接、客服聊天记录里，
     * <b>它是一个标识符，不是一个密码</b>。
     * 用订单号只是"少泄露一点"，不是"可以不校验归属"。
     */
    @GetMapping("/{orderNo}")
    public Result<OrderVO> getOne(@PathVariable String orderNo) {
        return Result.success(orderService.getByOrderNo(orderNo));
    }

    /**
     * 支付订单（模拟）。
     *
     * <p>{@code POST /api/shop/orders/{orderNo}/pay}
     *
     * <pre>
     *   { "payMethod": "ALIPAY" }        // 取值见 PayMethod
     * </pre>
     *
     * <p><b>★ 为什么路径是 {@code /{orderNo}/pay}，而不是
     * {@code POST /orders/pay} 把订单号放请求体里？</b>
     * 两个理由：
     * <ol>
     *   <li><b>订单号是「要操作哪个资源」，不是「操作的内容」。</b>
     *       它属于 URL。<b>URI 标识资源，请求体描述对资源的操作</b> ——
     *       这是 REST 最基本的划分。支付方式的取值才是"操作的内容"，
     *       所以它在请求体里。</li>
     *   <li><b>可以把「支付」和「取消」看成订单的两种动作</b>，
     *       挂在同一个 {@code {orderNo}} 下面，
     *       将来加「确认收货」「申请退款」也是同样的形状。</li>
     * </ol>
     *
     * <p>⚠️ 但这里必须承认一个<b>代价</b>：订单号出现在了 URL 里，
     * 也就可能出现在 access log、浏览器历史、Referer 头里。
     * 这是 REST 风格的通病，真实的支付系统会因此把敏感操作
     * 改成 {@code POST /orders/pay} + 请求体传订单号。
     * <b>本项目这么写是因为它更好理解、也更接近真实电商前端的做法；
     * 但要知道这个选择是有代价的，不是纯粹的风格问题。</b>
     *
     * <h4>★ 为什么用 POST 而不是 PUT？</h4>
     *
     * <p>「支付」看起来很像「把订单改成已付款状态」，而 PUT 是幂等的
     * （同一个请求发 N 次，效果和发一次一样）—— 这一点其实符合。
     * 但本项目仍然用 POST，理由是：
     * <pre>
     *   支付【不是】幂等的：「再付一次」是一次新的付款尝试，
     *   在真实系统里可能真的扣掉第二笔钱。
     *   服务端能拦住重复付款（靠 status = 0 那个条件），
     *   但那是【业务层的保护】，不是【HTTP 语义上的承诺】。
     * </pre>
     * <b>用 PUT 就等于向调用方承诺"你可以随便重发"，
     * 而这个承诺是靠不住时最危险的那种 —— 它涉及钱。</b>
     *
     * <p>另外还有一个更实际的理由：{@code POST /orders/{orderNo}/pay}
     * 表达的是<b>执行一个动作</b>（pay 是一个动词），
     * 而 PUT 的语义是"把资源替换成请求体描述的样子"。
     * 用 PUT 的话，语义上会变成"把这个订单替换成一个 payMethod=ALIPAY 的订单"，
     * 那更说不通。
     */
    @PostMapping("/{orderNo}/pay")
    public Result<OrderVO> pay(@PathVariable String orderNo,
                               @Valid @RequestBody PayDTO dto) {
        return Result.success(orderService.pay(orderNo, dto.getPayMethod()));
    }

    /**
     * 取消订单。
     *
     * <p>{@code POST /api/shop/orders/{orderNo}/cancel}
     *
     * <p><b>没有请求体</b> —— 取消不需要任何参数：取消哪一单在 URL 里，
     * 谁在取消在 JWT 里。<b>不需要参数就不要有请求体</b>，
     * 这不是省事，是不给客户端任何可以乱填的地方。
     * （对比一下：如果一个 DTO 里全是服务端已知的字段，
     *   那些字段被填了就是攻击面。）
     *
     * <p>⚠️ 只有「待付款」的订单能取消。已付款的要走退款流程，
     * 本项目不实现 —— 这条规则在 {@code OrderMapper.markCancelled}
     * 的 {@code WHERE status = 0} 里守着。
     *
     * <p>取消成功后会<b>把库存还回去</b>。
     */
    @PostMapping("/{orderNo}/cancel")
    public Result<OrderVO> cancel(@PathVariable String orderNo) {
        return Result.success(orderService.cancel(orderNo));
    }

    // ==========================================================================
    // 里程碑 10：订单列表 / 确认收货
    //
    // ★ 权限规则【依然一个字都不用改】，理由和上面里程碑 9 那段完全一样：
    //   /api/shop/orders/** 不在 WebMvcConfig 的排除列表里，
    //   新接口自动落在 MemberAuthInterceptor 后面。
    //   —— 「按业务实体归类到同一棵树」的收益在本里程碑第二次兑现。
    // ==========================================================================

    /**
     * 分页查「我的订单」。
     *
     * <p>{@code GET /api/shop/orders?status=1&pageNum=2&pageSize=10}
     *
     * <p>查询参数直接映射到 {@code ShopOrderQueryDTO} 的同名字段上
     * （和 {@code ShopProductController} 一样，不逐个写 {@code @RequestParam}）。
     *
     * <p><b>★ 为什么没有 {@code @Valid}？</b>
     * 和用户端商品列表、管理端列表的既有做法一致：
     * 分页参数不合法时<b>不是拒绝请求，而是钳到合法范围</b>
     * （{@code normalize()} 里把 pageSize 钳到 100 以内、页码至少为 1）。
     * <b>「搜索条件写得不对」不该是一个错误</b>——
     * 用户乱传一个 pageNum=-5，最好的回应是给他第 1 页，不是 400。
     * 而「不能为空」「格式不对」那类必须报错的东西才用 {@code @Valid}。
     *
     * <p><b>★★ 为什么 URL 里【没有】memberId？</b>
     * 因为查谁的订单由 JWT 决定。{@code ShopOrderQueryDTO} 里根本没有这个字段，
     * 所以 {@code ?memberId=999} 连绑都绑不上（Spring 会安静地忽略未知参数）。
     * <b>这不是"前端记得别传"，是"传了也没用"。</b>
     *
     * <p>⚠️ 注意 {@code status=0} 是<b>有意义的</b>（待付款），
     * 不要顺手把它当成"没传"。这是本项目里少数几个「0 是合法值」的参数之一，
     * 读它的时候专门写了一个 {@code readOrderStatus}（前端），
     * 而不是复用会把 0 当成解析失败的 {@code toPositiveInt}。
     */
    @GetMapping
    public Result<PageResult<OrderVO>> page(ShopOrderQueryDTO query) {
        return Result.success(orderService.pageMyOrders(query));
    }

    /**
     * 确认收货。
     *
     * <p>{@code POST /api/shop/orders/{orderNo}/complete}
     *
     * <p><b>没有请求体</b>，理由和 {@code cancel} 一样：
     * 确认哪一单在 URL 里，谁在确认在 JWT 里，
     * 没有参数就没有可以被乱填的地方。
     *
     * <p>⚠️ 只有「已发货」的订单能确认收货 ——
     * 这条规则在 {@code OrderMapper.markCompleted} 的 {@code WHERE status = 2} 里守着。
     *
     * <p><b>★★ 确认收货【不会】归还库存，这一点和取消订单恰好相反。</b>
     * 取消是「货还在仓里，还回去」；确认收货是「货已经在买家手里了」——
     * 再还一次就是凭空多出一件可以卖的货（超卖）。
     * 详见 {@code OrderServiceImpl.complete} 的注释。
     */
    @PostMapping("/{orderNo}/complete")
    public Result<OrderVO> complete(@PathVariable String orderNo) {
        return Result.success(orderService.complete(orderNo));
    }
}
