import request from './request'

/**
 * 订单相关的接口调用（管理端）。
 *
 * <p>baseURL 是 {@code /api}，所以下面的路径最终打到
 * {@code /api/admin/orders/**}。
 *
 * <h3>★ 这个文件是本项目第一个「管理员操作别人的数据」的入口</h3>
 *
 * <p>前面所有管理端接口（商品、分类）操作的都是<b>系统自己的数据</b> ——
 * 没有「归属」这回事。订单不一样：每一单都属于某个会员，
 * 而管理员发货时，操作的<b>是别人的订单</b>。
 *
 * <p>这件事在两个端上根本不同，值得写下来：
 *
 * <pre>
 *   mall-shop（会员端）  GET /api/shop/orders
 *     → 只能看到【自己】的订单。归属由服务端的 SQL 保证
 *       （WHERE member_id = ?），前端连 memberId 都传不了 ——
 *       DTO 里根本没有这个字段。这是「数据归属」的检查。
 *
 *   mall-web（管理端）   GET /api/admin/orders
 *     → 能看到【所有人】的订单。这不是越权，是管理端的正常职责。
 *       拦住"不该来的人"靠的是 AdminAuthInterceptor 对
 *       /api/admin/** 的一刀切拦截。这是「身份」的检查。
 * </pre>
 *
 * <p>⚠️ 两者都<b>不是</b>靠路径判断「这笔订单是不是你的」——
 * 路径只能回答「你是不是管理员」，而数据归属永远只能看数据。
 * 完整的论证写在 {@code AbstractAuthInterceptor} 的 javadoc 里。
 *
 * <p>⚠️ 还有一个明知的简化，写在这里免得读代码的人以为是漏了：
 * <b>本项目没有管理员权限模型</b> —— 任何一个登录的管理员都能发任何一单的货。
 * 真实系统里这里会有角色（客服 / 仓管 / 超管）和对应的权限点。
 */

/**
 * 分页查询所有订单。
 *
 * <p>{@code GET /api/admin/orders}
 *
 * <p>★ 筛选条件有三个，它们的分工是刻意的：
 * <pre>
 *   orderNo       精确匹配。订单号是一个【唯一标识】，
 *                 用户报单号来问的时候，要的就是那【一单】。
 *                 模糊匹配会一次返回一堆，反而要人工再挑
 *   memberKeyword 模糊匹配。搜的是会员的 username / nickname，
 *                 因为管理员记得的是「张三那单」，
 *                 不可能记得住订单号
 *   status        精确匹配。注意 null = 全部，而 0 是真实状态（待付款）
 * </pre>
 *
 * <p>⚠️ 「用一个关键词搜一切」是个很自然的偷懒想法，但不要那么做：
 * 一个输入框要同时当订单号和用户名用，服务端就得猜
 * （「这串东西长得像订单号吗」），而<b>猜错了用户看不出来</b> ——
 * 他搜「20260922」，系统按用户名去查，返回 0 条，用户只会以为没这个人。
 * <b>两个语义不同的搜索条件，就摆两个输入框。</b>
 *
 * @param {Object} params { pageNum, pageSize, orderNo, memberKeyword, status }
 * @returns {Promise<Object>} { list, total, pageNum, pageSize, pages }
 *          每行的字段比用户端多两个：{@code memberUsername} / {@code memberNickname}
 */
export function getOrderList(params) {
  return request.get('/admin/orders', { params })
}

/**
 * 发货。
 *
 * <p>{@code POST /api/admin/orders/{orderNo}/ship}
 *
 * <p><b>没有请求体</b> —— 发哪一单在 URL 里，谁在发在 JWT 里。
 * 不需要参数就不要有请求体：这不是省事，是不给客户端任何可以乱填的地方。
 * （对比下面 {@code orderNo} 这个参数：它是"要操作哪个资源"，
 * 属于 URL，不属于请求体 —— 这是 REST 最基本的划分。）
 *
 * <p>⚠️ 只有「已付款」的订单能发货。这条规则在服务端的
 * {@code markShipped} 的 {@code WHERE status = 1} 里守着，
 * 所以界面上不该显示这个按钮的时候<b>就不显示它</b>，
 * 而不是显示一个禁用的按钮。
 *
 * <p>⚠️⚠️ <b>这是一个不可逆的单向操作 —— 本项目没有「取消发货」。</b>
 * 发出去的货在现实里是要被物流拉走的，不存在"点错了收回来"。
 * 所以调用方<b>必须先做二次确认</b>，那不是走过场，
 * 它是这个操作唯一的后悔机会。
 *
 * ★★ 里程碑 18：请求体从【没有】变成【必填】——
 * 要填承运商和快递单号（「货是怎么发出去的」本来就是发货这件事的一部分，
 * 里程碑 10 只是没有记它）。
 *
 * <p>⚠️⚠️ <b>两个字段都必填，服务端刻意不接受空值。</b>
 * 理由具体得很：用户端的「查看物流」入口是按单号有没有值渲染的，
 * 允许为空会让「已发货但没单号」成为常态，而那些订单的用户
 * <b>页面上什么都没有、也不报错</b>，只会以为这个功能没做。
 * 所以服务端把「单号必填」放在接口契约上，让调用方在入口就失败。
 *
 * <p>⚠️ 长度上限 50 / 64（对齐数据库列宽）。超长会拿到 400，
 * 而不是「服务器错误」—— 别在前端再抄一遍这个长度，让服务端说。
 *
 * @param {string} orderNo
 * @param {{logisticsCompany: string, trackingNo: string}} payload 两个都必填
 * @returns {Promise<Object>} 发货后的订单（含 items 和会员名），
 *          可以直接拿去替换列表里那一行，不用重查整页
 */
