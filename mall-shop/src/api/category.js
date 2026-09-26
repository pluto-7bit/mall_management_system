import request from './request'

/**
 * 用户端分类接口。
 *
 * <p>给顶部导航条和商品列表页左侧的「分类筛选」用，返回所有<b>启用</b>的分类，
 * 按 {@code sort} 升序。游客也能访问。
 *
 * <p>返回的每一条是后端的 {@code Category} 实体（含里程碑 16 新增的 {@code parentId}）：
 * {@code { id, name, sort, status, parentId, createTime, updateTime } }。
 * {@code parentId === 0} 表示一级分类（**不是 null** —— 后端刻意用 0 表示"没有父"）。
 *
 * <p>★ 注意后端这里<b>没有建 VO</b>，直接把实体返回了 ——
 * 而商品接口却老老实实建了两个 VO。为什么两种做法？
 * 详见后端 {@code ShopCategoryController} 的类注释，
 * 一句话版本：<b>判断标准是「有没有不该给前端看的东西」，
 * 而不是「规范要求建 VO」</b>。分类里确实没什么可藏的。
 *
 * <p>★★ <b>返回的是【扁平数组】，不是树 —— 这是一个刻意的选择，别顺手改成树。</b>
 * 这个接口有三个使用者，其中两个是按字段 {@code find} 的
 * （{@code Home.vue} 的 banner 按 {@code name} 找一个分类、当前筛选说明按 {@code id} 找一个）。
 * <b>给它们一棵树，这两处会静默失效</b>：{@code find} 在嵌套结构上找不到就是 {@code undefined}，
 * banner 悄悄退回首页、筛选说明悄悄不显示。<b>加一个字段则三个使用者一个都不用动。</b>
 *
 * <p>而前端需要的「两级」根本不需要递归建树：
 * {@code roots = list.filter(c => !c.parentId)}、
 * {@code childrenOf(id) = list.filter(c => c.parentId === id)} —— 两个 filter 就够了。
 * 这是「分类最多两级」那个决定的第二次红利（见 {@code stores/category.js}）。
 *
 * <p>★ 只返回<b>祖先链上每一个也启用的</b>分类，不只是「自己启用」。
 * 否则会出现这个静默失败：一个<b>启用</b>的子分类，父分类被禁用了 ——
 * 它既不是根（{@code parentId !== 0}），也不出现在任何根的 {@code childrenOf} 里，
 * 于是<b>从导航里凭空消失</b>，而库里一切正常。
 */

/**
 * 查询所有启用的分类（扁平数组，含 {@code parentId}）。
 *
 * <p>这个接口没有参数 —— 分类就那么几到几十条，
 * 不需要分页，一次全给前端。前端拿去渲染筛选栏，
 * 数据量再大也扛得住（想想有多少个一级分类）。
 *
 * <p>⚠️ 筛选商品时传的是 {@code categoryId}，而<b>「筛父分类要包含它下面所有后代」
 * 这件事由后端负责，前端一点都不用做</b>。前端也别自作聪明地把子分类的 id
 * 收集起来拼一串传过去 —— 那会让同一个筛选条件在两处各有一份定义，
 * 而它们迟早会分岔（管理端那时候还是只筛自己）。
 */
export function getShopCategories() {
  return request.get('/shop/categories')
}
