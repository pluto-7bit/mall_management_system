package com.example.mall.controller.admin;

import com.example.mall.common.Result;
import com.example.mall.dto.LoginDTO;
import com.example.mall.service.AdminAuthService;
import com.example.mall.vo.AdminInfoVO;
import com.example.mall.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端认证接口。
 *
 * <p>{@code /api/admin/auth/login} 是整个 {@code /api/admin/**} 下
 * <b>唯一不需要登录</b>的接口 —— 见
 * {@link com.example.mall.config.WebMvcConfig} 里的 excludePathPatterns。
 *
 * <p><b>★ 类名从 {@code AuthController} 改成了 {@code AdminAuthController}。</b>
 * 注意包名已经是 {@code controller.admin} 了，所以从「不歧义」的角度说
 * 原来的名字也够用。这么改是为了和用户端的
 * {@link com.example.mall.controller.shop.MemberAuthController} <b>对齐</b>：
 * 两个类做的是同一件事，名字就该长成同一个形状，
 * 这样在 IDE 里搜索 {@code AuthController} 会同时看到两个，
 * 而不会出现「搜到一个就以为找全了」的情况。
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    /**
     * 登录。
     *
     * <p>{@code POST /api/admin/auth/login}
     * <pre>
     *   { "username": "admin", "password": "123456" }
     * </pre>
     *
     * <p><b>为什么登录用 POST 而不是 GET？</b>
     * 因为密码要放在请求体里。GET 请求的参数会出现在
     * URL、浏览器历史、Nginx 访问日志、Referer 头里 ——
     * 密码用 GET 传等于把它写进了好几个地方的日志文件。
     * 判断标准很简单：<b>请求会改变服务端状态、或者携带敏感数据，
     * 就用 POST</b>（严格来说登录取的是 token，不改变状态，
     * 但「敏感数据不放 URL」这条更重要）。
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(adminAuthService.login(dto));
    }

    /**
     * 查询当前登录管理员信息。
     *
     * <p>{@code GET /api/admin/auth/me}
     *
     * <p>前端刷新页面后调它，用 localStorage 里的 token 换回用户信息。
     * 这个接口<b>没有</b>被排除在拦截器之外，所以必须先登录才能访问 ——
     * 而它恰好也是前端检验「token 还有没有效」的手段：
     * 返回 401 就说明 token 过期了，该跳登录页了。
     */
    @GetMapping("/me")
    public Result<AdminInfoVO> me() {
        return Result.success(adminAuthService.currentAdmin());
    }

    /**
     * <b>这里刻意没有 logout 接口。</b>
     *
     * <p>因为 JWT 是无状态的，服务端没有「登录状态」可以清除。
     * 真正的退出登录动作是<b>前端把 localStorage 里的 token 删掉</b>，
     * 一个 HTTP 请求都不需要发。
     *
     * <p>硬要写一个 logout 接口的话，它只能返回一句
     * 「好的，请你自己把 token 删掉」—— 服务端什么也做不了。
     * 写这种「假装在工作」的接口，反而会让后来的人以为
     * 服务端真的记录了登录状态，产生误解。
     *
     * <p>什么时候才需要真的 logout 接口？
     * 当引入了 token 黑名单（把作废的 token 存进 Redis，
     * 每次请求查一下）之后。那时 logout 会做一件实事：
     * 把当前 token 加进黑名单。<b>但那是拿 JWT 的无状态换来的，
     * 属于安全等级要求的架构升级，不是随手就能加的。</b>
     */
}
