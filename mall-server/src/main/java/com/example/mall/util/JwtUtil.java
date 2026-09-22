package com.example.mall.util;

import com.example.mall.common.LoginUser;
import com.example.mall.common.UserType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 的生成与解析。
 *
 * <h3>JWT 到底是什么</h3>
 *
 * <p>它就是一串用两个点分隔的字符串，形如：
 * <pre>
 *   eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiIxIiwidHlwZSI6IkFETUlOIn0 . dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
 *   └──── 头部 ────┘   └──────── 载荷 ────────┘   └────────── 签名 ──────────┘
 * </pre>
 *
 * <p>三段都是 Base64URL 编码。前两段是<b>明文</b>，任何人复制到
 * <a href="https://jwt.io">jwt.io</a> 都能看到里面写了什么。
 * <b>所以绝对不要往 JWT 里放密码、身份证号这类敏感信息。</b>
 * 第三段签名保证的是「内容没被篡改」，不是「内容看不见」。
 *
 * <h3>为什么服务端不用存 token（无状态）</h3>
 *
 * <p>传统 Session 方案是：登录成功后服务端生成一个 sessionId，
 * 把用户信息存在服务端内存/Redis 里，客户端只拿一个 id。
 * 验证时要查一次存储 —— 这叫「有状态」。
 *
 * <p>JWT 是：所有信息都在 token 自己身上，服务端只要用密钥验一下签名
 * 就知道它是不是自己签发的、有没有过期。<b>不用查任何存储。</b>
 *
 * <p>好处是水平扩展容易（多台服务器不需要共享 session），
 * 代价是<b>无法主动让一个 token 失效</b> ——
 * 这就是为什么「退出登录」只能由前端删掉本地 token，
 * 真正的失效要等它自己过期。想做「立刻踢下线」必须引入黑名单，
 * 那又变回有状态了。工程上没有免费的午餐。
 */
@Slf4j
@Component
public class JwtUtil {

    /** 自定义声明的 key，抽成常量避免两边写岔 */
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_TYPE = "type";

    private final SecretKey secretKey;
    private final long expireMillis;

    /**
     * 构造器注入配置。
     *
     * <p>{@code @Value("${mall.jwt.secret}")} 从 application.yml 里取值。
     * 配置写的是 {@code ${MALL_JWT_SECRET:默认值}}，
     * 所以有环境变量就用环境变量，没有就用默认值。
     *
     * <p><b>密钥在这里就被转成 {@link SecretKey} 而不是每次用的时候转</b>，
     * 是因为 {@code Keys.hmacShaKeyFor()} 会校验密钥长度 ——
     * 长度不够会抛 {@code WeakKeyException}。
     * 放在构造器里意味着<b>启动时就会失败</b>，而不是等到第一个用户登录
     * 才在运行时炸掉。这叫「快速失败」，配置错误越早暴露越好。
     */
    public JwtUtil(@Value("${mall.jwt.secret}") String secret,
                   @Value("${mall.jwt.expire-minutes}") long expireMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60 * 1000;
    }

    /**
     * 签发一个 token。
     *
     * <p>几个标准声明的含义（JWT 规范里都是注册过的名字，别乱写）：
     * <ul>
     *   <li>{@code sub} (subject)  —— 这个 token 是关于谁的，这里放用户 id</li>
     *   <li>{@code iat} (issued at) —— 签发时间</li>
     *   <li>{@code exp} (expiration) —— 过期时间，<b>由 jjwt 自动校验</b></li>
     * </ul>
     * {@code username} 和 {@code type} 是我们自己加的<b>自定义声明</b>，
     * JWT 规范允许放任意键值对。
     *
     * @param userId   用户 id
     * @param username 用户名
     * @param type     {@link UserType#ADMIN} 或 {@link UserType#MEMBER}
     */
    public String generate(Long userId, String username, String type) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMillis);

        return Jwts.builder()
                // sub 里放 id。JWT 规范要求它必须是字符串，所以这里转一下
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(expiry)
                // signWith 会用密钥对「头部.载荷」算一个 HMAC-SHA256 签名。
                // ★ 没有这一步的 token 就是一段谁都能改的 Base64，
                //   任何人都能把自己改成管理员
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验 token。
     *
     * <p>「校验」包括三件事，任何一件不过都会抛异常：
     * <ol>
     *   <li>签名对不对（能不能用我们的密钥验通）—— 防篡改</li>
     *   <li>过期了没有 —— 防重放</li>
     *   <li>格式是不是合法的 JWT</li>
     * </ol>
     *
     * <p><b>注意这个方法会抛异常，调用方必须处理</b>。
     * 不打算在这里 try-catch 吐 null，是因为「过期」和「格式错」
     * 需要给用户不同的提示（「登录已过期」vs「凭证无效」），
     * 吞掉异常就没法区分了。让异常往上抛，由拦截器决定怎么回应。
     *
     * @throws io.jsonwebtoken.ExpiredJwtException    token 过期
     * @throws io.jsonwebtoken.JwtException           签名不对 / 格式非法
     */
    public Claims parse(String token) {
        return Jwts.parser()
                // 0.12.x 的新 API：verifyWith 换掉了老版本的 setSigningKey
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 把解析出来的声明转成 {@link LoginUser}。
     *
     * <p>单独抽一个方法，是为了让「Claims → LoginUser」这个转换
     * 只写一遍。拦截器会用它，以后别的地方（比如 WebSocket 握手、
     * 定时任务里模拟用户）也可能要，避免各自解析、各自写错字段名。
     *
     * <p><b>注意 type 缺失时返回 null 而不是默认成 ADMIN</b>：
     * 老版本签发的、没有 type 声明的 token 必须被当成无效，
     * 绝不能「宽容」地放行。安全相关的默认值永远要选最严的那个。
     */
    public LoginUser toLoginUser(Claims claims) {
        String type = claims.get(CLAIM_TYPE, String.class);
        if (type == null) {
            return null;
        }
        return new LoginUser(
                Long.valueOf(claims.getSubject()),
                claims.get(CLAIM_USERNAME, String.class),
                type);
    }
}
