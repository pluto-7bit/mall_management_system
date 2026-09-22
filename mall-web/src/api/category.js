import request from './request'

/**
 * 分类相关的接口调用（管理端）。
 *
 * <h3>这里有两个「查列表」的接口，别用混了</h3>
 *
 * <pre>
 *   getCategoryOptions()  全量、只查启用的  → 给下拉框用
 *   getCategoryPage(...)  分页、带条件筛选  → 给管理页表格用
 * </pre>
 *
 * <p>为什么需要两个？因为需求是冲突的：
 * <ul>
 *   <li>管理页要看到<b>禁用</b>的分类（否则禁用了就再也改不回来），
 *       还要分页（分类多了不能一次全拉）</li>
 *   <li>下拉框<b>只能看到启用</b>的分类（禁用的分类不该再挂新商品），
 *       而且不能分页（下拉框要能选到全部）</li>
 * </ul>
 *
 * <p>一个很常见的错误是：在下拉框里用分页接口，
 * 结果只加载到第一页的 10 个分类，第 11 个分类就选不到了。
 * 这种 bug 在分类少于 10 个时完全看不出来，上线后才爆。
 */

/**
 * 查询所有启用的分类（下拉框专用）。
 *
 * @returns {Promise<Array>} [{ id, name, sort, status }, ...]
 */
export function getCategoryOptions() {
  return request.get('/admin/categories/options')
}

/**
 * 分页查询分类列表（管理页表格专用）。
 *
 * @param {Object} params { pageNum, pageSize, name, status }
 * @returns {Promise<Object>} { list, total, pageNum, pageSize, pages }
 */
export function getCategoryPage(params) {
  return request.get('/admin/categories', { params })
}

/** 查询分类详情（编辑弹窗回填用） */
export function getCategoryDetail(id) {
  return request.get(`/admin/categories/${id}`)
}

/** 新增分类，返回新分类的 id */
export function createCategory(data) {
  return request.post('/admin/categories', data)
}

/** 修改分类 */
export function updateCategory(id, data) {
  return request.put(`/admin/categories/${id}`, data)
}

/**
 * 删除分类。
 *
 * <p>注意这个接口<b>可能失败</b>：如果分类下还挂着商品，
 * 后端会返回 code 1004 和一句「该分类下还有 N 个商品，请先移走或删除这些商品」。
 * 这句提示会被响应拦截器自动弹出来，所以页面里只要 catch 住、
 * 让弹窗保持打开即可，不需要自己判断错误类型。
 */
export function deleteCategory(id) {
  return request.delete(`/admin/categories/${id}`)
}
