package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.LoginUser;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.common.UserType;
import com.example.mall.dto.LoginDTO;
import com.example.mall.entity.AdminUser;
import com.example.mall.mapper.AdminUserMapper;
import com.example.mall.service.AdminAuthService;
import com.example.mall.util.JwtUtil;
import com.example.mall.vo.AdminInfoVO;
import com.example.mall.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 管理端认证业务实现。
 *
 * <p>用户端对应的是 {@link MemberAuthServiceImpl}。
 * 两个类看起来很像，但<b>刻意没有抽公共父类</b> ——
 * 和拦截器那边的情况不一样。判断标准见 {@link com.example.mall.service.MemberAuthService}
 * 的类注释：拦截器共享的是「流程」（步骤固定、顺序固定），
 * 而这里共享的只是「几行看着像的代码」（字段不同、日志不同、
 * 以后的校验规则也会分岔）。<b>抽流程是重构，抽巧合的相似是添乱。</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public LoginVO login(LoginDTO dto) {
        String username = dto.getUsername().trim();

        AdminUser admin = adminUserMapper.selectByUsername(username);

        // ------------------------------------------------------------------
        // ★ 防「用户名枚举」：账号不存在和密码错误，返回【完全一样】的提示
        // ------------------------------------------------------------------
        // 如果分开提示：
        //   账号不存在 → "该账号未注册"
        //   密码错误   → "密码错误"
        // 攻击者就能拿一份常见用户名清单批量试，
        // 根据提示语迅速筛出「哪些账号真实存在」，
        // 然后再针对这些账号集中爆破密码。这叫做「用户名枚举攻击」。
        //
        // 合并成一句之后，攻击者试一万次也不知道哪个账号是对的。
        //
        // ⚠️ 诚实地说：即使提示语一样，也还有一个更隐蔽的信道 ——
        //    「账号不存在」时跳过了 BCrypt 比对，响应会快几十毫秒。
        //    攻击者可以用响应时间差来区分。这叫「时间侧信道」。
        //    彻底堵住它需要在账号不存在时也跑一次假的 BCrypt 比对。
        //    本学习项目不做这层防护，但要知道它存在。
        //
        //    用户端（MemberAuthServiceImpl.login）有同样的处理和同样的取舍。
        // ------------------------------------------------------------------
        if (admin == null || !passwordEncoder.matches(dto.getPassword(), admin.getPassword())) {
            // 日志里记下真实原因，方便运维排查「用户说登录不上」的问题；
            // 但返回给前端的提示是模糊的
            log.warn("管理端登录失败, username={}, 原因={}",
                    username, admin == null ? "账号不存在" : "密码错误");
            throw new BusinessException(ResultCode.LOGIN_FAILED, "账号或密码错误");
        }

        // 密码对了才检查账号状态。
        // 顺序很重要：先验密码意味着「拿着错误密码去试探一个被禁用的账号」
        // 得到的也是「账号或密码错误」，不会泄漏这个账号存在且被禁用
        if (admin.getStatus() != null && admin.getStatus() == 0) {
            log.warn("已禁用的管理员尝试登录, username={}", username);
            throw new BusinessException(ResultCode.LOGIN_FAILED, "该账号已被禁用，请联系系统管理员");
        }

        String token = jwtUtil.generate(admin.getId(), admin.getUsername(), UserType.ADMIN);
        log.info("管理端登录成功, id={}, username={}", admin.getId(), admin.getUsername());

        // 返回 VO 而不是 AdminUser 实体 —— 后者带着 password 字段，
        // 直接返回会把密码哈希泄漏到前端（见 AdminUser 的类注释）
        return LoginVO.builder()
                .token(token)
                .id(admin.getId())
                .username(admin.getUsername())
                .nickname(admin.getNickname())
                .build();
    }

    @Override
    public AdminInfoVO currentAdmin() {
        // ★ 这里用 require() 而不是 get()：
        //   被拦截器保护的方法里，用户一定是登录的。
        //   require() 把这个「一定」变成代码里的断言，
        //   省掉了下面这次判空，也让阅读的人知道「这里不可能为 null」
        LoginUser loginUser = UserContext.require();

        // 拿 token 里的 id 去查库，而不是直接把 token 里的 username 返回。
        //
        // 为什么不直接用 token 里的信息？因为 token 可能已经签发了很久，
        // 期间昵称被改过、甚至账号被禁用了。
        // token 里放的信息相当于「签发那一刻的快照」，会过期。
        // 只有 id 是终生不变的，其他信息都该以数据库为准。
        //
        // 取舍：这样多了一次数据库查询，但换来的是信息永远最新。
        // 对 /me 这种「每次刷新页面才调一次」的接口，这笔买卖很划算；
        // 如果是每秒调几百次的高频接口，就值得把信息放进 token 里。
        AdminUser admin = adminUserMapper.selectById(loginUser.id());
        if (admin == null) {
            // 能走到这里说明：token 是合法的（签名对、没过期），
            // 但对应的账号已经被删了。属于边缘情况，但必须处理 ——
            // 不处理的话下面 admin.getUsername() 就是空指针
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号不存在，请重新登录");
        }
        if (admin.getStatus() != null && admin.getStatus() == 0) {
            // ★ 这里体现了一个重要的设计选择：
            //   被禁用的账号，即使手里有还没过期的 token，也立刻失效。
            //
            //   拦截器里【没有】做这个检查（那样每个请求都要查一次库，
            //   JWT 无状态的优势就没了）。而是在真正要用用户信息的接口里查。
            //
            //   如果要求「禁用后立即断开所有接口」，就得在每个请求都查库，
            //   或者引入 token 黑名单 —— 又是拿性能换安全的老问题。
            throw new BusinessException(ResultCode.UNAUTHORIZED, "该账号已被禁用，请联系系统管理员");
        }

        return AdminInfoVO.builder()
                .id(admin.getId())
                .username(admin.getUsername())
                .nickname(admin.getNickname())
                .build();
    }
}
