package com.example.mall.service;

import com.example.mall.dto.LoginDTO;
import com.example.mall.vo.AdminInfoVO;
import com.example.mall.vo.LoginVO;

/**
 * 管理端认证业务接口。
 *
 * <p><b>★ 类名里的 "Admin" 是后加的</b>（原本叫 {@code AuthService}）。
 * 做用户端时有了 {@link MemberAuthService}，两个名字并排放在一起，
 * 原来的 {@code AuthService} 就变得有歧义了 ——
 * 「AuthService 是谁的认证？」读者得点进去才知道。
 *
 * <p>这种改名很便宜（一个 IDE 的重构快捷键），但收益是长期的：
 * <b>名字里带上区分维度，读者就不用靠打开文件来消除歧义。</b>
 * 值得做的时候不要犹豫，拖着才会变成「历史遗留命名」。
 *
 * <p>注意这里<b>没有 logout 方法</b>，这不是遗漏。
 *
 * <p>JWT 是无状态的：服务端不保存「谁登录了」这个信息，
 * 只靠验签名来判断 token 是否合法。所以服务端<b>没有办法</b>
 * 主动作废一个还没过期的 token —— 除非引入额外存储
 * （把作废的 token 记进 Redis 黑名单，每次请求查一下）。
 *
 * <p>那「退出登录」怎么做？<b>前端把 localStorage 里的 token 删掉</b>
 * 就完成了。因为 token 只存在于客户端，删掉它用户就再也用不了了。
 *
 * <p>这听起来像个缺陷，但它是权衡的结果：
 * <ul>
 *   <li>要「立刻踢下线」→ 必须查存储 → 退回有状态，失去 JWT 的横向扩展优势</li>
 *   <li>接受「最长等 token 过期」→ 无状态，简单，够用</li>
 * </ul>
 * 本项目选后者（token 有效期 2 小时）。
 * 银行类系统会选前者 —— <b>安全等级决定架构选择，没有标准答案</b>。
 */
public interface AdminAuthService {

    /**
     * 管理员登录。
     *
     * @return 登录成功后的 token 和用户信息
     * @throws com.example.mall.common.BusinessException 账号或密码错误、账号被禁用
     */
    LoginVO login(LoginDTO dto);

    /**
     * 查询当前登录管理员的信息。
     *
     * <p>前端刷新页面后用它恢复登录状态：localStorage 里只有 token，
     * 拿它换回昵称等信息。
     */
    AdminInfoVO currentAdmin();
}
