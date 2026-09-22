import request from './request'

/**
 * 商品相关的接口调用（管理端）。
 *
 * <p>为什么要把接口调用单独抽成一个文件，而不是在页面里直接写
 * {@code request.get('/admin/products')}？有三个好处：
 *
 * <ol>
 *   <li><b>路径只写一遍</b>：将来后端把 /products 改成 /goods，
 *       只改这个文件，不用去每个页面里搜</li>
 *   <li><b>有类型提示和自动补全</b>：页面里 import { getProductList } 后，
 *       写错方法名编辑器立刻报错，而不是运行时才发现</li>
 *   <li><b>页面代码更干净</b>：看页面时一眼就知道它调了哪些接口</li>
 * </ol>
 *
 * <h3>关于路径里的 /admin 前缀</h3>
 *
 * <p>request 实例的 baseURL 是 {@code /api}，所以下面写的 {@code /admin/products}
 * 最终拼出来是 {@code /api/admin/products}。
 *
 * <p><b>为什么要加 admin 这一段？</b> 因为系统有两类使用者：
 * 管理端（这个工程）和用户端（后面的 mall-shop）。
 * 两边都需要「商品列表」，但看到的数据完全不同：
 * <pre>
 *   GET /api/admin/products  → 能看到下架商品、能看到真实库存
 *   GET /api/shop/products   → 只能看到上架商品
 * </pre>
 * 用 URL 前缀区分开，加权限拦截器时可以按路径一刀切
 * （所有 /api/admin/** 必须管理员登录），不会误伤用户端。
 */

/**
 * 分页查询商品列表。
 *
 * @param {Object} params 查询条件 { pageNum, pageSize, name, categoryId, status }
 * @returns {Promise<Object>} { list, total, pageNum, pageSize, pages }
 *
 * 注意返回的直接就是 data 部分 —— 因为 request.js 的响应拦截器
 * 已经帮我们把 { code, message, data } 的外壳剥掉了。
 */
export function getProductList(params) {
  return request.get('/admin/products', { params })
}

/**
 * 查询商品详情。
 *
 * 模板字符串里的 ${id} 是 JS 的变量插值，拼出来是 /admin/products/5
 */
export function getProductDetail(id) {
  return request.get(`/admin/products/${id}`)
}

/** 新增商品 */
export function createProduct(data) {
  return request.post('/admin/products', data)
}

/** 修改商品 */
export function updateProduct(id, data) {
  return request.put(`/admin/products/${id}`, data)
}

/** 删除商品 */
export function deleteProduct(id) {
  return request.delete(`/admin/products/${id}`)
}

/**
 * 上传一张图片，返回它的访问路径（形如 /uploads/2026/09/xx.png）。
 *
 * <h3>★ 这是全项目唯一一个【不用 JSON】的接口</h3>
 *
 * <p>请求体是 {@code multipart/form-data}，所以不能像其他接口那样
 * 直接把一个对象交给 axios —— 必须显式构造一个 {@code FormData}
 * 并用 {@code append} 塞进去。axios 认得 FormData，
 * 会自动设好 Content-Type <b>和那段 boundary</b>
 * （boundary 是分隔各个字段的随机字符串，手写必错，所以别手写）。
 *
 * <h3>★ 字段名必须是 file</h3>
 *
 * <p>后端是 {@code @RequestParam("file") MultipartFile file}，
 * 名字对不上就报「缺少 file 参数」。这个名字是接口契约的一部分。
 *
 * <h3>⚠️ 为什么单独把这个请求的 timeout 放宽到 30 秒</h3>
 *
 * <p>request.js 里配的全局 timeout 是 10 秒 —— 那个值是给
 * 「查一次数据库就返回」的接口定的，对它们来说 10 秒已经很宽裕了。
 *
 * <p>上传完全是另一回事：要把整个文件从浏览器传上去、再写进磁盘。
 * 图片动辄几百 KB 到 2MB，网慢的时候 10 秒<b>真的会超时</b>。
 *
 * <p>而超时的后果比「报个错」难受得多：<b>文件很可能已经完整传到后端
 * 并存进磁盘了</b>，只是响应还没回来。前端弹一句「请求超时」，
 * 用户以为失败就再传一次 —— 于是磁盘上多了一个永远没人引用的孤儿文件。
 * （这个功能的清理策略是「文件只增不减」，所以它会一直留在那儿。）
 *
 * @param {File} file 用户选中的文件对象（el-upload 的 option.file）
 * @returns {Promise<string>} 图片的可访问路径
 */
export function uploadImage(file) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/admin/images', fd, { timeout: 30000 })
}
