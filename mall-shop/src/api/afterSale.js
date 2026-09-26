import request from './request'

/**
 * 售后接口（用户端）。
 *
 * <p>路径都在 {@code /api/shop/after-sales/**} 下面，<b>全部需要登录</b>。
 *
 * <h3>★★ 这四个函数的参数表里，有四样东西【永远不会】出现</h3>
 *
 * <pre>
 *   ✗ memberId      —— 身份从 JWT 里取。
 *                      这是安全边界，不是"少写一个字段"（见下面那段）。
 *   ✗ refundAmount  —— ★ 退款金额由【服务端】算。
 *   ✗ 任何价格 / 小计 —— 退多少取决于那张售后单对应的
 *                      order_item.subtotal（成交快照）和订单的运费，
 *                      两者服务端都知道，客户端一个数字都不该提供。
 *   ✗ status        —— 状态由服务端的状态机推进，
 *                      而且【走哪条边由 URL 决定】，不由参数决定。
 * </pre>
 *
 * <p>★ 这条边界和 {@code api/order.js} 开头那条是同一条：
 * <b>凡是「服务端有权威来源」的数据，一律不接受客户端提供。</b>
 * 客户端能提供的东西，客户端就能伪造；而<b>金额能被伪造的退款接口，
 * 是一个能把 99 元退成 9900 元的接口。</b>
 *
 * <h3>★★ 为什么这些函数的参数表里【没有】memberId，这件事值得单独说</h3>
 *
 * <p>因为「这条售后单是不是你的」如果靠前端传的 memberId 判断，
 * 那它就等于「客户端自己声明它是谁」。所以后端的
 * {@code ShopAfterSaleController} 从 JWT 里拿会员 id，
 * 然后把它写进查询/更新的 {@code WHERE} 里
 * （{@code AND a.member_id = #{memberId}}）。
 *
 * <p>由此推出一条对调用方的要求：<b>不要试图在 URL 或 body 里补一个 memberId
 * 来"帮后端一把"</b> —— 后端根本不读它，而它会让人以为这条链路是可以被客户端指定身份的。
 * <b>一个存在于参数表里的字段，就是一个看起来像入口的洞。</b>
 *
 * <h3>★ 三个专用业务码：1012 / 1013 / 1014 的处理方式和其它错误【不一样】</h3>
 *
 * <pre>
 *   1012 AFTER_SALE_EXISTS   这条明细已经有一张进行中的售后单
 *   1013 ORDER_ITEM_REFUNDED 这条明细已经退过款了
 *   1014 AFTER_SALE_EXPIRED  超过售后申请期限（确认收货后 N 天）
 * </pre>
 *
 * <p>★ 这三个码的共同点是：<b>它们不是"操作失败"，而是"这一行现在是什么状态"。</b>
 * 所以调用方收到它们时<b>不该弹一个错误提示</b>，而应该
 * <b>把那一行标记成对应的状态、把入口去掉，然后重查列表</b>。
 *
 * <p>为什么这个区别重要：用户看到「申请失败：这条商品已经退款了」会以为自己
 * 做错了什么，而实际上他只是<b>面对了一行不再有入口的明细</b>。
 * 弹错和状态标记解决的是不同的问题 —— 前者在说"你操作失败了"，
 * 后者在说"这一行已经不需要操作了"。
 *
 * <p>⚠️ 但这只是<b>体验优化</b>。前端在按钮上过滤掉这些行，
 * 挡不住「用户开两个标签页、或者直接调接口」——
 * <b>真正的不变量在服务端</b>（{@code uk_order_item_active} 唯一索引）。
 * 这两层的分工在后端 {@code AfterSaleService} 的注释里有完整论证。
 */