export function shipOrder(orderNo, payload) {
  return request.post(`/admin/orders/${orderNo}/ship`, payload)
}

/**
 * 查一张订单的物流：承运商 + 单号 + 发货时间 + 轨迹节点。
 *
 * <p>{@code GET /api/admin/orders/{orderNo}/logistics}
 *
 * <p>★★ 一个接口返回三样东西，是因为<b>这三样正是那个弹窗要显示的全部内容</b>。
 * 拆成两个请求意味着弹窗要等两次，而中间那次失败会留下一个
 * <b>半空的弹窗</b>（上面有单号、下面是空的），用户以为「还没更新」。
 *
 * <p>★ 轨迹是<b>最新在上</b>的（服务端按 {@code trace_time DESC, id DESC} 排好），
 * 前端<b>不要再排一次</b> —— 重排就是第二个定义者。
 *
 * <p>⚠️ 未发货的订单也能查（返回空轨迹），但 {@code logisticsCompany} /
 * {@code trackingNo} / {@code shipTime} 三个字段会<b>从响应里整个消失</b>
 * （Jackson 的 non_null），判断要用假值，<b>不能写 === null</b>。
 *
 * @param {string} orderNo
 * @returns {Promise<{logisticsCompany?: string, trackingNo?: string,
 *           shipTime?: string, traces: Array}>} traces 永远是数组，不会是 null
 */
export function getOrderLogistics(orderNo) {
  return request.get(`/admin/orders/${orderNo}/logistics`)
}

/**
 * 给一张订单新增一个物流轨迹节点。
 *
 * <p>{@code POST /api/admin/orders/{orderNo}/logistics}
 *
 * <p>★★ <b>录到「已签收」（status = 4）会把订单自动推到「已完成」，
 * 而且不可撤销。</b>删掉这条节点不会把订单退回去 ——
 * 所以调用方<b>必须在提交前把这句话摆在操作者眼前</b>，
 * 而不是提交后再补救。
 *
 * <p>★ {@code traceTime} 是「这一节点【发生】的时刻」，可以填<b>过去的时刻</b>
 * （补录是这个功能的默认用法：白天忙、晚上一次性补录）。必填 ——
 * 服务端不兜底成 now，否则一个前端 bug 会把补录的节点静默记成今天，
 * 而管理员以为记的是昨天。
 *
 * @param {string} orderNo
 * @param {{status: number, description: string, traceTime: string}} payload
 *          traceTime 格式 'YYYY-MM-DD HH:mm:ss'
 * @returns {Promise<Object>} 录完之后的完整物流，可以直接拿去刷新对话框
 */
export function addLogisticsTrace(orderNo, payload) {
  return request.post(`/admin/orders/${orderNo}/logistics`, payload)
}

/**
 * 删除一个录错的轨迹节点。
 *
 * <p>{@code DELETE /api/admin/orders/{orderNo}/logistics/{traceId}}
 *
 * <p>★ 路径里带 orderNo 不是冗余：服务端要用它把 order_id 查出来，
 * 再用 {@code WHERE id = ? AND order_id = ?} 删 ——
 * 这样传错 id 也删不到别人的节点。
 *
 * <p>⚠️ <b>删除不回退订单状态。</b>删掉一条「已签收」，
 * 订单仍然是「已完成」。这是决定不是 bug，别去「修」它。
 *
 * <p>★ 轨迹只允许新增和删除，<b>没有修改</b>：一条被改过的轨迹不是轨迹。
 * 改错的做法是删了重录。
 *
 * @returns {Promise<Object>} 删完之后的完整物流，可以直接拿去刷新对话框
 */
export function deleteLogisticsTrace(orderNo, traceId) {
  return request.delete(`/admin/orders/${orderNo}/logistics/${traceId}`)
}
