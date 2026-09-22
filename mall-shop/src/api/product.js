import request from './request'

/**
 * 用户端商品接口。
 *
 * <p>路径都在 {@code /api/shop/products/**} 下面，<b>游客也能访问</b> ——
 * 后端 {@code WebMvcConfig} 里把这两条路径从
 * {@code MemberAuthInterceptor} 的拦截范围中排除掉了。
 *
 * <p>所以这几个函数<b>不需要判断登录状态</b>，直接调就行。
 *
 * <p>★ 但也要留意：它们是<b>目前唯一</b>能这么用的接口。
 * 里程碑 7 的购物车、8 的下单都必须登录，
 * 调用前要先看 {@code userStore.isLoggedIn}。
 */

/**
 * 分页查询商品列表。
 *
 * <p>参数全部可选：
 * <pre>
 *   { pageNum, pageSize, keyword, categoryId, sort }
 * </pre>
 * {@code sort} 只认三个值：{@code default} / {@code price_asc} / {@code price_desc}，
 * 传别的后端会静默退化成默认排序（不报错）。
 *
 * <p><b>为什么用 {@code { params }} 而不是拼在 URL 里？</b>
 * axios 会自动做百分号编码。搜索关键词是中文时，
 * 手工拼 URL 会得到一串非法字符 —— 浏览器可能帮你兜住，
 * 也可能直接报错，很不可靠。交给 axios 处理最稳妥。
 *
 * <p>注意 axios 有个反直觉的行为：值为 {@code undefined} 或 {@code null} 的
 * 参数会被<b>直接丢掉</b>，但空字符串 {@code ''} 会发出去
 * （变成 {@code ?keyword=}）。后端 {@code normalize()} 里把空串
 * 转成了 null，所以两种都安全 —— 但这是后端兜的，不是前端做对了。
 */
export function getShopProductPage(params) {
  return request.get('/shop/products', { params })
}

/**
 * 查询商品详情。
 *
 * <p>⚠️ 商品不存在<b>或已下架</b>时，后端返回业务码 1003 而不是 200，
 * {@code request.js} 的响应拦截器会自动弹一句「商品不存在或已下架」
 * 并把 Promise 打回 rejected。所以调用方只需要处理 catch，
 * 不用自己去判断 code。
 *
 * <p>详情页拿到这个 rejected 之后该做的不是「报错就完了」，
 * 而是显示一个「商品可能已下架」的空状态页 ——
 * 用户很可能是从半年前的收藏夹点进来的，这不是故障。
 */
export function getShopProductDetail(id) {
  return request.get(`/shop/products/${id}`)
}
