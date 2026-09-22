package com.example.mall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密器配置。
 *
 * <p><b>为什么不直接在 AuthServiceImpl 里 new 一个？</b>
 *
 * <p>因为服务端不只管理端登录这一处要用密码加密：
 * 里程碑 5 的会员注册要加密，以后「修改密码」也要用。
 * 如果每个地方各自 {@code new BCryptPasswordEncoder()}，会有两个问题：
 * <ol>
 *   <li>哪天真要换算法（比如升级到 Argon2），得满项目搜 {@code new BCrypt}</li>
 *   <li>更隐蔽的：代价因子（strength）如果各写各的，
 *       有的地方是 10、有的地方是 12，就会出现
 *       「同样的密码在不同入口加密结果强度不一致」的诡异情况</li>
 * </ol>
 *
 * <p>注册成 Bean 之后，需要的地方注入 {@link PasswordEncoder} 接口即可，
 * 具体用哪个实现只有这一个文件知道。
 *
 * <p><b>注入的是接口（PasswordEncoder）而不是实现类（BCryptPasswordEncoder）</b>，
 * 是「依赖倒置」的具体体现：业务代码不关心用的什么算法，
 * 只关心「能把明文变成密文、能比对」。想换算法时只改这里的返回值。
 *
 * <p><b>关于默认的代价因子</b>：不传参数时 BCrypt 用 10，即 2^10 = 1024 轮哈希。
 * 单次约 50-100 毫秒 —— 登录时用户完全无感，
 * 但攻击者每秒只能试十几次，暴力破解变得不现实。
 * 调高会更安全但也更慢，一般按「单次耗时 100ms 左右」来定。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
