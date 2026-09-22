import request from './request'

/**
 * 购物车接口。
 *
 * <p>路径都在 {@code /api/shop/cart/**} 下面，<b>全部需要登录</b> ——
 * 后端没有把它们排除出 {@code MemberAuthInterceptor} 的拦截范围。
 *
 * <p>⚠️ 这一点和 {@code product.js} 正好相反，值得对照着看：
 * <pre>
 *   /api/shop/products/**  →  排除，游客可访问（只读）
 *   /api/shop/cart/**      →  不排除，必须登录（会改数据）
 * </pre>
 * 「读可以匿名，写必须登录」这条规则在代码里的落地形态，
 * 就是这一行行的路径配置。所以这些函数<b>调用前必须确认已登录</b>。
 *
 * <p>不过实际上即使不确认也不会出大事：真没登录的话后端返回 401，
 * {@code request.js} 的拦截器会清 token 并跳登录页。
 * 但在界面层先拦一道体验好得多 —— 用户点「加入购物车」
 * 直接被告知「请先登录」并跳到登录页，比先发一个注定失败的请求再跳要干脆。
 */

/**
 * 查询完整购物车。
 *
 * <p>返回 {@code {items: [...], totalQuantity, totalAmount}}。
 * <b>购物车为空时也是 200 + 一个结构完整的空对象，不是报错</b>，
 * 所以调用方拿到之后不用判空，直接渲染 items 就行。
 */
export function getCart() {
  return request.get('/shop/cart')
}

/**
 * 查购物车商品<b>总件数</b>，给顶部角标用。
 *
 * <p>返回一个数字（比如 5），不是对象。
 *
 * <p>为什么不复用 {@link getCart} 然后 {@code items.length}？
 * 两个原因：
 * <ol>
 *   <li>{@code items.length} 是<b>条目数</b>，不是件数。
 *       加 3 瓶水 + 2 包纸应该是 5，不是 2</li>
 *   <li>完整购物车要 join 商品表拿名称/价格/库存，
 *       而角标只需要一个数字。在商品详情页为了显示「3」
 *       去拉一大坨数据很浪费</li>
 * </ol>
 */
export function getCartCount() {
  return request.get('/shop/cart/count')
}

/**
 * 加入购物车。
 *
 * <p>⚠️ {@code quantity} 是<b>增量</b>，不是目标值！
 * 车里已有 2 件时调 `addItem(id, 3)` 会变成 5 件。
 * 「改成 3 件」要用 {@link updateCartItem}。
 *
 * <p>这个区别在界面上对应两种不同的控件：
 * <pre>
 *   详情页的「加入购物车」按钮 → addItem（累加）
 *   购物车页的数量输入框       → updateCartItem（设值）
 * </pre>
 * 用错了的后果是：用户在购物车里把数量从 2 改成 3，
 * 结果变成了 5。「多出来的数量从哪来的」这种 bug 很难查，
 * 因为看起来每一步都"正常"。
 *
 * @param {number} productId 商品 id
 * @param {number} quantity  要【增加】的数量，1~99
 */
export function addCartItem(productId, quantity = 1) {
  return request.post('/shop/cart/items', { productId, quantity })
}

/**
 * 修改购物车里某个商品的数量（<b>设成</b>指定值，不是累加）。
 *
 * @param {number} productId 商品 id
 * @param {number} quantity  目标数量，1~99。
 *                           想变成 0 请调 {@link removeCartItem} ——
 *                           后端会拒绝 quantity 为 0 的请求
 */
export function updateCartItem(productId, quantity) {
  return request.put(`/shop/cart/items/${productId}`, { quantity })
}

/**
 * 从购物车移除一个商品。
 *
 * <p><b>幂等</b>：商品本来就不在车里也返回成功，不报错。
 * 所以调用方不用先判断「它还在不在」。
 */
export function removeCartItem(productId) {
  return request.delete(`/shop/cart/items/${productId}`)
}

/** 清空购物车。⚠️ 界面层必须先弹二次确认，这个操作不可撤销 */
export function clearCart() {
  return request.delete('/shop/cart')
}
