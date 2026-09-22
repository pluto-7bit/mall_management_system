import request from './request'

/**
 * 订单接口。
 *
 * <p>路径都在 {@code /api/shop/orders/**} 下面，<b>全部需要登录</b>。
 *
 * <h3>★ 这两个函数的参数，值得逐字看一遍</h3>
 *
 * <p>下单是用户能做的<b>唯一一个会扣别人东西</b>的操作
 * （扣库存），所以「前端能往请求里塞什么」这件事格外重要：
 * <pre>
 *   createFromCart 的 body:  {productIds, addressId, idempotencyKey, remark}
 *   createByBuyNow 的 body:  {productId, quantity, addressId, idempotencyKey, remark}
 *
 *   ✗ 没有 totalAmount   —— 金额由服务端按商品现价算
 *   ✗ 没有 price         —— 单价同理
 *   ✗ 没有 receiver/phone/address —— 收货信息由 addressId 间接指定
 *   ✗ 没有 memberId      —— 身份从 JWT 里取
 *   ✗ 没有 orderNo       —— 服务端生成
 *   ✗ 没有 status        —— 订单一建立就是「待支付」
 * </pre>
 *
 * <p>这不是"少写几个字段"，而是<b>一条明确的边界</b>：
 * <b>凡是「服务端有权威来源」的数据，一律不接受客户端提供。</b>
 * 购物车结算的 {@code productIds} 里连数量都没有 ——
 * 数量从 Redis 购物车里读，因为那才是真相所在。
 *
 * <p>这条规则的意义在于：客户端能提供的东西，客户端就能伪造。
 * 金额能被伪造的商城，是一个可以一块钱买 iPhone 的商城。
 */

/**
 * 购物车结算下单。
 *
 * <p>{@code POST /api/shop/orders}
 *
 * <p>⚠️ <b>{@code productIds} 是「用户勾选了哪几种商品」，
 * 不是「要买什么」的完整描述。</b>每种买几件由服务端去
 * Redis 购物车里读 —— 所以调用方<b>不需要也不应该</b>传数量。
 *
 * <p>下单成功且事务提交之后，后端会把这几种商品从购物车里移除。
 *
 * @param {number[]} productIds 勾选的商品 id
 * @param {number}   addressId  收货地址 id
 * @param {string}   idempotencyKey 见 {@code utils/checkoutIntent.js} ——
 *                 <b>不要在这个函数里现生成</b>，要从外面传进来
 * @param {string}   [remark]   备注，可以为空
 * @returns {Promise<object>} 新订单，或<b>幂等命中时</b>之前建立的那笔订单
 */
export function createOrderFromCart(productIds, addressId, idempotencyKey, remark) {
  return request.post('/shop/orders', {
    productIds,
    addressId,
    idempotencyKey,
    remark: remark || '',
  })
}

/**
 * 立即购买（不经过购物车）。
 *
 * <p>{@code POST /api/shop/orders/buy-now}
 *
 * <p>⚠️ 这里数量必须由前端传 —— 和购物车结算相反。
 * 原因是<b>这条路上服务端没有别的真相来源</b>：
 * 用户从商品详情页直接买 3 件，这 3 件不存在于任何服务器状态里，
 * 是用户刚刚在数量选择器上决定的。
 *
 * <p>★ 那这不就违反了上面「不接受客户端提供数据」的规则吗？
 * 没有。规则说的是<b>「服务端有权威来源的数据」</b>。
 * 数量在购物车结算时有权威来源（Redis 购物车），所以不收；
 * 在立即购买时没有，所以只能收 ——
 * 但服务端<b>照样要校验</b>：数量必须是 1~999（{@code BuyNowDTO} 的
 * {@code @Min/@Max}），而且真正下单时会重新查库存。
 * <b>客户端提供 ≠ 客户端说了算。</b>
 *
 * <p>⚠️ 而且这条路径<b>完全不碰购物车</b>：既不加进去，也不清空。
 *
 * @param {number} productId
 * @param {number} quantity  1~999
 * @param {number} addressId
 * @param {string} idempotencyKey 见 {@code utils/checkoutIntent.js}
 * @param {string} [remark]
 */
