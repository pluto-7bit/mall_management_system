package com.example.mall.service;

import com.example.mall.dto.LoginDTO;
import com.example.mall.dto.MemberRegisterDTO;
import com.example.mall.vo.LoginVO;
import com.example.mall.vo.MemberInfoVO;

/**
 * 用户端（会员）认证业务接口。
 *
 * <p><b>★ 为什么不和管理端的 {@link AdminAuthService} 合并成一个 AuthService？</b>
 *
 * <p>它们看起来都是「登录」，但那只是名字像。实际比一比：
 * <pre>
 *   管理端：登录 / 查当前管理员                两个方法
 *   用户端：注册 / 登录 / 查当前会员            三个方法
 * </pre>
 * 合并之后得到的类会是「两个互不相干的代码块塞在同一个文件里」——
 * 它们不共享字段、不调用彼此、连日志措辞都不一样。
 * 唯一的共同点是方法名都叫 login。
 *
 * <p>更关键的是<b>变化方向不同</b>：
 * 用户端登录马上就要加「图形验证码」（防机器注册），
 * 管理端登录可能永远不需要；管理端以后要加「登录失败次数限制」，
 * 用户端则更适合交给网关限流。
 * 合并之后，每次给一边加东西，另一边都得跟着重新测试。
 *
 * <p><b>「两个东西名字像」不是合并的理由，「它们会一起变」才是。</b>
 *
 * <p>顺带一提：这两个接口没有公共父接口（比如 {@code AuthService}）
 * 也不是疏忽。父接口的价值在于「调用方只关心抽象、可以有多种实现」，
 * 而这里的调用方是各自的 Controller，一一对应，永远不会替换实现。
 * 加一层只有两个实现的空接口，是纯粹的仪式感。
 */
public interface MemberAuthService {

    /**
     * 注册新会员。
     *
     * <p>★ 注册成功后<b>直接返回 token，等于顺带完成了登录</b>。
     *
     * <p>这是刻意的体验设计：用户刚填完账号密码点了「注册」，
     * 如果结果是「注册成功，请去登录页登录」，他会觉得白忙一场 ——
     * 明明刚刚才把密码输过两遍。
     *
     * <p>另一种做法是注册接口只返回成功、让前端再调一次登录接口，
     * 也能达到同样效果，但那是<b>两次网络往返</b>，而且中间那次失败
     * 会让用户处于「注册成功了却登不进去」的尴尬状态。
     * 服务端一次做完，要么全成要么全不成，更干净。
     *
     * @return 含 token 的登录信息，前端拿到即可直接进入已登录状态
     */
    LoginVO register(MemberRegisterDTO dto);

    /**
     * 会员登录。
     *
     * <p>复用管理端的 {@link LoginDTO}，因为它就是「账号 + 密码」这两个字段，
     * 没有任何管理端特有的东西。等用户端真的要加验证码字段时再拆开 ——
     * 那时拆的成本是改一个 import，而现在复制一份的成本是
     * 以后每次改密码规则都要记得改两个地方。
     *
     * @return 含 token 的登录信息
     */
    LoginVO login(LoginDTO dto);

    /**
     * 查询当前登录会员的信息。
     *
     * <p>前端刷新页面后调它，用 localStorage 里的 token 换回用户信息。
     */
    MemberInfoVO currentMember();
}
