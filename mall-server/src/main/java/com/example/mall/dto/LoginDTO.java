package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求参数。
 *
 * <p>管理端和用户端可以共用这一个 DTO —— 登录要的东西两边完全一样：
 * 账号 + 密码。等哪一边多出需求了（比如用户端要「记住我」、
 * 管理端要「验证码」）再拆开，现在拆只是多一个重复的文件。
 *
 * <p><b>注意这里没有 {@code @Size(min = 8)} 这类密码强度校验</b>。
 * 密码强度是<b>注册</b>时该管的事，登录时不该管：
 * 老用户的密码可能不符合新规则，登录时按新规则拦住他们，
 * 等于把人锁在门外。登录只校验「格式上能受理」，
 * 对不对由密码比对说了算。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "请输入账号")
    @Size(max = 50, message = "账号长度不能超过 50 个字符")
    private String username;

    /**
     * 密码。
     *
     * <p>上限设为 100 而不是 72 —— 因为 BCrypt 虽然只用前 72 字节，
     * 但超长的密码不该被拒绝，只是超出部分无效而已。
     * 加这个上限纯粹是为了防止有人传一个几 MB 的字符串过来
     * 白白消耗服务端资源。
     */
    @NotBlank(message = "请输入密码")
    @Size(max = 100, message = "密码长度不能超过 100 个字符")
    private String password;
}
