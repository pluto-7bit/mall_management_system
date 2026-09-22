package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.LoginUser;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.common.UserType;
import com.example.mall.dto.LoginDTO;
import com.example.mall.dto.MemberRegisterDTO;
import com.example.mall.entity.Member;
import com.example.mall.mapper.MemberMapper;
import com.example.mall.service.MemberAuthService;
import com.example.mall.util.JwtUtil;
import com.example.mall.vo.LoginVO;
import com.example.mall.vo.MemberInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户端（会员）认证业务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberAuthServiceImpl implements MemberAuthService {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional
    public LoginVO register(MemberRegisterDTO dto) {
        String username = dto.getUsername().trim();

        // ------------------------------------------------------------------
        // 1. 账号查重（第一道防线：给用户一句人话提示）
        // ------------------------------------------------------------------
        // member 表上有 UNIQUE KEY uk_username，为什么这里还要先查一次？
        //
        // 因为唯一索引报出来的错误是 "Duplicate entry 'zhangsan' for key
        // 'member.uk_username'" —— 这句话给不了用户任何有效信息，
        // 而且它暴露了表名和索引名。
        // 应用层先查一次，能在 99% 的情况下返回「该账号已被注册，换一个试试」。
        //
        // ★ 那唯一索引是不是就多余了？不是。
        //   查重和插入之间存在竞态：两个人同时注册同一个账号，
        //   两次查重都会通过，然后两次插入。
        //   这种情况下唯一索引是【唯一】能拦住它的东西，
        //   拦下来之后抛的 DuplicateKeyException 由下面的 catch 兜住。
        //
        //   两道防线各司其职：
        //     应用层查重 → 负责「体验」（给得出人话）
        //     唯一索引   → 负责「正确」（并发下也不会脏）
        //   只留应用层会脏，只留索引则提示看不懂 —— 两个都要。
        // ------------------------------------------------------------------
        if (memberMapper.countByUsername(username) > 0) {
            log.info("注册失败：账号已存在, username={}", username);
            throw new BusinessException(ResultCode.USERNAME_TAKEN, "该账号已被注册，换一个试试");
        }

        Member member = new Member();
        member.setUsername(username);

        // ★ 立刻加密，明文密码从此不再出现在任何地方。
        //   注意这里【没有】任何日志打印 dto.getPassword() ——
        //   一旦密码进了日志文件，它就成了运维和开发都能看到的明文，
        //   而且日志通常会被收集到集中式平台、保留很久、权限很宽。
        //   「不要把密码写进日志」是一条没有例外的规则。
        member.setPassword(passwordEncoder.encode(dto.getPassword()));

        member.setNickname(blankToNull(dto.getNickname()));
        member.setPhone(blankToNull(dto.getPhone()));

        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            // 走到这里说明遇上了上面说的并发竞态（或有人绕过应用层直接插库）。
            // 转成和上面一样的业务异常，让用户看到的提示保持一致 ——
            // 用户不关心自己是「先被查出来的」还是「被索引拦下来的」
            log.warn("注册时唯一索引冲突（并发注册同一账号）, username={}", username);
            throw new BusinessException(ResultCode.USERNAME_TAKEN, "该账号已被注册，换一个试试");
        }

        // insert 之后 id 已经被 useGeneratedKeys 写回对象里了
        Long newId = member.getId();
        log.info("会员注册成功, id={}, username={}", newId, username);

        String token = jwtUtil.generate(newId, username, UserType.MEMBER);

        // 注意 nickname：用户没填时是 null，前端应该显示账号而不是空白。
        // 这个「用账号兜底」的逻辑放在前端（store 的 displayName 计算属性），
        // 不在这里补 —— 服务端应该如实返回数据，不做展示层的决策
        return LoginVO.builder()
                .token(token)
                .id(newId)
                .username(username)
                .nickname(member.getNickname())
                .build();
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        String username = dto.getUsername().trim();

        Member member = memberMapper.selectByUsername(username);

        // ------------------------------------------------------------------
        // ★ 和管理端登录完全相同的防用户名枚举处理
        // ------------------------------------------------------------------
        // 用户端的注册接口是公开的，所以「哪些账号存在」某种程度上
        // 用户自己就能试出来（注册时提示「已被占用」）。
        // 那这里的防枚举还有意义吗？
        //
        // 有。注册接口的信息是「你主动试的这个账号存不存在」——
        // 攻击者一次只能确认一个，而且有频率限制的空间。
        // 登录接口如果不防枚举，攻击者可以拿一份几百万条的常见密码表
        // 批量探测，那是完全不同的量级。
        //
        // 而且更重要的一点：注册接口只能确认「存在」，
        // 登录接口不防的话能确认「存在 + 密码对不对」，
        // 后者直接就是账号被盗了。
        // ------------------------------------------------------------------
        if (member == null || !passwordEncoder.matches(dto.getPassword(), member.getPassword())) {
            log.warn("用户端登录失败, username={}, 原因={}",
                    username, member == null ? "账号不存在" : "密码错误");
            throw new BusinessException(ResultCode.LOGIN_FAILED, "账号或密码错误");
        }

        // 先验密码再看状态：避免「拿错误密码试探」泄漏出这个账号存在且被禁用
        if (member.getStatus() != null && member.getStatus() == 0) {
            log.warn("已禁用的会员尝试登录, username={}", username);
            throw new BusinessException(ResultCode.LOGIN_FAILED, "该账号已被禁用，请联系客服");
        }

        String token = jwtUtil.generate(member.getId(), member.getUsername(), UserType.MEMBER);
        log.info("用户端登录成功, id={}, username={}", member.getId(), member.getUsername());

        return LoginVO.builder()
                .token(token)
                .id(member.getId())
                .username(member.getUsername())
                .nickname(member.getNickname())
                .build();
    }

    @Override
    public MemberInfoVO currentMember() {
        LoginUser loginUser = UserContext.require();

        // 拿 token 里的 id 查库，而不是直接信 token 里的用户信息 ——
        // 原因和管理端一样：token 是「签发那一刻的快照」，会过期。
        // 详见 AdminAuthServiceImpl.currentAdmin 的注释
        Member member = memberMapper.selectById(loginUser.id());
        if (member == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号不存在，请重新登录");
        }
        if (member.getStatus() != null && member.getStatus() == 0) {
            // 被禁用的会员，手里有过期前的 token 也立刻失效。
            // 这个检查放在这里而不是拦截器里 —— 拦截器每个请求都查库的话，
            // JWT 无状态的优势就没了
            throw new BusinessException(ResultCode.UNAUTHORIZED, "该账号已被禁用，请联系客服");
        }

        return MemberInfoVO.builder()
                .id(member.getId())
                .username(member.getUsername())
                .nickname(member.getNickname())
                .phone(member.getPhone())
                .build();
    }

    /**
     * 把空字符串和纯空白规范化成 {@code null}。
     *
     * <p>前端表单没填的选填项传过来是 {@code ""}，直接入库会让
     * 「手机号为空」这件事在数据库里有两副面孔（{@code ''} 和 {@code NULL}），
     * 后续所有查询都得写 {@code WHERE phone != '' AND phone IS NOT NULL}。
     * 在入口处统一成 NULL，后面就只需要判断 {@code IS NULL} 一种情况。
     *
     * <p>注意这里也做了 {@code trim()}：用户复制粘贴手机号时
     * 很容易带上首尾空格，而 {@code @Pattern} 的正则因为加了 {@code ^$}
     * 分支，会拒绝 {@code " 13800138000"}（有空格，不匹配）。
     * 那是正确的拒绝 —— 但既然我们自己能修好，就没必要让用户吃这个报错。
     *
     * <p>⚠️ 这里【没有】对 username 做同样的处理。账号的合法性
     * 必须由 {@code @Pattern} 严格把关（它不允许空格），
     * 悄悄 trim 掉反而会掩盖问题：用户以为自己注册的是 {@code " abc"}，
     * 实际存的是 {@code "abc"}，下次登录时就懵了。
     * 密码同理 —— <b>密码更要原样对待，绝不能 trim</b>，
     * 空格是密码的合法组成部分，你替用户删掉就是在改他的密码。
     */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
