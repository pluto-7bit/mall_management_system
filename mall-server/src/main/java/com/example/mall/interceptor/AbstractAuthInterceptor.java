package com.example.mall.interceptor;

import com.example.mall.common.LoginUser;
import com.example.mall.common.Result;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器的公共骨架 —— 管理端和用户端共用。
 *
 * <h3>整个鉴权流程</h3>
 * <pre>
 *   请求进来
 *     ↓
 *   取 Authorization 请求头，格式必须是 "Bearer &lt;token&gt;"
 *     ↓ 没有 → 401
 *   用密钥验签名 + 验过期
 *     ↓ 不合法 → 401
 *   ★ 取出 type 声明，必须等于子类要求的类型
 *     ↓ 不是 → 401
 *   把用户信息塞进 UserContext（供业务代码取用）
 *     ↓
 *   放行 → Controller
 *     ↓
 *   请求结束，清理 UserContext
 * </pre>
 *
 * <h3>★ 为什么抽成抽象类，而不是写两份</h3>
 *
 * <p>管理端和用户端的拦截器，除了「要求哪一种身份」之外，
 * 每一步都完全一样：取头、验签、判类型、写上下文、清理。
 * 直接复制的话会得到两个 200 行、95% 相同的文件 ——
 * 那种重复的危险不在于「多打了一遍字」，而在于<b>以后会改歪</b>：
 * 哪天发现了一个安全漏洞（比如漏了某个异常分支），
 * 修了一个文件却忘了另一个，而漏掉的那边不会有任何报错。
 *
 * <p>这里用的是<b>模板方法模式</b>：父类把流程固定下来，
 * 把「哪一步需要变化」抽成一个抽象方法交给子类填。
 * 子类因此只剩下一个方法、三行代码，一眼就能看完 ——
 * 而这正是我们想要的：<b>要看懂管理端拦截器做了什么，
 * 不需要读 200 行，只需要读那三行「它要求 ADMIN」。</b>
 *
 * <h3>★ 只做「认证」，不做「授权」</h3>
 *
 * <p>它只回答「你是谁、你有没有登录」。至于「你能不能删这个商品」
 * 「你能不能取消这个订单」，那是业务规则，属于 Service 的职责。
 * <b>不要在没有区分角色/归属的需求时提前设计权限系统</b>，
 * 那会带来一堆没人用的表和判断。
 *
 * <h3>★ 订单是那个例外 —— 而且它有两种答案（里程碑 10 之后）</h3>
 *
 * <p>「能不能操作这笔订单」<b>取决于数据本身，而不是路径</b>，
 * 所以它必须在 Service 里判断。里程碑 9 时这里只有一种情况：
 * <b>会员只能操作自己的订单</b>（SQL 里的 {@code AND member_id = #{memberId}}）。
 *
 * <p>里程碑 10 加了管理端之后，同一个问题有了<b>两种正确答案</b>：
 * <pre>
 *   会员   →  看【数据归属】：这笔订单的 member_id 是不是我
 *             判据在 Service / SQL 里，因为路径上看不出来
 *   管理员 →  看【身份】：只要你是管理员，操作谁的订单都合法
 *             判据就是本类拦截的路径前缀，因为发货本来就是在操作别人的订单
 * </pre>
 * <b>「管理员操作别人的订单」不是这一层的漏洞，是它的职责。</b>
 *
 * <p>⚠️ 注意两者<b>都不是靠路径判断「这笔订单是不是你的」</b>——
 * 路径只能回答「你是不是管理员」这个问题。
 * 把数据归属也做成路径规则（比如 {@code /api/admin/orders/**} 就免检）
 * 看起来更省事，但那样一旦某个用户端接口被挂错了树，
 * 它就会静默地失去归属校验。<b>数据归属永远只能看数据。</b>
 *
 * <p>（本项目<b>没有</b>管理员权限模型：任何登录的管理员都能发货、
 * 能看全部订单。这是明知的简化，详见 {@code OrderAdminMapper} 的类注释。）
 */
@Slf4j
public abstract class AbstractAuthInterceptor implements HandlerInterceptor {

    /** Authorization 请求头的固定前缀。注意 Bearer 后面有个空格 */
    private static final String BEARER_PREFIX = "Bearer ";

    protected final JwtUtil jwtUtil;
    protected final ObjectMapper objectMapper;

    /**
     * 注意这里<b>不能用 {@code @RequiredArgsConstructor}</b>：
     * Lombok 生成的构造器不会调用父类构造器，
     * 在继承体系里必须手写构造函数显式 {@code super(...)}。
     * 这是 Lombok 一个很容易踩的坑 —— 编译能过，但父类字段会是 null。
     */
    protected AbstractAuthInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    /**
     * 本拦截器要求 JWT 里的 type 声明等于什么。
     *
     * <p>返回 {@link com.example.mall.common.UserType#ADMIN} 或
     * {@link com.example.mall.common.UserType#MEMBER}。
     */
    protected abstract String requiredType();

    /** 端口的名字，只用在日志里。比如「管理端」「用户端」 */
    protected abstract String sideName();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        // ------------------------------------------------------------------
        // 1. 只拦截 Controller 方法
        // ------------------------------------------------------------------
        // 对于静态资源、CORS 预检请求（OPTIONS）等，handler 不是 HandlerMethod。
        // 直接放行 —— 这些请求本来也不该带 token。
        // 不加这个判断的话，浏览器发起的 CORS 预检请求会被我们判成「未登录」，
        // 导致跨域请求全部失败，而报错信息完全指不到这里，非常难查。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // ------------------------------------------------------------------
        // 2. 取 token
        // ------------------------------------------------------------------
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // 这里刻意不区分「没带」和「格式不对」，统一提示「请先登录」。
            // 对用户来说要做的事情是一样的（去登录），
            // 区分开只会增加前端要处理的错误分支
            return reject(response, "请先登录");
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return reject(response, "请先登录");
        }

        // ------------------------------------------------------------------
        // 3. 验签 + 验过期
        // ------------------------------------------------------------------
        LoginUser user;
        try {
            Claims claims = jwtUtil.parse(token);
            user = jwtUtil.toLoginUser(claims);
            if (user == null) {
                // 没有 type 声明。可能是很老的 token，也可能是伪造的。
                // 安全相关的异常情况一律按「无效」处理，不做任何兼容
                return reject(response, "登录凭证无效，请重新登录");
            }
        } catch (ExpiredJwtException e) {
            // 过期是最常见的情况（用户开了个页面放了两小时），
            // 单独给一句更明确的提示，用户知道「重新登录一下就好」
            return reject(response, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException 覆盖签名错误、格式非法、alg=none 等；
            // IllegalArgumentException 是 Base64 解码失败时抛的。
            // ★ 这两种情况的详细原因【只记日志，不返回给前端】——
            //   告诉攻击者「你的签名算错了」会帮他调试伪造脚本
            log.warn("JWT 校验失败: {}", e.getMessage());
            return reject(response, "登录凭证无效，请重新登录");
        }

        // ------------------------------------------------------------------
        // 4. ★ 检查身份类型
        // ------------------------------------------------------------------
        // 这一步绝不能省。admin_user.id 和 member.id 都是从 1 开始自增的，
        // 两个 id 都为 1 的记录必然同时存在。
        // 只看 id 的话，member.id=1 的会员就能冒充 admin_user.id=1 的管理员。
        //
        // ★ 注意这里是【双向】拦截的：
        //   会员 token 访问 /api/admin/** 会被这里挡住；
        //   管理员 token 访问 /api/shop/** 同样会被挡住。
        //   后者看起来无害（管理员还不能买东西了？），但放任它会带来两个问题：
        //     1. 下单时用的 member_id 会是 admin 表里的 id，
        //        和 member 表里的某个 id 撞号，订单归属就乱了
        //     2. 「管理员能不能给自己下单」变成一个没人想过的问题，
        //        而这类模糊地带正是将来出漏洞的地方
        //   真想支持「管理员也是顾客」，正确做法是给他也开一个会员账号，
        //   而不是让一个 token 同时扮演两个身份。
        //
        // 提示语和「凭证无效」保持一致，不告诉攻击者「你的类型不对」——
        // 那等于确认了他的 token 是有效的，只是类型错了
        if (!requiredType().equals(user.type())) {
            log.warn("检测到非 {} 身份访问{}接口: 实际 type={}, id={}",
                    requiredType(), sideName(), user.type(), user.id());
            return reject(response, "登录凭证无效，请重新登录");
        }

        // ------------------------------------------------------------------
        // 5. 放进上下文，供业务代码使用
        // ------------------------------------------------------------------
        UserContext.set(user);
        log.debug("{}鉴权通过: id={}, username={}", sideName(), user.id(), user.username());
        return true;
    }

    /**
     * 请求处理完之后调用，<b>无论成功还是抛异常都会执行</b>。
     *
     * <p>★ 这里的 {@code UserContext.clear()} 是<b>必须的</b>。
     *
     * <p>Tomcat 用线程池处理请求，一个线程处理完这个请求后不会销毁，
     * 而是被复用去处理下一个请求。如果不清掉 ThreadLocal，
     * 下一个请求（哪怕它没带 token、本该是匿名的）就会读到上一个
     * 请求留下的用户身份 —— 等于凭空获得了权限。
     *
     * <p>这个 bug 的特征是「依赖线程调度，偶发，重启后消失」，
     * 是最难排查的那一类。而规避它的成本只有一行代码。
     *
     * <p>放在 afterCompletion 而不是 postHandle，是因为 postHandle
     * 在 Controller 抛异常时<b>不会执行</b>，而 afterCompletion 一定会。
     * 清理动作必须放在一定会执行的地方。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 统一返回 401。
     *
     * <p><b>为什么这里用真正的 HTTP 401，而不是像业务异常那样返回 200 + code？</b>
     *
     * <p>回顾一下项目的约定：
     * <pre>
     *   业务结果（库存不足、重名）→ HTTP 200 + body.code = 1xxx
     *   协议/认证错误（401/404/400）→ 真正的 HTTP 状态码
     * </pre>
     *
     * <p>「未登录」属于后者，理由有两个：
     * <ol>
     *   <li>HTTP 有专门的语义表达它，用 401 是标准做法，
     *       浏览器、网关、监控工具都能直接理解</li>
     *   <li>前端需要一个<b>明确的信号</b>来决定「跳转到登录页」。
     *       用状态码区分最可靠，不用去解析 body 里的业务码
     *       （万一以后新增了别的 1xxx 错误码，前端逻辑就要跟着改）</li>
     * </ol>
     *
     * <p>注意这里<b>手动写 JSON 而不是抛异常</b>：拦截器在
     * DispatcherServlet 的调用链上，此时抛出的异常<b>不会</b>被
     * {@code @RestControllerAdvice} 捕获（那个只处理 Controller 内部抛出的异常），
     * 而是会走到容器默认的错误页，前端拿到一坨 HTML，没法用。
     */
    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(Result.error(ResultCode.UNAUTHORIZED, message)));
        return false;
    }
}
