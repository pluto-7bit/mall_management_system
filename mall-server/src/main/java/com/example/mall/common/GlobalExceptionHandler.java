package com.example.mall.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
// ⚠️ 是 multipart.【support】，不是 multipart —— 少一段 IDE 补全里找不到
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p><b>{@code @RestControllerAdvice} 是什么？</b>
 * 它会「织入」所有 Controller，把里面抛出的异常统统拦截下来交给下面这些方法处理。
 * 没有它的话，Service 里 {@code throw new BusinessException("商品不存在")}
 * 会直接变成 500 错误页 + 一坨堆栈，前端拿不到可读的提示。
 *
 * <p><b>它最大的价值是「消除重复」</b>：有了它，Controller 里再也不用写
 * <pre>
 *   try {
 *       ...
 *   } catch (Exception e) {
 *       return Result.error(e.getMessage());
 *   }
 * </pre>
 * 这种模板代码。业务代码只写正常流程，异常自然往上抛，最终在这里统一收口。
 *
 * <p><b>★ HTTP 状态码怎么定？—— 业务结果看 body.code，协议错误用真状态码</b>
 *
 * <p>本项目用响应体里的 {@code code} 字段表达<b>业务</b>成败（见 {@link Result} 的注释），
 * 这样前端只需处理一种响应格式。但这条规则只覆盖业务层面：
 * <pre>
 *   业务结果（库存不足、重名、无权限、图片超过限额）→ HTTP 200 + body.code
 *   协议错误（路径错、报文错、方法错）             → 真正的 HTTP 状态码
 * </pre>
 * 判据只有一个，完整原文在 {@code handleNoResourceFound} 的注释里：
 * <b>「这是调用方把请求本身写错了，还是他提了一个不合规但格式正确的内容？」</b>
 *
 * <p><b>协议错误在本类里的完整清单</b>（每一处都有自己的论证，这里只做索引）：
 * <pre>
 *   404  handleNoResourceFound            路径根本没有对应的接口
 *   405  handleMethodNotSupported         路径有，方法不对（★ 额外带 Allow 头）
 *   400  handleNotReadable                请求体不是合法 JSON
 *   400  handleTypeMismatch               路径变量类型不对（/products/abc）
 *   400  handleValidationException        查询参数类型不对（?pageNum=abc）那一支
 *   400  handleMultipart                  压根不是 multipart 请求
 *   400  handleMissingServletRequestPart  是 multipart，但没有那个 part
 *   415  handleMediaTypeNotSupported      请求体的 Content-Type 读不了（★ 带 Accept 头）
 *   406  handleMediaTypeNotAcceptable     客户端不接受我们能产出的格式
 * </pre>
 * 唯一「业务码同时映射成 HTTP 状态码」的是 401，理由见 {@code handleBusinessException}。
 *
 * <p><b>★ 一个例外：406 那个处理器不返回 {@link Result}。</b>
 * 它是全类唯一一个空 body 的响应，而且<b>必须</b>是空的 ——
 * 给它塞一个 JSON 的真实后果是 HTTP 500 + Tomcat 错误页。
 * 完整推演写在该方法自己的注释里，★ 看到它「不合群」时先去读那段，不要顺手补齐。
 *
 * <hr>
 *
 * <p><b>★★ 交接给下一次审计（新增接口时从这里接着看，不必从头再查一遍）</b>
 *
 * <p>判断一个异常该不该单独写处理器，问三个问题：
 * <ol>
 *   <li><b>它今天真的可达吗？</b> —— 给一条走不到的路写代码，
 *       和给一个没有读者的字段加一列，是同一件事。</li>
 *   <li><b>它是协议错误，还是内容不合规？</b> —— 决定用真状态码还是 200 + code（判据见上）。</li>
 *   <li><b>它掉进兜底之后，未认证的请求能不能刷爆 error 日志？</b> ——
 *       这是本类存在的第二大理由（第一是语义正确），详见 {@code handleTypeMismatch}。</li>
 * </ol>
 *
 * <p>里程碑 13 逐个类型排查过、判定<b>今天不可达</b>的，
 * 连同「什么条件下它会变得可达」一起记在这里：
 * <pre>
 *   MissingServletRequestParameterException
 *       全项目只有 2 个 @RequestParam，且都是 MultipartFile 参数 ——
 *       对 MultipartFile 走的是另一条分支（见 handleMissingServletRequestPart）。
 *       ⚠️ 哪天加了非 MultipartFile 的 @RequestParam，它就可达了。
 *   ConstraintViolationException
 *       全后端 @Validated 零命中，只在 @RequestBody 上用 @Valid。
 *       ⚠️ 哪天给类或方法加上 @Validated，它就可达了。
 *   NoHandlerFoundException
 *       没配 throw-exception-if-no-handler-found，Boot 默认抛 NoResourceFoundException。
 *       ⚠️ 在 application.yml 里打开那个开关，它就可达了。
 *   BindException
 *       项目里没有「查询 DTO + 尾部 Errors 参数」这种老式写法，
 *       Spring 6 对查询 DTO 绑定失败直接抛它的子类 MethodArgumentNotValidException。
 *       ⚠️ 哪天用了那种老式写法，它就可达了。
 *   AsyncRequestTimeoutException
 *       项目里没有 DeferredResult / Callable / @Async。
 * </pre>
 *
 * <p><b>★ 但「掉进了兜底」不等于「需要专用处理器」—— 这是上面那份清单的边界。
 * 有两个是想清楚了故意不写的：</b>
 * <pre>
 *   DataIntegrityViolationException（非唯一键分支）
 *       唯一键那一支已经有 DuplicateKeyException → handleDuplicateKey 接住了。
 *       剩下的是「值超长 / 非空约束」这类 —— 它既不是协议错误，也不是业务错误，
 *       而是【我们自己的代码或约束写错了】，500 + ERROR 堆栈正是对的。
 *       给它加一个「你的参数有问题」的处理器，等于把服务端 bug 伪装成调用方的错，
 *       把真正该修的东西藏起来。
 *   IllegalStateException（UserContext 里 ThreadLocal 为空）
 *       拦截器漏配才会撞上，是服务端 bug，500 是对的；而且只在开发期出现。
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常 —— 这是最常见的，属于「预料之中」的错误。
     * 比如商品不存在、库存不足。不需要记 error 日志，因为不是系统故障。
     *
     * <p><b>★ 这里有一个例外：错误码是 401 时会返回真正的 HTTP 401。</b>
     *
     * <p>正常情况下业务异常一律返回 HTTP 200 + body.code（见类注释）。
     * 但 401 是个特殊情况 —— 它是前端「跳转到登录页」的触发信号，
     * 而这个信号可能从两个地方发出：
     * <pre>
     *   1. 拦截器发现没带 token / token 过期 → 它直接写 HTTP 401
     *      （拦截器抛的异常不会被 @RestControllerAdvice 捕获，
     *        见 AdminAuthInterceptor.reject 的注释）
     *   2. Service 发现账号被删了或被禁用了 → 抛 BusinessException(401)
     * </pre>
     *
     * <p>如果这两种情况一个走 HTTP 401、一个走 HTTP 200，
     * 前端就得<b>同时</b>判断 HTTP 状态码和 body 里的业务码才能知道
     * 该不该跳登录页 —— 少判断一处就是一个「登录过期了但页面不跳转」的 bug。
     *
     * <p>所以这里统一一下：<b>code 401 永远对应 HTTP 401</b>，
     * 让「要不要重新登录」只需要看一个地方。
     *
     * <p>返回 {@code ResponseEntity} 而不是直接返回 {@code Result}，
     * 就是为了能自由控制 HTTP 状态码。Spring MVC 对这两种返回值都支持。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());

        Result<Void> body = Result.error(e.getCode(), e.getMessage());

        boolean needLogin = e.getCode() != null && e.getCode() == ResultCode.UNAUTHORIZED;
        return needLogin
                ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body)
                : ResponseEntity.ok(body);
    }

    /**
     * 处理参数校验失败。
     *
     * <p>当 Controller 参数上写了 {@code @Valid}，而 DTO 字段上的
     * {@code @NotBlank}、{@code @Min} 等约束不满足时，Spring 会抛这个异常。
     *
     * <p>一个请求可能同时违反多条约束，所以 {@code getFieldErrors()} 是个列表。
     * 这里只取第一条返回给用户 —— 一次提示一条，用户改完再提示下一条，
     * 比一口气糊一屏错误更好用。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        // ★ 这个处理器要处理两类【性质完全不同】的错误，得先分开。
        //
        //   1. 约束校验失败（@NotBlank/@Min/@Pattern 那些）
        //      → 请求格式是对的，是内容不合规。业务错误，HTTP 200 + code 400。
        //
        //   2. 类型转换失败（?pageNum=abc 这种）
        //      → 请求【本身就是畸形的】，属于协议错误。按本项目的约定
        //        （见 handleNoResourceFound 的注释）该返回真正的 HTTP 400。
        //
        //   为什么第 2 类会跑到这个方法里来？
        //   因为查询参数绑定到 DTO 时，Spring 把绑定失败也包装成
        //   MethodArgumentNotValidException（它继承了 BindException）。
        //   而【路径变量】上的同一类错误抛的是 MethodArgumentTypeMismatchException，
        //   走的是下面那个处理器 —— 同一个「类型转换失败」有两条路径，
        //   不显式处理的话两边表现会不一样。
        //
        //   这个坑是测试脚本发现的：/products/abc 返回 500「系统繁忙」，
        //   而 ?pageNum=abc 返回 400 —— 同一个性质的问题两种结果。
        // getCodes() 返回的是错误码数组，类型转换失败时形如：
        //   ["typeMismatch.shopProductQueryDTO.pageNum", "typeMismatch.pageNum",
        //    "typeMismatch.java.lang.Integer", "typeMismatch"]
        // 用 startsWith 匹配而不是 equals，是因为最具体的那个码带了字段路径。
        //
        // ★ 用【错误码】判断而不是用【消息文本】判断 ——
        //   消息文本是给人看的，会随语言和 Spring 版本变；
        //   错误码是给程序用的，稳定得多。这是个通用的原则：
        //   **永远不要去字符串匹配一段人类可读的文案。**
        boolean typeMismatch = e.getBindingResult().getFieldErrors().stream()
                .anyMatch(fe -> fe.getCodes() != null
                        && Arrays.stream(fe.getCodes()).anyMatch(c -> c.startsWith("typeMismatch")));

        if (typeMismatch) {
            String field = e.getBindingResult().getFieldErrors().get(0).getField();
            // 不返回 Spring 生成的原始消息 —— 它会带上
            // 'java.lang.String' 'java.lang.Integer' 这类内部类型名，
            // 用户看不懂，也暴露了实现细节。理由同 handleNotReadable。
            log.warn("查询参数类型转换失败: field={}", field);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error(ResultCode.BAD_REQUEST, "参数 " + field + " 格式有误"));
        }

        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");

        log.warn("参数校验失败: {}", message);
        return ResponseEntity.ok(Result.error(ResultCode.BAD_REQUEST, message));
    }

    /**
     * 处理路径变量 / 请求参数的类型转换失败。
     *
     * <p>典型场景：{@code GET /api/shop/products/abc}。
     * 这个接口声明的是 {@code @PathVariable Long id}，而 URL 里给了 "abc"，
     * Spring 没法把它变成 Long，就直接抛异常了 ——
     * <b>业务代码一行都没跑</b>。
     *
     * <p><b>没有这个处理器会怎样？</b>它会掉进最下面的兜底处理器，
     * 结果是：HTTP 200 + code 500 + 一条 ERROR 级日志和完整堆栈。
     * 两个问题：
     * <ol>
     *   <li><b>语义错了</b> —— 这是调用方参数写错，不是服务端故障。
     *       告诉用户「系统繁忙」会让他去重试，而重试永远不会成功。</li>
     *   <li><b>日志会被刷爆</b> —— 这是更要紧的一点。
     *       任何人写个循环请求 {@code /products/随便什么字符串}，
     *       就能往你的 error 日志里灌垃圾。真正的故障堆栈会被淹没在里面。
     *       <b>凡是「未认证的请求能让服务端记 ERROR 日志」的地方，
     *       都是一个可被利用的日志放大点。</b></li>
     * </ol>
     *
     * <p>所以这里和 {@code handleNotReadable} 一样：记 warn（调用方的问题），
     * 返回真正的 HTTP 400，并且只回一句人话。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型转换失败: name={}, value={}", e.getName(), e.getValue());
        return Result.error(ResultCode.BAD_REQUEST, "参数 " + e.getName() + " 格式有误");
    }

    /**
     * 处理数据库唯一索引冲突。
     *
     * <p><b>这个处理器什么时候会真的被触发？</b>
     * 正常使用下<b>不会</b> —— Service 里的重名校验会先拦下来。
     * 它只在并发场景下被触发：两个请求几乎同时提交同名分类，
     * 两边都查到「不存在」，应用层校验双双放行，
     * 最后是数据库的唯一索引挡住了第二次插入。
     *
     * <p><b>那为什么还要写？</b>因为没有它，这个并发场景就会走到最下面的兜底
     * 处理器，用户看到的是「系统繁忙，请稍后重试」——
     * 明明是自己提交了重复数据，却被告知服务器出了问题，
     * 用户会反复重试，而每次重试都会失败。
     * 有了它，用户看到的是「该名称已存在，请换一个再试」，知道该怎么办。
     *
     * <p><b>这也是「应用层校验 + 数据库约束」两道防线的完整形态</b>：
     * <pre>
     *   应用层校验  → 99.9% 的情况在这里拦下，能给精确提示
     *   唯一索引    → 兜住剩下的并发情况，保证数据不会真的重复
     *   这个处理器  → 把数据库的报错翻译成人话，别让用户收到「系统繁忙」
     * </pre>
     * 少了任何一环都不完整。只做应用层校验数据会脏，
     * 只做数据库约束用户会看到一堆看不懂的报错。
     *
     * <p>{@code DuplicateKeyException} 是 Spring 对各类数据库唯一约束冲突的
     * 统一抽象。MyBatis 抛出的 {@code SQLIntegrityConstraintViolationException}
     * 会被 Spring 的异常转换机制翻译成它，所以这里不用关心底层是 MySQL 还是别的库。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一索引冲突: {}", e.getMessage());
        // 不返回 e.getMessage()：里面带着 "Duplicate entry 'xxx' for key 'category.uk_name'"
        // 这种数据库内部信息，暴露表名和索引名没有必要
        return Result.error(ResultCode.DUPLICATE_NAME, "该名称已存在，请换一个再试");
    }

    /**
     * 处理「请求的 URL 没有对应的接口」。
     *
     * <p><b>为什么必须单独处理这个？</b>
     * 因为它会被下面的兜底 {@code Exception} 处理器接住，
     * 结果就是：访问一个写错的 URL，返回的却是
     * 「系统繁忙，请稍后重试」+ 一整条 ERROR 堆栈。
     *
     * <p>这会带来两个实际危害：
     * <ol>
     *   <li><b>误导排查方向</b>：前端明明是把路径写错了（比如漏了 /admin），
     *       看到的却是「服务器内部错误」，会让人以为是后端挂了</li>
     *   <li><b>污染错误日志</b>：监控系统按 ERROR 级别告警，
     *       写错 URL 这种小事会把真正的故障淹没掉</li>
     * </ol>
     *
     * <p><b>★ 这里和上面的业务异常有个重要区别</b>：
     * 前面说过「业务结果用响应体里的 code 表达，HTTP 状态码一律 200」。
     * 但这一条规则只适用于<b>业务层面</b>的成败 ——
     * 「库存不足」是业务结果，「URL 不存在」是<b>协议层</b>的错误。
     * 后者用真正的 HTTP 404 更准确：浏览器、Nginx、监控工具
     * 都能正确理解 404 的含义，而对一个 200 响应它们什么也看不出来。
     *
     * <p>所以本项目的约定是：
     * <pre>
     *   业务结果（库存不足、重名、无权限）→ HTTP 200 + body.code = 1xxx
     *   协议错误（404 路径错、400 报文错）→ 真正的 HTTP 状态码
     * </pre>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        // 用 warn 而不是 error：路径写错是调用方的问题，不是服务端故障
        log.warn("请求的接口不存在: {}", e.getResourcePath());
        return Result.error(ResultCode.BAD_REQUEST, "请求的接口不存在");
    }

    /**
     * 处理「路径存在，但 HTTP 方法不对」。
     *
     * <p>典型场景：{@code GET /api/shop/cart/items} ——
     * 这个接口只声明了 {@code POST}，用 GET 请求它就没有处理方法能匹配上。
     *
     * <p><b>★ 这个处理器和上面几个是同一类问题的最后一个漏网之鱼。</b>
     * 没有它的时候，表现是这样的：
     * <pre>
     *   GET    /api/shop/cart/items  →  HTTP 200 + code 500「系统繁忙，请稍后重试」
     *   PATCH  /api/shop/cart/items  →  HTTP 200 + code 500「系统繁忙，请稍后重试」
     *   DELETE /api/shop/products    →  HTTP 200 + code 500「系统繁忙，请稍后重试」
     * </pre>
     * 危害和 {@code handleTypeMismatch} 注释里写的两条一模一样：
     * <ol>
     *   <li><b>语义反了</b> —— 调用方只是用错了 HTTP 方法，
     *       却被告诉「系统繁忙，请稍后重试」。用户真的去重试，
     *       而重试永远不会成功，因为方法一直是错的</li>
     *   <li><b>日志放大</b> —— 这是一条 ERROR 级日志 + 完整堆栈。
     *       任何人写个循环 {@code GET /api/shop/cart/items} 就能刷满 error 日志。
     *       而且它比 {@code /products/abc} 更隐蔽：
     *       这个路径看起来完全正常，运维看到日志只会以为接口坏了</li>
     * </ol>
     *
     * <p><b>为什么是 405 而不是 404？</b>
     * 405 Method Not Allowed 是 HTTP 规范里为这件事准备的语义
     * （RFC 9110 §15.5.6）。有一种安全实践是用 404 掩盖接口的存在，
     * 但那对这个项目没意义 —— 商城的接口本来就是公开的，
     * 隐藏「/cart/items 存在」不带来任何安全性，
     * 却会让写错方法的开发者完全摸不着头脑。
     *
     * <p><b>★ 规范要求 405 响应必须带 Allow 头</b>，列出这个路径支持哪些方法。
     * Spring 自己处理 405 时会带上，但我们接管了异常之后就得自己加 ——
     * 这就是「自己处理异常」的代价：方便了，但规范要求的细节要自己记得。
     * 加上它很值：前端开发者看到 {@code Allow: POST} 立刻就知道该改什么，
     * 不用去翻后端代码。
     *
     * <p><b>一个顺带观察到的现象</b>：{@code PATCH /api/shop/cart/items}
     * 是匿名的，返回的却是 405 而不是 401 —— 说明<b>拦截器根本没跑</b>。
     * 原因是方法不匹配时 Spring 在 {@code getHandler()} 阶段就抛异常了，
     * 那时候 {@code HandlerExecutionChain}（拦截器链）还没建起来。
     * 和 {@code handleNoResourceFound} 注释里说的是同一个道理：
     * <b>拦截器只保护「真的能执行的方法」，没有方法可执行时它不介入。</b>
     * 这不是漏洞（没有代码会跑），但解释了为什么「用错方法」不会触发鉴权。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        String supported = e.getSupportedHttpMethods() == null
                ? ""
                : e.getSupportedHttpMethods().stream()
                        .map(HttpMethod::name)
                        .sorted()
                        .collect(Collectors.joining(", "));

        log.warn("请求方法不支持: method={}, 该接口支持={}", e.getMethod(), supported);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (!supported.isEmpty()) {
            builder.header(HttpHeaders.ALLOW, supported);
        }

        // 响应体里的 code 仍用 400：本项目的 code 只有
        // 200/400/401/403/500 这几档（见 ResultCode 的分段规划），
        // 405 属于协议层错误，前端看的是 HTTP 状态码而不是这个 code。
        // 这里和上面的 handleNoResourceFound 保持一致的做法。
        return builder.body(Result.error(ResultCode.BAD_REQUEST,
                "该接口不支持 " + e.getMethod() + " 请求"
                        + (supported.isEmpty() ? "" : "，请改用 " + supported)));
    }

    /**
     * 处理请求体解析失败。
     *
     * <p>最典型的场景是<b>发过来的 JSON 格式不对</b>：
     * 少了个引号、多了个逗号，或者<b>编码不是 UTF-8</b>
     * （用 Git Bash 的 curl 直接带中文参数就会踩到这个，
     * 因为 Windows 的 Git Bash 默认用 GBK）。
     *
     * <p>不单独处理的话，同样会被兜底成「系统繁忙」，
     * 于是你会以为是后端代码有问题，实际只是请求本身发歪了。
     * 调试时这种误导特别浪费时间。
     *
     * <p>这里也不把 {@code e.getMessage()} 返回给前端 ——
     * 里面会带 Jackson 的类名和字符偏移量，用户看不懂。
     * 具体原因记在日志里，够排查就行。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST, "请求数据格式有误，请检查 JSON 格式和编码是否为 UTF-8");
    }

    /**
     * 处理「请求体的 Content-Type 读不了」（★ 里程碑 13 新增）。
     *
     * <p>触发条件：往一个 {@code @RequestBody} 接口发 {@code Content-Type: text/plain}。
     * 异常从 {@code readWithMessageConverters} 抛出 —— 它遍历所有消息转换器，
     * 没有一个 {@code canRead(目标类型, text/plain)} 返回 true。
     * <b>业务代码一行都没跑，连 {@code @Valid} 都没轮到。</b>
     *
     * <h3>★ 为什么是真 HTTP 415，而不是像 handleMaxUploadSize 那样 200 + code 400</h3>
     *
     * <p>这两条看起来矛盾，其实不矛盾 —— 区别还是那一条判据
     * （完整原文在 {@code handleMaxUploadSize} 的 ★★ 段）：<b>请求本身是不是畸形的</b>。
     * <pre>
     *   ?pageNum=abc                  畸形（"abc" 不是数字）        → 真 400
     *   文件 3MB，multipart 结构正常   不畸形，只是违反一条声明限额   → 200 + code 400
     *   压根不是 multipart             畸形                        → 真 400
     *   Content-Type: text/plain       畸形                        → 真 415  ← 本方法
     * </pre>
     * 415 和 {@code handleMultipart} 是<b>同一族</b>：请求本身就发错了，
     * 连「解析」这个动作都不成立。
     *
     * <p><b>★★ 那条「request.js 不看响应体」的论据不是独立判据。</b>
     * 反证：如果「消息到不了用户眼前」就足以否掉真状态码，那么
     * 404、405、{@code handleNotReadable}、{@code handleTypeMismatch}
     * <b>全都违反它</b> —— 而这四个是本项目<b>刻意</b>做成真状态码的。
     * 项目早就接受了一件事：<b>有一类错误是给开发者看的，不是给最终用户看的。</b>
     *
     * <p><b>★★ 那条论据其实有一个从没写出来的前提：这个错误从真实前端可达。</b>
     * 补上它，两个 case 就完全不冲突：
     * <pre>
     *   MaxUploadSizeExceeded  【可达】el-upload 的 :http-request 会把用户选的
     *                                任意大小的图原样发出去
     *                                →「图片太大了，最大 2MB」必须到达用户眼前
     *                                → 只能 200 + code 400
     *   415                    【不可达】axios 对普通对象请求体自动设 application/json，
     *                                项目里没有任何一处手工设成别的值
     *                                → 这条分支的读者是 curl / Postman / devtools 用户
     *                                → 他要的就是那个 415，而人话提示写在 body 里
     * </pre>
     * ★ 这个前提必须写下来 —— 不补的话，下一个人照着
     * {@code handleMaxUploadSize} 那段注释做，会把 415 也改成 200 + 400。
     * （405 和 404 同理不可达：Vue 路由不会发错方法，路径是写死的。
     *   这也解释了为什么那几条一开始就选了真状态码，而且从没出过问题。）
     *
     * <p><b>反方向的理由</b>：200 + code 400 会让「服务端拒绝解析了这个请求」
     * 在 HTTP 层表现为一次<b>成功的交换</b>，Nginx、网关、监控全都看不到异常。
     *
     * <h3>★ 为什么用 ResponseEntity 而不是 @ResponseStatus</h3>
     *
     * <p>因为要加一个 {@code Accept} 响应头。这正是 {@code ResponseEntity} 在本类的
     * 第二个用法（第一个是 {@code handleMethodNotSupported} 的动态 {@code Allow} 头）——
     * <b>状态码是固定常量、又不需要动态响应头时，就用 {@code @ResponseStatus} + 裸 {@code Result}。</b>
     *
     * <p>★ 这条和 405 那句注释是同一条原则：<b>接管了异常，规范要求的细节就得自己记得。</b>
     * 依据不是猜的：{@code HttpMediaTypeNotSupportedException.getHeaders()} 里调的就是
     * {@code setAccept(getSupportedMediaTypes())} —— <b>Spring 自己处理 415 时也会带上它</b>，
     * 我们只是把它接了过来。
     *
     * <p>⚠️ <b>一个已知且主动接受的漏洞</b>：如果一个请求<b>同时</b>发了
     * {@code Content-Type: text/plain} 和 {@code Accept: application/xml}，本方法会走进死胡同 ——
     * 它想写 JSON body，而客户端声明读不了 JSON → 二次抛出 → 最终是 500 + Tomcat 错误页。
     * <b>不修的理由</b>：修它只有一条路，让本方法也返回 {@code ResponseEntity<Void>}，
     * 那 415 连一句人话都给不出来。为一个「两个头同时发错」的请求牺牲掉所有正常 415
     * 的可读性，不划算。<b>这是一次主动的取舍，不是没想到。</b>
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e) {
        // ★ 复用异常里现成的「本接口能读什么」，不硬编码 "application/json" ——
        //   它是按【目标类型】收窄过的（异常构造时传的是 getSupportedMediaTypes(targetClass)），
        //   对 DTO 会给 application/json 和 application/*+json 两个。
        //   端点将来支持了 XML，这个头会自己跟上。
        String supported = e.getSupportedMediaTypes().stream()
                .map(MediaType::toString)
                .collect(Collectors.joining(", "));

        log.warn("请求体的 Content-Type 不支持: contentType={}, 该接口支持={}",
                e.getContentType(), supported);

        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        if (!supported.isEmpty()) {
            builder.header(HttpHeaders.ACCEPT, supported);
        }

        // 响应体里的 code 仍用 400：本项目的 code 只有 200/400/401/403/500 这几档，
        // 415 属于协议层错误，前端看的是 HTTP 状态码而不是这个 code。
        // 这里和 handleMethodNotSupported 保持一致的做法。
        return builder.body(Result.error(ResultCode.BAD_REQUEST,
                "请求体的 Content-Type 不支持，该接口只接受 " + supported));
    }

    /**
     * 处理「客户端不接受我们能产出的格式」（★ 里程碑 13 新增）。
     *
     * <p>触发条件：请求头里写了 {@code Accept: application/xml}，
     * 而本项目的消息转换器只会产出 JSON。
     *
     * <h3>★★ 为什么它不返回 Result，而且【必须】不返回</h3>
     *
     * <p>一句话的理由：<b>客户端刚刚明确告诉我们它读不懂我们的格式，
     * 我们再塞一个 JSON 给它，是在做一件已经被拒绝的事。</b>
     *
     * <p>而给它塞 body 的后果比不处理还糟。<b>下面这段是逐字节码核对过的</b>
     * （spring-webmvc 6.2.19），不是推理：
     * <pre>
     * 1. 返回值归 HttpEntityMethodProcessor 管（它排在
     *    RequestResponseBodyMethodProcessor 之前）。它【先】setStatus，
     *    【再】writeWithMessageConverters(httpEntity.getBody(), ...)
     *    → 对 .build() 来说，传进去的 body 就是 null。
     *
     * 2. writeWithMessageConverters 里有两处会抛 406，
     *    而【两处都以 body == null 为放行条件】：
     *      a. getAcceptableMediaTypes() 抛 → 被自己 catch，判断
     *         "body == null || 状态码/100 是 4 或 5" → 命中就 return，不写、不重抛
     *      b. 交集为空时抛 → 但它显式写了 if (body != null) 才抛，
     *         body 为 null 就直接 return
     *    → .build() 走完整条链路：406 设上、body 不写、不重抛、flush。
     *      响应 = 406 + 空 body + Content-Length: 0。
     *
     * 3. 那塞一个 body 呢？第 2.b 步会因为 body != null 真的把 406 抛出来，接着：
     *    → ExceptionHandlerExceptionResolver 的 catch(Throwable) 接住
     *      （它只检查「是不是原来那个异常」，不是就 warn 一句
     *        "Failure in @ExceptionHandler"，然后返回 null）
     *    → 下一个解析器 DefaultHandlerExceptionResolver.handleHttpMediaTypeNotAcceptable
     *      ★ 它的方法体只有两条指令：aconst_null; areturn; —— 空实现，故意什么都不写
     *    → 所有解析器都没产出 ModelAndView → DispatcherServlet 把原异常重新抛出去
     *    → Tomcat 收到没人处理的 ServletException → 500 错误页 + 一条堆栈日志
     * </pre>
     *
     * <p><b>所以给 406 写 body 的后果是：HTTP 500 + Tomcat 的 HTML 错误页 +
     * 每次请求一条 ERROR 堆栈。</b>而它是<b>匿名可达的</b>
     * （{@code GET /api/shop/products} 就在拦截器的排除清单里，见 {@code WebMvcConfig}），
     * 一个循环就能把日志刷满 —— 正是 {@code handleTypeMismatch} 注释里点过名的那类缺陷：
     * <b>语义反了 + 日志放大</b>。
     *
     * <p>★ 这不是我们自己发明的判断，<b>Spring 自己就是这么做的</b> ——
     * 上面第 3 步里那个空方法就是证据。我们接管了这个异常，就应该继承这个判断。
     *
     * <p>★★ 这段推演必须留在注释里。否则下一个人看到「就它不返回 {@code Result}」，
     * 会以为是不小心漏了，然后顺手补齐 —— 而那个「补齐」的真实后果是 500。
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.warn("客户端不接受我们能产出的格式: 我们能产出={}", e.getSupportedMediaTypes());
        // ★★ 没有 body，而且【必须】没有 body —— 完整推演见上面的注释。
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    /**
     * 处理上传文件超过大小限制（★ 里程碑 11 新增）。
     *
     * <p>触发条件：请求里的文件超过了 {@code application.yml} 里
     * {@code spring.servlet.multipart.max-file-size}（本项目是 2MB）。
     *
     * <h3>★ 为什么必须有这个处理器</h3>
     *
     * <p>因为这个异常<b>在进入 Controller 之前就抛了</b> ——
     * Spring 的 multipart 解析发生在<b>参数绑定阶段</b>，业务代码一行都没跑。
     * 所以不能指望 Service 里再判一次大小（它根本没机会执行）。
     *
     * <p>没有它的话，异常会掉进最下面的兜底处理器，用户看到
     * 「系统繁忙，请稍后重试」—— 而<b>他重试永远不会成功</b>，
     * 因为那张图一直是那么大。这是本项目注释里已经写过两次的
     * 「语义反了」（见 {@code handleTypeMismatch} / {@code handleNotReadable}）：
     * 告诉用户「服务器故障」会让他去重试一个注定失败的操作，
     * 正确的说法是「你传的东西不合规」。
     *
     * <p>记 {@code warn} 而不是 {@code error}：传了个大文件是<b>调用方</b>的问题，
     * 不是服务端故障。理由同 {@code handleTypeMismatch} ——
     * 而且这里还有一条：任何登录的管理员都能用一个循环把 error 日志刷满，
     * 真正的故障堆栈会被淹掉。凡是「一次普通请求就能让服务端记 ERROR」的地方，
     * 都是一个可被利用的日志放大点。
     *
     * <h3>★★ 为什么返回 HTTP 200 + code 400，而不是真正的 HTTP 400</h3>
     *
     * <p>这条和 {@code handleTypeMismatch} 的结论<b>相反</b>，所以必须说清楚
     * 判据是什么 —— 区别不在「谁抛的异常」，在<b>这个请求本身是不是畸形的</b>：
     * <pre>
     *   ?pageNum=abc           请求本身畸形（"abc" 根本不是个数字）
     *                          → 协议错误 → 真正的 HTTP 400
     *
     *   文件 3MB，但格式完全正常
     *                          请求本身是合法的，只是内容违反了一条【声明的限额】
     *                          → 和 @NotBlank / @Min / @Size 是同一族
     *                          → 业务错误 → HTTP 200 + code 400
     * </pre>
     *
     * <p>明确的先例就在上一条 {@code handleValidationException} 里：
     * 约束校验失败返回的是 {@code ResponseEntity.ok(...)}，
     * 只有类型转换失败那一支才返回 HTTP 400。
     *
     * <p>⚠️ 而且这里有一个<b>具体的、可验证的</b>原因：
     * {@code mall-web/src/api/request.js} 的响应拦截器，对非 2xx 的响应
     * <b>完全不看响应体</b>，直接按状态码拼一句「请求失败（400）」。
     * 所以如果这里返回真正的 HTTP 400，那句「图片太大了，最大 2MB」
     * <b>永远到不了用户眼前</b> —— 写了等于没写。
     *
     * <p>⭐ 更深一层：这三个「参数类」处理器（校验失败、类型转换、请求体不可读）
     * 的结论各不相同，而它们看起来长得很像。区分它们靠的始终是同一个问题：
     * <b>「这是调用方写错了请求，还是他提了一个不合规但格式正确的内容？」</b>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        // 日志里带上真实的异常信息（它会说明是哪一条限制被突破了），
        // 但不返回给前端 —— 理由同 handleNotReadable，内部细节不外泄。
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST, "图片太大了，最大 2MB");
    }

    /**
     * 处理「请求根本不是 multipart」的情况（★ 里程碑 12 新增）。
     *
     * <p>触发条件：往一个要求 {@code multipart/form-data} 的上传接口
     * 发了别的请求 —— 最典型的就是发了个空的 POST，或者发了个 JSON。
     * Spring 的报错是
     * {@code MultipartException: Current request is not a multipart request}。
     *
     * <h3>★ 它是【被测试逼出来的】，不是设计时想到的</h3>
     *
     * <p>里程碑 12 写 {@code test-review.py} 时有这么一条用例：
     * 「往 {@code /api/shop/images} 发一个不带文件的 POST，应该返回 400」。
     * 结果它返回的是 <b>HTTP 200 + code 500「系统繁忙，请稍后重试」</b> ——
     * 这条用例当场红了。
     *
     * <p><b>★★ 这正是「测试的价值不在于证明它对，而在于发现你想不到的东西」的
     * 一个小例子。</b>写上传功能时满脑子想的是「文件太大怎么办」「格式不对怎么办」，
     * 而「压根没带文件」这条从没想到过 —— 因为<b>真实前端永远会带上文件</b>
     * （{@code el-upload} 的 {@code :http-request} 一定会构造 FormData）。
     * 只有测试会去发一个前端永远不会发的请求。
     *
     * <h3>★ 为什么它和 handleMaxUploadSize 是【两个】处理器</h3>
     *
     * <p>两者都是 multipart 家族的异常，但结论正好相反 ——
     * 判据还是那一条：<b>请求本身是不是畸形的</b>。
     * <pre>
     *   文件 3MB（handleMaxUploadSize）
     *        multipart 结构完全正常，只是违反了一条【声明的限额】
     *        → 业务错误 → HTTP 200 + code 400
     *
     *   压根不是 multipart（本方法）
     *        请求本身就发错了，连"上传"这个动作都不成立
     *        → 协议错误 → 真正的 HTTP 400
     * </pre>
     *
     * <p>这个结论和 {@code handleNotReadable}（请求体不是合法 JSON）<b>一致</b> ——
     * 那两个都是「调用方把请求写错了」，错误在<b>协议层</b>，不在内容层。
     *
     * <p>⚠️ 为什么不用 HTTP 200 + code 400？因为按项目约定，
     * 协议错误的判据是「HTTP 状态码表达这次请求本身怎么样了」。
     * 这里请求确实是畸形的，所以用真状态码是对的 ——
     * 和 {@code handleTypeMismatch}（{@code ?pageNum=abc}）站在一起。
     *
     * <p>⚠️ {@code MultipartException} 是 {@code MaxUploadSizeExceededException}
     * 的<b>父类</b>，所以它理论上能接到「文件太大」那个子类。
     * <b>但 Spring 会选最匹配的处理器</b>，子类那个方法会优先命中，
     * 这里的顺序不影响结果（这也是下面兜底处理器能写在最后的原因）。
     *
     * <p>记 {@code warn}：这是调用方把请求发错了，不是服务端故障。
     * 理由同 {@code handleMaxUploadSize} —— 上传接口是任何人都能打的，
     * 用 {@code error} 的话，一个循环就能把 error 日志刷满。
     */
    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMultipart(MultipartException e) {
        // ★ 日志里【带上】真实异常信息（它会说明到底哪里不对），
        //   但不返回给前端 —— 理由同 handleNotReadable，内部细节不外泄。
        log.warn("multipart 请求解析失败: {}", e.getMessage());
        // ★ 里程碑 13：这半句「字段名必须叫 file」被【删掉了】，搬去了下一个处理器。
        //   不是嫌它长 —— 而是走到本方法时【压根不是 multipart 请求，没有字段名可言】，
        //   那句话在这个分支里永远不成立。它不是废话，是【误导】：
        //   一个真的遇到 Content-Type 问题的调用方，看到「字段名必须叫 file」
        //   会去 FormData 里找字段名，而真正的问题在请求头上。
        return Result.error(ResultCode.BAD_REQUEST, "请以 multipart/form-data 方式上传文件");
    }

    /**
     * 处理「是 multipart，但里面没有我们要的那个 part」（★ 里程碑 13 新增）。
     *
     * <p>触发条件：发了一个<b>结构完全合法的 multipart 请求</b>，
     * 但里面没有叫 {@code file} 的部分（比如 FormData 里 append 的名字写成了 {@code image}）。
     *
     * <h3>★ 为什么是真 400</h3>
     *
     * <p>判据还是那一条：<b>请求本身是不是畸形的</b>。
     * <pre>
     *   Content-Type 压根不是 multipart      畸形                        → 真 400（handleMultipart）
     *   multipart 结构合法，但缺了那个 part   【请求本身是错的】          → 真 400（本方法）
     *   文件 3MB，结构合法                    不畸形，只是违反声明限额    → 200 + code 400
     * </pre>
     * 本方法和 {@code handleMultipart} 站在一起，<b>而不是和 {@code handleMaxUploadSize} 站在一起</b>。
     *
     * <h3>★★ 为什么必须单开一个处理器 —— 这次的理由和前面几对【都不一样】</h3>
     *
     * <p>前面几对分开，都是因为对同一个问题得出了<b>相反的结论</b>。
     * 这一对不是：两者都是真 400、{@code code} 都是 400。
     * 分开的理由是<b>硬性的、类型系统层面的</b>（已 {@code javap} 核对）：
     * <pre>
     *   MultipartException                    extends NestedRuntimeException
     *   MissingServletRequestPartException    extends jakarta.servlet.ServletException
     * </pre>
     * <b>两者在类层次上没有半点亲缘关系</b>，
     * 所以 {@code @ExceptionHandler(MultipartException.class)} <b>接不住它</b>。
     *
     * <p>★ 提炼成一句能记住的话：<b>要不要单开一个处理器，取决于「能不能被现有处理器接住」，
     * 而不是「结论是否相同」。</b>名字只差几个词的两个异常，
     * 可能一个也漏不掉，也可能一个都接不住 —— <b>亲缘关系要查，不能猜。</b>
     *
     * <h3>★ 两个处理器合起来，才把「同一个缺失」的两条路补全</h3>
     *
     * <p>对照 {@code RequestParamMethodArgumentResolver.handleMissingValueInternal} 的分支：
     * <pre>
     *   参数是 MultipartFile 而请求里没给
     *     ├─ 请求的 Content-Type【不是】multipart  → MultipartException                → handleMultipart
     *     └─ 是 multipart，但【没有那个 part】     → MissingServletRequestPartException → 本方法
     * </pre>
     * <b>「没带文件」这一个说法，在 Spring 眼里是两件不同的事</b>，
     * 判据是 <b>Content-Type 头</b>，不是请求体长什么样。
     *
     * <h3>★ 它为什么这么久没被发现</h3>
     *
     * <p>{@code test-upload.py} / {@code test-review.py} 里的 {@code post_file} 辅助函数
     * 有一个 {@code field="file"} 参数，<b>默认值恰好永远正确，从来没人传过别的值</b>。
     * ★ 提炼成一句能记住的话：<b>一个「默认值恰好永远正确」的参数，就是一个永远测不到的分支。</b>
     * 这比「忘了写用例」隐蔽得多 —— 代码看起来是参数化的、文档看起来是覆盖了的，
     * 但那个参数只用过一次。
     *
     * <p>★ 把 {@code e.getRequestPartName()} 回给前端<b>不构成信息泄漏</b> ——
     * 它来自我们自己写的 {@code @RequestParam("file")}，不是从请求里来的
     * （对比 {@code handleDuplicateKey} 不回 {@code e.getMessage()} 的理由）。
     *
     * <p>记 {@code warn}：这是调用方把请求发错了，不是服务端故障。
     * 上传接口任何人都能打，用 {@code error} 一个循环就能刷满日志。
     */
    // ⚠️ import 是 org.springframework.web.multipart.【support】.MissingServletRequestPartException
    //    不是 org.springframework.web.multipart —— 少一段，IDE 补全里找不到，会找半天
    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingServletRequestPart(MissingServletRequestPartException e) {
        log.warn("multipart 请求缺少必需的部分: {}", e.getRequestPartName());
        return Result.error(ResultCode.BAD_REQUEST,
                "上传文件的字段名必须叫 " + e.getRequestPartName()
                        + "，请检查 FormData 里 append 的字段名");
    }

    /**
     * 兜底：处理所有没被上面捕获的异常。
     *
     * <p>这类通常是代码 bug（空指针、数组越界）或环境问题（数据库连不上），
     * 属于「预料之外」的错误。要记 error 级别的日志并打印完整堆栈，方便排查。
     *
     * <p><b>注意这里不把 e.getMessage() 返回给前端</b> ——
     * 原始异常信息可能包含表名、SQL 片段、文件路径等内部细节，
     * 泄露给前端是不安全的，而且用户也看不懂。统一给一句笼统的提示，
     * 详细信息只写进服务端日志。
     *
     * <p><b>这个处理器一定要放在最后</b>：Spring 会选「最匹配」的处理器，
     * 所以即使把它写在前面，具体类型的处理器仍然优先。
     * 但把它写在最后，读代码的人一眼就知道「以上都不匹配才会走到这里」。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统繁忙，请稍后重试");
    }
}
