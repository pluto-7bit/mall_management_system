import request from './request'

/**
 * 售后接口（管理端）。
 *
 * <p>路径都在 {@code /api/admin/after-sales/**} 下面。
 * 整个 {@code /api/admin/**} 被 {@code AdminAuthInterceptor} 拦着，
 * 所以这四个函数<b>不需要也不该</b>自己带 token 之外的任何鉴权参数。
 *
 * <h3>★★ 这四个函数里【没有】member_id，这不是遗漏</h3>
 *
 * <p>管理端操作的是<b>别人的</b>售后单 —— 它根本不知道也不该知道
 * 这张单属于哪个会员。所以后端的更新语句里<b>没有</b>
 * {@code AND member_id = ...} 这一条：
 *
 * <pre>
 *   用户端的 T5（填寄回）：WHERE id = ? AND member_id = ? AND status = 1
 *                          ↑ 必须有 member_id —— 那是「只能动自己的」这条边界
 *   管理端的 T6（确认收到）：WHERE id = ? AND status = 2
 *                          ↑ 没有 member_id —— 管理员动的是别人的单，
 *                            身份这一层由拦截器保证（整个 /api/admin/** 都要登录）
 * </pre>
 *
 * <p>★ 这个对照值得记住，因为它说明<b>同一张表上的同类操作，
 * 两端的 WHERE 本来就该长得不一样</b>。看到一边有 member_id、
 * 另一边没有时，正确的反应是去核对「这一端是不是本来就该动别人的单」，
 * 而不是「两边不一致，补一个上去」—— 补上去的后果是
 * <b>管理员永远确认不了任何一张退货</b>（因为那些单都不是他的）。
 *
 * <h3>★ 「同意」这个动作在两个端是【一件事】，但后台看到的形态不同</h3>
 *
 * <p>{@code approve} 一个接口管两种类型，因为它就是管理员点的那一下 ——
 * 但<b>这一下对两种类型做的事完全不同</b>：
 * <pre>
 *   仅退款   →  同意即退款：钱退了、库存还了、售后单直接到「退款完成」
 *   退货退款 →  同意只是同意：售后单到「待买家寄回」，
 *              钱还在，库存也还没还，要等货真的回到仓库
 * </pre>
 * <p>★ 所以界面上这两个类型的按钮文字<b>不该一样</b>：
 * 仅退款 → 「同意并退款」，退货退款 → 「同意退货」。
 * 都叫「同意」的话，管理员分不清自己刚刚做了什么 ——
 * 而这两种"做了什么"在钱和货上的差别是根本性的。
 */

/**
 * 分页查全部售后单。
 *
 * <p>{@code GET /api/admin/after-sales}
 *
 * <p>★ 四个筛选条件，各有各的匹配方式：
 * <pre>
 *   status        精确匹配（等值）。null = 全部
 *   afterSaleNo   精确匹配。★ 客服拿一个单号来问，要能一步查到 ——
 *                 而且用等值不是 LIKE：单号是精确标识符，
 *                 用模糊匹配会让「AS2026...1」同时命中「AS2026...12」
 *   memberKeyword 模糊匹配会员名。★ 唯一一个模糊的 ——
 *                 因为管理员记得的是「姓张的那个」，不是全名
 *   type          精确匹配（1=仅退款 2=退货退款）
 * </pre>
 *
 * <p>⚠️ {@code status} 和 {@code ORDER_STATUS_OPTIONS} 一样：
 * <b>「全部」用 {@code null} 表示，不要用 0 或 -1</b> ——
 * 0 是一个真实状态（待审核）。
 *
 * @param {object} [params]
 * @param {number} [params.status]         状态码，不传表示全部
 * @param {string} [params.afterSaleNo]    精确单号
 * @param {string} [params.memberKeyword]  会员名模糊搜索
 * @param {number} [params.type]           1=仅退款 2=退货退款
 * @param {number} [params.pageNum]        从 1 开始
 * @param {number} [params.pageSize]       后端上限 100，超了会被钳到 100
 * @returns {Promise<{list: object[], total: number, pageNum: number,
 *                    pageSize: number, pages: number}>}
 */
export function listAfterSales(params) {
  return request.get('/admin/after-sales', { params })
}

