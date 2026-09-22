package com.example.mall.interceptor;

import com.example.mall.common.UserType;
import com.example.mall.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 用户端（会员）登录拦截器。
 *
 * <p>和管理端拦截器唯一的区别就是要求的身份类型是 {@code MEMBER}。
 *
 * <p><b>★ 这里也是里程碑 4 埋的那个坑真正被验证的地方。</b>
 *
 * <p>{@code admin_user.id} 和 {@code member.id} 都从 1 开始自增，
 * 所以库里必然同时存在两个 id=1 的记录。
 * 里程碑 4 只做了「管理端拒绝会员 token」这半边；
 * 现在这半边补上「用户端拒绝管理员 token」，
 * 两个方向都堵住之后，{@code type} 声明的价值才是完整的。
 *
 * <p>顺带一提：用户端注册接口是<b>任何人</b>都能调的，
 * 所以攻击者可以自己注册一个会员账号拿到合法的 MEMBER token。
 * 这个拦截器让他拿到的 token 恰好只能干「会员该干的事」——
 * 这就是为什么类型检查必须在<b>服务端</b>做，
 * 而不是靠前端把不该显示的菜单藏起来。
 */
@Component
public class MemberAuthInterceptor extends AbstractAuthInterceptor {

    public MemberAuthInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        super(jwtUtil, objectMapper);
    }

    @Override
    protected String requiredType() {
        return UserType.MEMBER;
    }

    @Override
    protected String sideName() {
        return "用户端";
    }
}
