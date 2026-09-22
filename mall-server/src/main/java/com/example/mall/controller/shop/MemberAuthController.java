package com.example.mall.controller.shop;

import com.example.mall.common.Result;
import com.example.mall.dto.LoginDTO;
import com.example.mall.dto.MemberRegisterDTO;
import com.example.mall.service.MemberAuthService;
import com.example.mall.vo.LoginVO;
import com.example.mall.vo.MemberInfoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端认证接口。
 *
 * <p>{@code /api/shop/auth/register} 和 {@code /api/shop/auth/login}
 * 是整个 {@code /api/shop/**} 下<b>仅有的两个不需要登录</b>的接口 ——
 * 见 {@link com.example.mall.config.WebMvcConfig} 里的 excludePathPatterns。
 *
 * <p><b>★ 这个 Controller 和三件事有关，但一件都不在这里做：</b>
 * <pre>
 *   参数校验      → DTO 上的注解 + @Valid（Spring 自动执行）
 *   业务规则      → MemberAuthService（查重、验密码、签 token）
 *   登录状态检查  → MemberAuthInterceptor（在进入这个方法【之前】就完成了）
 * </pre>
 *
 * <p>所以这些方法都只有一行：调 Service，包成 Result 返回。
 * 这是<b>好的</b> Controller 该有的样子 —— 它的职责就是「把 HTTP 世界
 * 翻译成 Java 调用」，任何多出来的 if/else 都意味着有代码放错了层。
 *
 * <p>反过来说，如果一个 Controller 方法里出现了「查数据库」「判断重名」
 * 「拼 SQL 条件」，那说明业务逻辑泄漏到了这一层。
 * 后果是这块逻辑没法被别的地方复用、没法加事务、也没法单独测试。
 */
@RestController
@RequestMapping("/api/shop/auth")
@RequiredArgsConstructor
public class MemberAuthController {

    private final MemberAuthService memberAuthService;

    /**
     * 注册。
     *
     * <p>{@code POST /api/shop/auth/register}
     * <pre>
     *   { "username": "xiaoming", "password": "123456",
     *     "nickname": "小明", "phone": "13800138000" }
     * </pre>
     *
     * <p>返回的 {@link LoginVO} 里带着 token，<b>注册完就是已登录状态</b>，
     * 前端不需要再调一次登录接口。理由见
     * {@link com.example.mall.service.MemberAuthService#register}。
     *
     * <p>⚠️ 注意这个接口是<b>匿名可调</b>的，所以它是整个系统里
     * 唯一一个「陌生人能直接往数据库写数据」的入口。
     * 真实项目在这里至少要加：
     * <ul>
     *   <li><b>图形/行为验证码</b> —— 否则脚本可以每秒注册几百个账号，
     *       把你的会员表灌满垃圾数据（这叫「注册机」）</li>
     *   <li><b>IP 限流</b> —— 同一个 IP 每小时最多注册几个</li>
     *   <li><b>手机号/邮箱验证</b> —— 确认这个人是真的，也留一条找回密码的路</li>
     * </ul>
     * 本学习项目都不做，但<b>要知道这里是敞开的</b>，
     * 而不是以为「反正注册接口就是这样」。
     */
    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody MemberRegisterDTO dto) {
        return Result.success(memberAuthService.register(dto));
    }

    /**
     * 登录。
     *
     * <p>{@code POST /api/shop/auth/login}
     *
     * <p>和管理端登录用的是同一个 {@link LoginDTO}。
     * 理由见 {@link com.example.mall.service.MemberAuthService#login} ——
     * 它就是一个「账号 + 密码」的通用载体，没有管理端特有的字段。
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(memberAuthService.login(dto));
    }

    /**
     * 查询当前登录会员。
     *
     * <p>{@code GET /api/shop/auth/me}
     *
     * <p>和前两个不同，这个接口<b>没有</b>被排除在拦截器之外 ——
     * 它必须先登录才能访问。前端刷新页面后调它，
     * 既恢复用户信息，也顺便验证了 token 还有没有效。
     */
    @GetMapping("/me")
    public Result<MemberInfoVO> me() {
        return Result.success(memberAuthService.currentMember());
    }

    // 这里同样没有 logout 接口。理由和管理端完全一样：
    // JWT 是无状态的，服务端没有「登录状态」可以清除，
    // 退出登录 = 前端把 localStorage 里的 token 删掉。
    // 详见 controller/admin/AuthController 的注释。
}
