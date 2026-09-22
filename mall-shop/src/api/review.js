import request from './request'

/**
 * 商品评价接口。
 *
 * <h3>★★★ 这个文件里最重要的东西是【请求体里没有什么】</h3>
 *
 * <p>看 {@code submitReview} 的参数：
 * <pre>
 *   {orderItemId, rating, content, images}
 *
 *   ✗ 没有 productId  —— 服务端从 order_item 查出来
 *   ✗ 没有 memberId   —— 服务端从 JWT 里取
 *   ✗ 没有 createTime —— 数据库的 DEFAULT CURRENT_TIMESTAMP
 *   ✗ 没有 id         —— 主键自增
 * </pre>
 *
 * <p>这不是「少写几个字段」，是一条<b>安全边界</b>：
 * <b>客户端只能声明「我想评哪一条明细」，能不能评、评的是哪个商品、
 * 是谁在评，全部由服务端判定。</b>
 *
 * <p>★ 尤其注意 {@code productId} 为什么不收：如果让客户端报商品，
 * 那么「评价挂到哪个商品下」就变成了一个用户可以随便填的值 ——
 * 他可以拿着自己买过的一单 A 商品，去给从没买过的 B 商品刷一条五星。
 * <b>唯一索引挡的是「一条明细评两次」，挡不住「评价挂到别的商品上」。
 * 所以商品必须从明细推导，不能接受客户端提供。</b>
 *
 * <p>这条规则和 {@code api/order.js} 里那段「凡是服务端有权威来源的数据，
 * 一律不接受客户端提供」是同一句话 —— 这里只是它的第二次应用。
 */

/**
 * 分页查某个商品的评价。
 *
 * <p>{@code GET /api/shop/products/{productId}/reviews}
 *
 * <p>★ <b>这个接口【匿名可调】</b>（不用登录）——
 * 商品详情页游客也能逛，评价区自然也要能看。
 * 它挂在 {@code /api/shop/products/**} 这棵树下面，
 * 所以自动继承了「商品浏览匿名可读」那条权限规则，
 * 见后端 {@code WebMvcConfig} 的 excludePathPatterns。
 *
 * <p>⚠️ <b>路径段数是 3 段</b>（{@code products}/{@code {id}}/{@code reviews}），
 * 和后端商品详情的 {@code GET /api/shop/products/{id}}（2 段）不冲突 ——
 * 但这只对后端有意义，前端这边只关心别把 id 拼错。
 *
 * <p>@param {number} productId
 * @param {object} [params]
 * @param {number} [params.pageNum] 从 1 开始
 * @param {number} [params.pageSize] 后端上限 100，超了会被钳到 100
 * @returns {Promise<{list: object[], total: number, pageNum: number,
 *                    pageSize: number, pages: number}>}
 */
export function listProductReviews(productId, params) {
  return request.get(`/shop/products/${productId}/reviews`, { params })
}

/**
 * 发表一条评价。
 *
 * <p>{@code POST /api/shop/reviews}
 * <p><b>必须登录。</b>
 *
 * <p>⚠️ 注意它落在 {@code /shop/reviews}，<b>不在</b>
 * {@code /shop/products/**} 下面 —— 这是故意的。
 * 那条被排除的路径树里的一切都是匿名可读的，
 * 一个写操作要是挂进去，就会变成匿名可写，而且<b>不会有任何报错</b>。
 * 所以「需要登录的接口各自独立成树」。
 *
 * <h3>★ 这是全项目唯一一个「不可修改、不可撤销」的写接口</h3>
 *
 * <p>评价一旦提交就<b>不能再改也不能删</b>（用户确认过的设计：
 * 「一次定终身」）。后端在 {@code product_review.order_item_id} 上
 * 建了唯一索引，所以第二次提交会被拒 —— 但它拒的是<b>另一个请求</b>，
 * 拒不了「用户手抖点错了」。
 *
 * <p>所以界面上必须做到两件事：
 * <ol>
 *   <li>提交前给用户看清自己填了什么（星级、文字、图片都在同一屏上）</li>
 *   <li><b>提交按钮加 {@code :loading}</b>，防止一次点击发出去两个请求</li>
 * </ol>
 * 第二件事不是体验问题 —— 后端挡得住重复（唯一索引 + 那一段
 * {@code catch (DuplicateKeyException)}），但用户会看到一句
 * 「你已经评价过了」，而他明明是第一次评。加了 loading 就不会走到那里。
 *
 * <p>@param {object} data
 * @param {number}   data.orderItemId ★ <b>订单明细</b>的 id，不是订单 id、不是商品 id
 * @param {number}   data.rating      1~5
 * @param {string}   data.content     最多 500 字
 * @param {string[]} [data.images]    晒图路径，最多 3 张。
 *                  <b>不传和传 {@code []} 是同一个意思</b>（都是「没晒图」）——
 *                  这一点和商品图集不同：那边 {@code update} 时
 *                  「不传」表示「不要动」，「传 []」表示「清空」。
 *                  区别在于<b>这个操作有没有「保持不变」这个可能</b>：
 *                  create 没有（刚建的东西没有"原来"），update 有。
 * @returns {Promise<number>} 新评价的 id
 */
export function submitReview(data) {
  return request.post('/shop/reviews', data)
}

/**
 * 上传一张评价晒图，返回它的访问路径（形如 /uploads/2026/09/xx.png）。
 *
 * <h3>★★ 为什么有了 {@code /api/admin/images} 还要再写一个</h3>
 *
 * <p>因为那是<b>管理端</b>的接口，它在 {@code /api/admin/**} 下面，
 * 被管理员的拦截器拦着。<b>会员的 token 过不去。</b>
 * 后端为此专门开了 {@code POST /api/shop/images}（会员侧上传端点）。
 *
 * <p>对前端来说，这两个函数长得几乎一样 —— 这正是后端
 * 把上传逻辑放在 {@code FileStorageService} 里的回报：
 * 魔数校验、2MB 上限、UUID 重命名、按年月分目录，
 * 会员侧那个接口<b>一行代码都没复制</b>，但它继承了一整套规则。
 *
 * <h3>★ 不用 JSON</h3>
 *
 * <p>请求体是 {@code multipart/form-data}，所以必须显式构造一个
 * {@code FormData} 再 {@code append}。<b>不要手写 boundary</b> ——
 * axios 认得 FormData，会自动设好 Content-Type 和那段随机分隔串，
 * 手写必错。
 *
 * <p>★ 字段名必须是 {@code file}：后端是
 * {@code @RequestParam("file") MultipartFile file}，
 * 名字对不上就报「缺少 file 参数」。这是接口契约的一部分。
 *
 * <h3>⚠️ timeout 单独放宽到 30 秒</h3>
 *
 * <p>{@code request.js} 里配的全局 timeout 是 10 秒 —— 那是给
 * 「查一次数据库就返回」的接口定的。上传要传整个文件，是另一回事。
 *
 * <p>而超时的后果比「报个错」难受得多：<b>文件很可能已经完整传到后端
 * 并存进磁盘了</b>，只是响应还没回来。用户看到「请求超时」以为失败，
 * 就再传一次 —— 磁盘上于是多了一个永远没人引用的孤儿文件。
 *
 * <p>⚠️ 这个理由和 {@code api/product.js} 里管理端那个上传函数
 * <b>一字不差</b> —— 两个前端工程不共享代码，所以理由也各写了一份。
 *
 * @param {File} file 用户选中的文件对象（el-upload 的 option.file）
 * @returns {Promise<string>} 图片的可访问路径
 */
export function uploadReviewImage(file) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/shop/images', fd, { timeout: 30000 })
}
