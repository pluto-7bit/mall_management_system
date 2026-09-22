package com.example.mall.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 当前登录管理员的公开信息。
 *
 * <p>和 {@link LoginVO} 的区别是它<b>不含 token</b>，
 * 用于「前端刷新页面后，用已存的 token 换回用户信息」这个场景：
 * localStorage 里只有 token 和用户名，昵称这类信息要么一起存
 * （会过期、可能不一致），要么向服务端要一次（可靠）。
 * 这里选后者。
 */
@Data
@Builder
public class AdminInfoVO {

    private Long id;

    private String username;

    private String nickname;
}
