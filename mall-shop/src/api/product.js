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

/**
 * 查询<b>一个规格</b>的详情（含它所属商品的名字、封面、分类）。
 *
 * <p>{@code GET /api/shop/skus/204}
 *
 * <p>★ 里程碑 15 阶段 4 新加的，只服务<b>一条路</b>：<b>立即购买</b>。
 *
 * <p>为什么「加入购物车」不需要它？因为加购时用户就站在商品详情页上，
 * 商品详情接口返回的 {@code skus} 数组里已经有每行的价格和库存了，
 * 再查一次是白跑一趟。
 *
 * <p>那「立即购买」为什么需要？因为<b>结算页手里只有一个 skuId</b>：
 * 从详情页跳过来时 URL 是 {@code /checkout?skuId=204&quantity=2}，
 * 结算页要渲染的那一行（商品名、封面、规格文字、单价）全都得靠这次请求。
 * 这正是不把接口挂成 {@code /products/{id}/skus/{skuId}} 的理由 ——
 * {@code URL} 上只有一个 id 的时候，接口也只该要一个 id。
 *
 * <p>⚠️ 和 {@link getShopProductDetail} 完全一致的两点：
 * <ul>
 *   <li>商品不存在<b>或已下架</b>时返回业务码 1003，Promise 被打回 rejected，
 *       {@code request.js} 的统一提示会弹「商品不存在或已下架」</li>
 *   <li>这两种失败原因<b>故意回同一句话</b>，不告诉外面「这个 id 存在但下架了」</li>
 * </ul>
 *
 * <p>★ 返回的字段：{@code {id, specs, specText, price, stock,
 * productId, productName, cover, categoryName}}。
 * 结算页的「立即购买」那一行要的正好就是这些。
 *
 * @param {number} skuId 规格 id
 */
export function getShopSku(skuId) {
  return request.get(`/shop/skus/${skuId}`)
}
