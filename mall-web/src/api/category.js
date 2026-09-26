import request from './request'

/**
 * 分类相关的接口调用（管理端）。
 *
 * <h3>★ 里程碑 16 起：两个「查列表」的接口变了形状</h3>
 *
 * <pre>
 *   getCategoryOptions()  启用 + 扁平       → 给下拉框用（**一个字没改**）
 *   getCategoryTree(...)  全部 + 嵌套树     → 给管理页表格和上级选择器用
 * </pre>
 *
 * <p>为什么不把两个接口合并成一个？因为需求还是冲突的：
 * <ul>
 *   <li>管理页要看到<b>禁用</b>的分类（否则禁用了就再也改不回来），
 *       而且表格是<b>树</b>（父子层级要看得出来）</li>
 *   <li>下拉框<b>只能看到启用</b>的分类（禁用的分类不该再挂新商品），
 *       而且必须是<b>扁平</b>的（见下）</li>
 * </ul>
 *
 * <h3>★★ 为什么下拉框那个接口必须是扁平的，而不是"顺便也改成树"</h3>
 *
 * <p>因为 <b>`el-select` 拿到嵌套数据不会报错，它会把每一个根渲染成一个空白选项</b>：
 * 下拉框还在、还能点、就是一个分类名都不显示。
 * `el-select` 读的是它拿到的那个数组里每一项的 `label` / `value`——
 * 而树里的根节点只有 `children`，没有平铺的兄弟。<b>它不认识的字段它不会去找。</b>
 *
 * <p>所以「商品表单里给分类选项加一级缩进」这件事，
 * 靠的是在<b>扁平</b>数据上拼字符串（`'　└ ' + name`），
 * 而不是把数据换成树。见 `views/product/ProductForm.vue`。
 *
 * <h3>⚠️ 一个很常见的错误：在下拉框里用「列表」接口</h3>
 *
 * <p>（分页时代是「只加载到第一页的 10 个分类，第 11 个就选不到」；
 * 现在没有分页了，但错误换了张脸：）在需要<b>嵌套</b>的地方
 * （表格、上级选择器）用扁平的 `getCategoryOptions()`，
 * 会让子分类<b>以平级的身分混在根分类中间</b>——
 * 看起来是一张正常的表格，只是层级没了，也没有任何地方会报错。
 */

/**
 * 查询所有启用的分类（下拉框专用）。
 *
 * @returns {Promise<Array>} [{ id, parentId, name, sort, status }, ...] —— **扁平**数组
 */
export function getCategoryOptions() {
  return request.get('/admin/categories/options')
}

/**
 * 查询分类树（管理页表格 + 上级分类选择器专用）。
 *
 * <h3>★★ 这个函数有一处【必须遵守的调用约定】</h3>
 *
 * <pre>
 *   列表页：getCategoryTree(query)   带 name/status 筛选 —— 数据可能被裁剪
 *   表单页：getCategoryTree()        【不带任何参数】—— 永远是全量、最新的
 * </pre>
 *
 * <p>表单页的「上级分类」选项<b>绝不能</b>复用列表页那份。
 * 理由和「编辑商品要查详情、不要复用列表行」是同一条：
 * 列表页的数据可能正被搜索裁剪着，拿它当父分类选项，
 * 用户搜过一次之后再打开编辑，就会<b>少掉一大批可选的父分类</b>——
 * 而他会以为那些分类不存在。
 *
 * <p>★ 传了参就返回裁剪后的树，这不是 bug，是接口的语义。
 * <b>危险的是「谁传了什么」在调用处看不出来</b>，所以这条约定写在这里。
 *
 * <h3>筛选语义（不是"过滤掉不匹配的"那么简单）</h3>
 *
 * <p>命中一个子分类时，它的<b>祖先链</b>也会在结果里（祖先本身不需要命中）；
 * 命中一个父分类时，它的<b>整棵子树</b>都在。
 * 这样每个结果都挂在一个看得见的位置上，不会出现"孤儿节点"。
 *
 * @param {Object} [params] { name, status } —— 都不传就是全量树
 * @returns {Promise<Array>} 森林（可能多个根），每个节点
 *   { id, parentId, name, sort, status, createTime, updateTime, children? }
 *   ★ 叶子节点【没有 children 这个键】（不是空数组）——
 *     后端 Jackson 配了 non_null，null 会被整个丢掉。
 *     前端判断一个节点有没有子分类要用 `node.children?.length`，
 *     不能假定它存在。
 */
export function getCategoryTree(params) {
  return request.get('/admin/categories', { params })
}

/** 查询分类详情（编辑弹窗回填用，返回单个分类，含 parentId） */
export function getCategoryDetail(id) {
  return request.get(`/admin/categories/${id}`)
}

/** 新增分类，返回新分类的 id。body 里 parentId 传 0 表示建一级分类 */
export function createCategory(data) {
  return request.post('/admin/categories', data)
}

/** 修改分类。★ parentId 不传 = 变成一级分类（后端把它归一成 0） */
export function updateCategory(id, data) {
  return request.put(`/admin/categories/${id}`, data)
}

/**
 * 删除分类。
 *
 * <p>注意这个接口<b>有两种</b>「被拒绝」：
 * <ul>
 *   <li>分类下还挂着<b>商品</b> → code 1004 +「该分类下还有 N 个商品……」（里程碑 6 起）</li>
 *   <li>分类下还有<b>子分类</b> → code 1011 +「该分类下还有 N 个子分类……」（里程碑 16 起）</li>
 * </ul>
 *
 * <p>两句提示都会被响应拦截器自动弹出来，所以页面里只要 catch 住、
 * 让弹窗保持打开即可，不需要自己判断错误类型。
 *
 * <p>★ 后端<b>先查子分类、后查商品</b>。「还有子分类」比「还有商品」更该先说 ——
 * 用户要做的事不一样（先删子分类 vs 先移走商品），
 * 而只报商品的话，他移完商品会再被拒一次。
 */
export function deleteCategory(id) {
  return request.delete(`/admin/categories/${id}`)
}
