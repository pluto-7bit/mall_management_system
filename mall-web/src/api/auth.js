import request from './request'

/**
 * 认证相关的接口调用（管理端）。
 */

/**
 * 登录。
 *
 * @param {Object} data { username, password }
 * @returns {Promise<Object>} { token, id, username, nickname }
 *
 * 注意返回里【没有 password】—— 后端返回的是 LoginVO 而不是实体，
 * 只包含白名单里的字段。这是后端的事，但前端也该知道
 * 「这个响应里永远不会出现密码」，别去读一个不存在的字段。
 */
export function login(data) {
  return request.post('/admin/auth/login', data)
}

/**
 * 查询当前登录管理员的信息。
 *
 * 用途是「刷新页面后恢复登录状态」：
 * localStorage 里只有 token，没有昵称这些信息。
 *
 * 它还有一个隐含用途 —— 检验 token 还有没有效。
 * 如果 token 过期了，这个接口会返回 HTTP 401，
 * 请求拦截器就会自动清掉 token 并跳登录页。
 *
 * @returns {Promise<Object>} { id, username, nickname }
 */
export function getCurrentAdmin() {
  return request.get('/admin/auth/me')
}

/**
 * ★ 这里没有 logout 接口，这是刻意的。
 *
 * JWT 是无状态的，服务端没有「登录状态」这个东西可以清除。
 * 退出登录就是前端把 localStorage 里的 token 删掉，一个请求都不用发。
 *
 * 详见后端 AuthService 的类注释。
 */
