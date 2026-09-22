import request from './request'

/**
 * 用户端分类接口。
 *
 * <p>给商品列表页左侧的「分类筛选」用，返回所有<b>启用</b>的分类，
 * 按 {@code sort} 升序。游客也能访问。
 *
 * <p>返回的每一条就是后端的 {@code Category} 实体：
 * {@code { id, name, sort, status, createTime, updateTime } }。
 *
 * <p>★ 注意后端这里<b>没有建 VO</b>，直接把实体返回了 ——
 * 而商品接口却老老实实建了两个 VO。为什么两种做法？
 * 详见后端 {@code ShopCategoryController} 的类注释，
 * 一句话版本：<b>判断标准是「有没有不该给前端看的东西」，
 * 而不是「规范要求建 VO」</b>。分类里确实没什么可藏的。
 */

/**
 * 查询所有启用的分类。
 *
 * <p>这个接口没有参数 —— 分类就那么几到几十条，
 * 不需要分页，一次全给前端。前端拿去渲染筛选栏，
 * 数据量再大也扛得住（想想有多少个一级分类）。
 */
export function getShopCategories() {
  return request.get('/shop/categories')
}