export function createOrderByBuyNow(productId, quantity, addressId, idempotencyKey, remark) {
  return request.post('/shop/orders/buy-now', {
    productId,
    quantity,
    addressId,
    idempotencyKey,
    remark: remark || '',
  })
}

// ==========================================================================
// 里程碑 9：查详情 / 支付 / 取消
//
// ★ 这三个函数的参数表里都有一个共同点：**只有一个**业务参数。
//   订单号在路径里、身份在 JWT 里、金额和状态在数据库里 ——
//   客户端能提供的只剩「要操作哪一单」（路径）和「用哪种方式付」（body）。
//
//   和前两个创建函数对比一下：
//     createFromCart 要传 productIds / addressId / idempotencyKey / remark
//     pay            只要传 payMethod
//   差别不是"支付这个功能简单"，而是**支付这件事服务端什么都知道，
//   只有「用户偏好哪种支付方式」只存在于用户的点击里。**
//   DTO 里该有哪些字段，取决于「这件事服务端自己知不知道」。
// ==========================================================================

/**
 * 查订单详情。
 *
 * <p>{@code GET /api/shop/orders/{orderNo}}
 * <p>需要登录。只能查<b>自己的</b>订单 —— 查别人的会返回 1003（订单不存在），
 * 后端故意用同一个错误码，不泄露「这个订单号是存在的」。
 *
 * <p>收银台页（{@code Pay.vue}）打开时调它，靠返回的 {@code status}
 * 决定该显示「待付款」的表单还是「已付款」的结果。
 *
 * <p>★ 返回的 {@code payDeadline} <b>只有待付款的订单才有</b>
 * （后端配了 {@code non_null}，null 字段在 JSON 里直接消失）。
 * 它是给倒计时用的，<b>纯展示</b> —— 能不能付款始终由服务端说了算。
 *
 * @param {string} orderNo
 * @returns {Promise<object>} 订单详情（含 items 明细）
 */
export function getOrder(orderNo) {
  return request.get(`/shop/orders/${orderNo}`)
}

/**
 * 支付订单（模拟）。
 *
 * <p>{@code POST /api/shop/orders/{orderNo}/pay}
 * <p>需要登录。
 *
 * <p>⚠️ <b>只有「待付款」且未超过支付时限的订单能支付。</b>
 * 已过时限的订单即使定时任务还没扫到，也会被拒绝（1002）——
 * 因为「超时」这条规则写在服务端的 SQL 里，不是靠定时任务执行的。
 *
 * <p>★ 为什么路径里要带 {@code /pay} 而不是 {@code POST /orders/pay}
 * 把订单号放请求体？因为「订单号」是<b>要操作哪个资源</b>（属于 URL），
 * 「支付方式」才是<b>操作的内容</b>（属于请求体）。
 * 这是 REST 最基本的划分。
 *
 * <p>⚠️ 重复支付会得到 1002 而不是静默成功。这是刻意的 ——
 * 见后端 {@code OrderServiceImpl.pay} 里关于「状态本身就是幂等键」那段。
 * 调用方应该在按钮上加 {@code :loading} 作为第一道防线。
 *
 * @param {string} orderNo
 * @param {string} payMethod 见 {@code utils/orderStatus.js} 的 {@code PAY_METHODS}
 * @returns {Promise<object>} 支付后的订单（前端可以直接用它刷新页面状态，不用再查一次）
 */
export function payOrder(orderNo, payMethod) {
  return request.post(`/shop/orders/${orderNo}/pay`, { payMethod })
}

/**
 * 取消订单。
 *
 * <p>{@code POST /api/shop/orders/{orderNo}/cancel}
 * <p>需要登录。
 *
 * <p><b>没有请求体</b> —— 取消不需要任何参数：取消哪一单在 URL 里，
 * 谁在取消在 JWT 里。<b>不需要参数就不要有请求体</b>，
 * 这不是省事，是不给客户端任何可以乱填的地方。
 *
 * <p>⚠️ 只有「待付款」的订单能取消，<b>已付款的不行</b>
 * （要走退款流程，本项目不实现）。所以界面上已付款的订单
 * 应该<b>不显示</b>取消按钮，而不是显示一个禁用的按钮。
 *
 * <p>取消成功后服务端会把库存还回去 —— 这一点前端不用管，
 * 也不该去"帮忙"算库存。
 *
 * @param {string} orderNo
 * @returns {Promise<object>} 取消后的订单
 */