/**
 * 申请售后。
 *
 * <p>{@code POST /api/shop/after-sales}
 * <p>需要登录。只能对自己的订单、自己的明细申请。
 *
 * <h3>★★ 一次提交可以建【N 张】售后单 —— 这是"整单退"的实现方式</h3>
 *
 * <p>{@code orderItemIds} 是一个数组，服务端<b>在一个事务里</b>
 * 为每一条明细建一张售后单，<b>全成或全败</b>。
 *
 * <p>★ 为什么不是"一张售后单管多行"？因为那样一来
 * 「这一行退没退」就有两个定义者（售后单自己的状态 + 主单的状态），
 * 两者必然分岔。所以模型上坚持<b>一张售后单只对一条明细</b>，
 * 而「整单退」是<b>体验</b>问题 —— 用"一次提交多张单"来解决，
 * 而不是改动数据模型。
 *
 * <p>⚠️ 因此超时的可能性和选择的条数有关：一次勾 10 条就是 10 张单。
 * 这是正常的，不需要前端做分批。
 *
 * <h3>★ 类型（type）能不能选，不由这个参数决定</h3>
 *
 * <p>{@code type} 传的是 <b>1=仅退款 / 2=退货退款</b>，但<b>不是所有订单都两种都能选</b>：
 * <pre>
 *   订单还没发货（status = 1） → 货在仓库里 → 只能"仅退款"
 *   订单已发货 / 已完成        → 货在买家手里 → 只能"退货退款"
 * </pre>
 * <p>界面上应该<b>只把能选的那种摆出来</b>（{@code AFTER_SALE_TYPE_CHOICES}
 * 按订单状态过滤），而<b>最终把关的是服务端</b> ——
 * 传一个不匹配的组合会拿到 1002。
 * <b>前端过滤只是体验优化，让用户看到他能选的，而不是让他选了再被拒。</b>
 *
 * @param {object}   payload
 * @param {string}   payload.orderNo       订单号（★ 用订单号不用订单 id：
 *                                         它是有意义的、人和 URL 都在用的那个标识）
 * @param {number[]} payload.orderItemIds  ★ 一个数组。整单退就传全部明细的 id
 * @param {number}   payload.type          1=仅退款 2=退货退款，见 {@code afterSaleStatus.js}
 * @param {number}   payload.reason        原因码，见 {@code AFTER_SALE_REASONS}
 * @param {string}   [payload.description] 补充说明，可选，最长 255 字
 * @returns {Promise<object[]>} 新建的售后单列表（和 {@code orderItemIds} 一一对应）
 *
 * @throws {1012} 其中某条明细已有进行中的售后单 —— ★ 标记那一行，不要弹错
 * @throws {1013} 其中某条明细已经退过款 —— ★ 同上
 * @throws {1014} 已超过售后申请期限 —— ★ 同上
 * @throws {1002} 订单状态不支持申请售后 / 类型和订单状态不匹配
 * @throws {1003} 订单不存在，或不是你的（后端<b>刻意</b>合并成同一个码，
 *                不泄露"这个订单号是存在的"）
 */
export function applyAfterSale(payload) {
  return request.post('/shop/after-sales', {
    orderNo: payload.orderNo,
    orderItemIds: payload.orderItemIds,
    type: payload.type,
    reason: payload.reason,
    description: payload.description || '',
  })
}

/**
 * 分页查「我的售后」。
 *
 * <p>{@code GET /api/shop/after-sales}
 * <p>需要登录。<b>只返回自己的售后单</b>。
 *
 * <p>⚠️ 和 {@code listMyOrders} 一样，这里<b>没有 memberId</b> ——
 * 查谁的由 JWT 决定。
 *
 * <p>★ {@code status} 的语义和订单列表完全一样，而且<b>同一个坑还在这里</b>：
 * <pre>
 *   不传        = 全部
 *   0           = 一个【真实】的状态（待审核）
 * </pre>
 * <p>所以<b>不能用 0 表示"全部"</b>，也不能用 -1 之类的哨兵值
 * （哨兵值迟早会和真实取值撞车）。读 URL 上那个 status 时
 * 要去 {@code utils/query.js} 找那个"允许 0"的解析函数 ——
 * 用 {@code toPositiveInt} 的话，「待审核」这个筛选永远选不中，
 * <b>而且不报错</b>。
 *
 * <p>⚠️ 「全部」在下拉框里的 value 是 {@code null}
 * （见 {@code AFTER_SALE_STATUS_OPTIONS} 为什么不用 -1）。
 * {@code null} 传进 axios 的 params 会变成<b>不发这个参数</b>，正好就是"全部"。
 *
 * @param {object} [params]
 * @param {number} [params.status]  状态码，<b>不传表示全部</b>
 * @param {number} [params.pageNum] 从 1 开始
 * @param {number} [params.pageSize] 后端上限 100，超了会被钳到 100
 * @returns {Promise<{list: object[], total: number, pageNum: number,
 *                    pageSize: number, pages: number}>}
 */