/**
 * 同意售后申请。
 *
 * <p>{@code POST /api/admin/after-sales/{afterSaleNo}/approve}
 *
 * <p><b>没有请求体</b> —— 同意哪一张在 URL 里，谁在同意由拦截器保证。
 * 同意不需要解释。（拒绝<b>需要</b>解释，见 {@code rejectAfterSale} ——
 * 参数只在「需要说点什么」的时候存在，这不是不对称。）
 *
 * <h3>★★ 这一个接口对两种类型做的事完全不同，而且不可逆</h3>
 *
 * <p><b>仅退款</b>（{@code type = 1}）：<b>点下去钱就退了</b>，
 * 同时库存被归还、订单可能被推到「已退款」终态。
 * 调用方<b>必须先做二次确认</b>，而且文案要说清「同意后将立即退款」。
 *
 * <p><b>退货退款</b>（{@code type = 2}）：只是同意，售后单进入「待买家寄回」。
 * 此时<b>一分钱没退、一件库存没还</b>，要等买家填单号、
 * 管理员再点「确认收到」才发生。所以这一步<b>不需要二次确认</b>，
 * 它本身不是不可逆的（拒绝和确认收货才是）。
 *
 * <p>★ 这两段差异<b>不该由前端判断</b> —— 前端只管显示哪个按钮、
 * 要不要二次确认；真正走哪条边由服务端按 {@code type} 决定。
 * 前端按 {@code type} 分支只是为了把话说对，
 * <b>不是因为它有权决定走哪条路</b>。
 *
 * @param {string} afterSaleNo
 * @returns {Promise<object>} 同意后的售后单
 * @throws {1002} 当前状态不允许同意（例如已经处理过了）。
 *                ★ 消息里会带上当前状态的中文 —— 因为这是管理员会正常撞到的
 *                「另一个人已经处理了」，不是并发闸门那种"正常路径不该撞上"的错
 * @throws {1003} 售后单不存在
 */
export function approveAfterSale(afterSaleNo) {
  return request.post(`/admin/after-sales/${afterSaleNo}/approve`)
}

/**
 * 拒绝售后申请。
 *
 * <p>{@code POST /api/admin/after-sales/{afterSaleNo}/reject}
 *
 * <p>⚠️ <b>拒绝理由是必填的</b>（后端是 {@code @NotBlank}）。
 * 所以调用方必须先弹一个让管理员填理由的对话框，
 * <b>不能</b>点一下「拒绝」就直接提交 —— 那样用户会收到一条
 * 后端的参数校验错误，而管理员会以为按钮坏了。
 *
 * <p>★ 为什么理由是必填的：拒绝是<b>唯一一个会让用户不满、
 * 且他无从申诉</b>的动作。一条没有理由的「已拒绝」，
 * 用户只能再申请一次（然后大概率再被拒）或者打客服电话 ——
 * 而客服看着这条记录也只能说「我也不知道为什么」。
 * <b>必填的理由字段是把「决策」逼成「可以说清楚的决策」。</b>
 *
 * <p>⚠️ 可拒绝的状态是<b>两个</b>：待审核、以及<b>待买家寄回</b>
 * （管理员同意之后反悔 —— 比如发现买家填的单号对不上）。
 * 但<b>不能拒绝「待卖家收货」</b>：那时候货已经在路上了，
 * 唯一正确的动作是「确认收到」。
 *
 * @param {string} afterSaleNo
 * @param {string} rejectReason 拒绝理由，必填，最长 255 字
 * @returns {Promise<object>} 拒绝后的售后单
 * @throws {1002} 当前状态不允许拒绝（例如「待卖家收货」——货在途）
 * @throws {1003} 售后单不存在
 */
export function rejectAfterSale(afterSaleNo, rejectReason) {
  return request.post(`/admin/after-sales/${afterSaleNo}/reject`, { rejectReason })
}

/**
 * 确认收到买家的退货 → 退款 + 归还库存。
 *
 * <p>{@code POST /api/admin/after-sales/{afterSaleNo}/receive}
 *
 * <p><b>没有请求体</b> —— 确认哪一张在 URL 里。
 *
 * <h3>★★ 这是整条售后链路上【最不可逆】的一步</h3>
 *
 * <p>点下去会发生：状态到「退款完成」、<b>退款金额写入</b>、
 * <b>库存归还</b>、订单可能被推到「已退款」终态。
 * 而且它<b>没有反向操作</b>（本项目不实现「撤销确认收货」）。
 *
 * <p>所以调用方<b>必须</b>做二次确认，且文案要说清「确认后将立即退款」。
 *
 * <p>⚠️ <b>只有「待卖家收货」能确认</b>（{@code WHERE status = 2}）。
 * 这条规则是「待买家寄回」和「待卖家收货」两个状态分家的全部意义 ——
 * 合成一个状态的话，管理员能在用户还没寄回时就点这一下，
 * 于是<b>钱退了、货还在买家手里</b>，而且没有任何一层会报错。
 *
 * <p>★ 库存归还的时机是<b>这一步</b>，不是「同意」那一步。
 * 前端不用管库存，提一句只是为了让读代码的人知道
 * 为什么两笔看起来一样的「同意退货」在库存上的效果不同：
 * 一笔（仅退款）当场还，一笔（退货退款）要走到这里才还。
 *
 * @param {string} afterSaleNo
 * @returns {Promise<object>} 退款后的售后单
 * @throws {1002} 当前状态不是「待卖家收货」
 * @throws {1003} 售后单不存在
 */
export function receiveAfterSale(afterSaleNo) {
  return request.post(`/admin/after-sales/${afterSaleNo}/receive`)
}
