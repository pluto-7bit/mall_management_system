import request from './request'

/**
 * 用户端认证相关接口。
 *
 * <p>路径都在 {@code /api/shop/auth/**} 下面。
 * 注意这个前缀不是随便起的 —— 后端
 * {@code MemberAuthInterceptor} 的拦截范围是 {@code /api/shop/**}，
 * 而这两个接口必须在 {@code WebMvcConfig} 的 excludePathPatterns 里
 * 被精确排除，否则没人能登录（死锁）。
 */

/** 注册。成功后会直接返回 token，等于顺手完成了登录 */
export function register(data) {
  return request.post('/shop/auth/register', data)
}

/** 登录 */
export function login(data) {
  return request.post('/shop/auth/login', data)
}

/**
 * 查询当前登录会员。
 *
 * <p>前端刷新页面后调它，用 token 换回昵称、手机号等信息。
 * 顺带也起到「验证 token 还有没有效」的作用。
 */
export function getCurrentMember() {
  return request.get('/shop/auth/me')
}

// 同样没有 logout 接口 —— JWT 是无状态的，
// 退出登录就是把 localStorage 里的 token 删掉，不需要请求服务端。
// 详见后端 MemberAuthController 的注释。
