package com.example.mall.interceptor;

import com.example.mall.common.UserType;
import com.example.mall.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 管理端登录拦截器。
 *
 * <p>整个鉴权流程都在父类 {@link AbstractAuthInterceptor} 里，
 * 这里只声明「我要求 ADMIN 身份」。
 *
 * <p><b>★ 一个 200 行的类变成 20 行，是重构做对了的标志。</b>
 * 想知道管理端接口受什么保护，读这个文件就够了，不用再去啃一遍
 * token 解析和异常分支 —— 那些是两端共用的、已经验证过的逻辑。
 */
@Component
public class AdminAuthInterceptor extends AbstractAuthInterceptor {

    public AdminAuthInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        super(jwtUtil, objectMapper);
    }

    @Override
    protected String requiredType() {
        // 用常量而不是字面量 "ADMIN"：写错了编译期就会报错。
        // 而且这个常量的定义处（UserType）有一段注释专门讲
        // admin.id 和 member.id 撞号的问题，顺着就能找到
        return UserType.ADMIN;
    }

    @Override
    protected String sideName() {
        return "管理端";
    }
}