export function listMyAfterSales(params) {
  return request.get('/shop/after-sales', { params })
}

/**
 * 撤销售后申请。
 *
 * <p>{@code POST /api/shop/after-sales/{afterSaleNo}/cancel}
 * <p><b>没有请求体</b> —— 撤销哪一张在 URL 里，谁在撤销在 JWT 里。
 * 不需要参数就不要有请求体，这不是省事，是不给客户端任何可以乱填的地方。
 *
 * <p>⚠️ <b>只有「待审核」的售后单能撤销。</b>一旦管理员同意过
 * （状态到了「待买家寄回」或更后面），撤销的入口就没有了 ——
 * 那时候货可能已经在路上了，得走「拒绝」那条路（由管理员操作）。
 *
 * <p>★ 撤销是<b>唯一一条不碰库存的关闭方式</b>：它只是把这张申请作废，
 * 商品从头到尾都还在买家手里（或者仓库里），没有任何东西需要归还。
 * 这一点和"同意退货"形成对照 —— 后者要等货真的回到仓库才还库存。
 * 前端<b>不需要也不该</b>关心库存，这里提一句只是为了让读代码的人
 * 不要在撤销成功后去找一个"库存加回来了"的效果。
 *
 * <p>★ 撤销成功后，那一条明细<b>可以重新申请</b>
 * （服务端的 {@code uk_order_item_active} 令牌被放开了）。
 * 所以界面上撤销之后要去掉"处理中"的标记，让入口重新出现 ——
 * 否则用户会以为撤销完就再也申请不了了。
 *
 * @param {string} afterSaleNo
 * @returns {Promise<object>} 撤销后的售后单
 * @throws {1002} 当前状态不允许撤销（例如已经被同意了）
 * @throws {1003} 售后单不存在，或不是你的
 */
export function cancelAfterSale(afterSaleNo) {
  return request.post(`/shop/after-sales/${afterSaleNo}/cancel`)
}

/**
 * 填写退货的寄回物流信息。
 *
 * <p>{@code POST /api/shop/after-sales/{afterSaleNo}/return}
 * <p>需要登录。只能填<b>自己的</b>售后单。
 *
 * <p>⚠️ <b>只有「待买家寄回」能填</b>（管理员同意退货退款之后、卖家确认收到之前）。
 * 这个状态是<b>唯一一个"只有买家能动"的状态</b> ——
 * 整条状态机的分工就是靠它划出来的：
 * <pre>
 *   待买家寄回 → 只有买家能动（就是本函数）
 *   待卖家收货 → 只有管理员能动（确认收到）
 * </pre>
 * <p>★ 为什么这两步不能合成一步：合成之后，管理员能在用户还没寄回时
 * 就点"确认收到"，于是<b>钱退了、货还在买家手里</b>，而且没有任何一层会报错。
 * <b>两个状态的全部意义就是让"谁该动手"这件事在结构上说不含糊。</b>
 *
 * <p>⚠️ 填完只是把状态推进到「待卖家收货」，<b>此时退款还没发生、库存也还没归还</b>。
 * 那些都发生在管理员点「确认收到」的时候 —
 * 所以界面上这一步的文案应该说"提交寄回信息"，不要说"已退款"。
 *
 * @param {string} afterSaleNo
 * @param {string} returnCompany  快递公司（★ 自由文本，不是码表 ——
 *                                快递公司太多了，做成码表就得一直追着加）
 * @param {string} returnTracking 快递单号
 * @returns {Promise<object>} 更新后的售后单
 * @throws {1002} 当前状态不是「待买家寄回」
 * @throws {1003} 售后单不存在，或不是你的
 */
export function submitReturnInfo(afterSaleNo, returnCompany, returnTracking) {
  return request.post(`/shop/after-sales/${afterSaleNo}/return`, {
    returnCompany,
    returnTracking,
  })
}