export function cancelOrder(orderNo) {
  return request.post(`/shop/orders/${orderNo}/cancel`)
}

// ==========================================================================
// 里程碑 10：我的订单列表 / 确认收货
//
// ★ 这两个函数的参数表里同样什么都没有（除了「查第几页什么状态」）：
//   查谁的订单由 JWT 决定，客户端连提都提不了 ——
//   后端的 ShopOrderQueryDTO 里【没有】memberId 字段，
//   所以这个参数在两边的代码里都不存在。
// ==========================================================================

/**
 * 分页查「我的订单」。
 *
 * <p>{@code GET /api/shop/orders}
 * <p>需要登录。<b>只返回自己的订单</b>。
 *
 * <p>⚠️ 注意这里只有两个参数，<b>没有 memberId</b> ——
 * 见上面那段。前端唯一能控制的是「筛哪些状态、看第几页」。
 *
 * <p>★ {@code status} 的语义要特别小心：<b>不传 = 全部</b>，
 * 而 <b>{@code 0} 是一个真实的状态（待付款）</b>。
 * 「全部」不能用一个哨兵值（比如 -1）来表示，
 * 因为哨兵值迟早会和真实取值撞车。
 * 读 URL 上那个 status 时请用 {@code utils/query.js} 的
 * {@code readOrderStatus}，<b>不要用 {@code toPositiveInt}</b> ——
 * 它会把 0 当成解析失败，于是「待付款」这个 Tab 永远选不中。
 *
 * <p>⚠️ 返回的每一行都带 {@code items} 明细（后端用一次批量查询装的，
 * 不是每单查一次）。明细里的 {@code orderId} 可以拿来核对
 * 「这条明细属于哪一单」—— 不过列表本来就是按订单分好组的，
 * 正常情况下用不到它。
 *
 * @param {object} [params]
 * @param {number} [params.status]  状态码，<b>不传表示全部</b>
 * @param {number} [params.pageNum] 从 1 开始
 * @param {number} [params.pageSize] 后端上限 100，超了会被钳到 100
 * @returns {Promise<{list: object[], total: number, pageNum: number,
 *                    pageSize: number, pages: number}>}
 */
export function listMyOrders(params) {
  return request.get('/shop/orders', { params })
}

/**
 * 确认收货。
 *
 * <p>{@code POST /api/shop/orders/{orderNo}/complete}
 * <p>需要登录。只能确认<b>自己的</b>订单。
 *
 * <p><b>没有请求体</b> —— 确认哪一单在 URL 里，谁在确认在 JWT 里。
 * 不需要参数就不要有请求体，这不是省事，是不给客户端任何可以乱填的地方。
 *
 * <p>⚠️ 只有「已发货」的订单能确认收货。这条规则在服务端的
 * {@code WHERE status = 2} 里守着，所以界面上不该显示这个按钮的时候
 * <b>就不显示它</b>（而不是显示一个禁用按钮 —— 见 Pay.vue 里
 * 「已付款为什么不显示取消按钮」那段，两种处理的方向是相反的）。
 *
 * <p>⚠️ <b>这个操作不可逆</b> —— 本项目没有「撤销确认收货」。
 * 所以调用方必须先做二次确认。
 *
 * <p>★★ 顺便说清一件事，因为它很容易被误解：
 * <b>确认收货【不会】归还库存</b>（这一点和取消订单恰好相反）。
 * 取消是「货还在仓里，还回去」；确认收货是「货已经在买家手里了」，
 * 再还一次就是凭空多出一件可以卖的货。
 * 这是服务端的事，前端不用管也不该去算 —— 提这一句只是为了
 * 让读到这里的人不要觉得「少做了点什么」。
 *
 * @param {string} orderNo
 * @returns {Promise<object>} 确认后的订单（含 items），
 *          可以直接拿去替换列表里那一行，不用重查列表
 */
export function completeOrder(orderNo) {
  return request.post(`/shop/orders/${orderNo}/complete`)
}
