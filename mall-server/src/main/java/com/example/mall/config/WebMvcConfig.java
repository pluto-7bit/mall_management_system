package com.example.mall.config;

import com.example.mall.interceptor.AdminAuthInterceptor;
import com.example.mall.interceptor.MemberAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring MVC 配置 —— 目前做两件事：注册两个拦截器，以及把上传目录映射成静态资源。
 *
 * <p>{@code WebMvcConfigurer} 是 Spring MVC 提供的扩展点接口，
 * 实现它就能插手 MVC 的各种配置（拦截器、参数解析器、跨域、消息转换器……）。
 * 它里面的方法都有 default 实现，所以<b>只需要覆盖你关心的那几个</b>，
 * 不用写一堆空方法。
 *
 * <p>两个拦截器都是 {@link com.example.mall.interceptor.AbstractAuthInterceptor}
 * 的子类，逻辑完全一样，只有要求的身份类型不同。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final MemberAuthInterceptor memberAuthInterceptor;

    /** 上传文件的落盘根目录，来自 application.yml 的 mall.upload.dir */
    @Value("${mall.upload.dir}")
    private String uploadDir;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // ==================================================================
        // 管理端：/api/admin/**
        // ==================================================================
        registry.addInterceptor(adminAuthInterceptor)
                // ------------------------------------------------------------------
                // 拦截范围：所有 /api/admin/** 开头的接口
                // ------------------------------------------------------------------
                // ★ 用「路径前缀」而不是「在需要登录的接口上一个一个加注解」，
                //   是因为后者是黑名单思路 —— 新加一个接口时忘了加注解，
                //   那个接口就是裸奔的，而且不会有任何报错提示你。
                //   前缀白名单则是：只要放在这个包下就自动受保护，
                //   忘记配置的默认结果是「被保护」而不是「裸奔」。
                //
                //   安全设计上永远要选「默认安全」的那个方案。
                .addPathPatterns("/api/admin/**")
                // ------------------------------------------------------------------
                // 排除：登录接口本身
                // ------------------------------------------------------------------
                // 登录时用户当然还没有 token，不排除的话就成了死锁：
                // 想登录必须先登录。
                .excludePathPatterns("/api/admin/auth/login");

        // ==================================================================
        // 用户端：/api/shop/**
        // ==================================================================
        registry.addInterceptor(memberAuthInterceptor)
                .addPathPatterns("/api/shop/**")
                // ------------------------------------------------------------------
                // 排除：注册和登录
                // ------------------------------------------------------------------
                // 这两个是仅有的「匿名可调」接口，理由和管理端一样。
                //
                // ⚠️ 这里要留意排除的路径是【精确匹配】的：
                //    "/api/shop/auth/login" 不会排除
                //    "/api/shop/auth/login/xxx"。
                //    所以别把不同的接口挂在登录路径下面。
                //
                // ------------------------------------------------------------------
                // 排除：商品浏览和分类查询（★ 里程碑 6 新增，这一段值得细看）
                // ------------------------------------------------------------------
                // 「浏览不用登录，下单/购物车才要登录」是电商的通行规则 ——
                // 逼人注册才能看商品，转化率会掉得很惨：
                // 大部分人只想随便看看，被注册墙拦住就直接关掉了。
                // 而愿意注册的人往往已经决定要买，这时候要账号才是等价交换。
                //
                // ★ 但这里有一个必须想清楚的问题：
                //   一条路径被排除掉 = 它【对所有写操作也一并免检】。
                //   所以排除之前要先问：
                //   「这个路径下，将来会不会出现需要登录的接口？」
                //
                //   现在这两条路径下的所有接口都是【纯只读】的：
                //     GET /api/shop/products                     列表
                //     GET /api/shop/products/{id}                详情
                //     GET /api/shop/products/{id}/reviews        某商品的评价 ★ 里程碑 12 新增
                //     GET /api/shop/categories                   分类
                //   一个写操作都没有（详见 ShopProductController 的类注释），
                //   所以整条路径放开是安全的。
                //
                //   ★ 里程碑 12 那条新路径【一个字都没改这份配置】就自动免检了 ——
                //     因为它落在 /api/shop/products/** 这棵树里面。
                //     这不是「碰巧放对了位置」：评价是商品的从属资源，
                //     「商品匿名可读」这条规则的适用范围本来就该包含它。
                //     ★★ 反过来说，这也是这份清单最容易失效的地方 ——
                //     它是【人工维护】的，而路径匹配是【自动】的。
                //     两者一旦不一致，清单开始说假话，而权限照旧生效。
                //     所以：**在 /api/shop/products/** 或 /api/shop/categories 下
                //     新增任何一个接口时，必须回来补一行。**
                //     （写操作例外 —— 那属于下面那条红线，要去新开一棵路径树。）
                //
                // ⚠️ 但里程碑 7 的购物车【绝对不能】挂在这里。
                //   如果将来有人图省事写成：
                //       @RequestMapping("/api/shop/products")
                //       并在里面加一个 POST /api/shop/products/cart
                //   那么这个加入购物车的接口会因为本次排除而【变成匿名可调】——
                //   而且不会有任何报错，拦截器根本不跑。
                //   这就是「路径前缀式权限」的固有风险：
                //   授权规则和代码位置的耦合是隐式的。
                //
                //   所以定一条规矩：【需要登录的接口，不要放在被排除的路径树下】。
                //   购物车放 /api/shop/cart/**，订单放 /api/shop/orders/**，
                //   各自独立成树，就不会踩到这里的排除。
                //
                //   一般来说，把「只读的浏览」和「会改数据的操作」
                //   放进不同的路径树，是很划算的组织方式 ——
                //   一次路径划分就换来一个清晰的权限边界。
                .excludePathPatterns(
                        "/api/shop/auth/register",
                        "/api/shop/auth/login",
                        // 列表：/api/shop/products
                        "/api/shop/products",
                        // 详情：/api/shop/products/{id}
                        // 注意 "/api/shop/products/**" 其实已经能覆盖上面那一条，
                        // 这里两条都写是为了让「这条路径整棵树都放开」这件事
                        // 一眼可见，不用去回忆 /** 到底匹不匹配零段路径
                        "/api/shop/products/**",
                        // 分类：商品列表页的筛选下拉框要用，同样不需要登录
                        "/api/shop/categories");

        // ------------------------------------------------------------------
        // 关于两个拦截器的执行顺序
        // ------------------------------------------------------------------
        // 它们的路径范围（/api/admin/** 和 /api/shop/**）完全不重叠，
        // 任何请求最多只会命中其中一个，所以不需要考虑顺序。
        //
        // 如果将来有重叠（比如某天加了个同时服务两端的 /api/common/**），
        // 那么 registry 里【注册的顺序就是执行顺序】——
        // 上面的 admin 会先执行。到那时要仔细想清楚谁先谁后，
        // 因为「先执行的拦截器拒绝掉的请求，后面的根本不会跑」。
    }

    // ======================================================================
    // 静态资源映射（★ 里程碑 11 新增）
    // ======================================================================
    /**
     * 把用户上传的图片目录暴露成一个可访问的 URL 前缀。
     *
     * <p>在这之前，整个后端<b>不提供任何静态资源</b>
     * （{@code src/main/resources} 下只有 {@code application.yml} 和 {@code mapper/}）。
     * 商品封面图指向的 {@code /images/*.svg} 其实是由<b>用户端的 Vite dev server</b>
     * 提供的 —— 那个设计在当时是对的，但它有一个天花板：
     * 图片必须是随工程走的静态文件，运营没法自己换一张真实的图。
     * 里程碑 11 起，后端也能提供图片了。
     *
     * <h3>★ 为什么 {@code /uploads/**} 不在 {@code /api/**} 下面 —— 这是刻意的</h3>
     *
     * <p>因为 {@code <img src>} <b>发不出 {@code Authorization} 头</b>。
     * 图片要么公开可访问，要么就得把 token 塞进 URL（那是另一种糟糕）。
     * 所以三条路径的权限边界是：
     * <pre>
     *   /api/admin/**   被 AdminAuthInterceptor 拦，必须带管理员 token
     *   /api/shop/**    被 MemberAuthInterceptor 拦，必须带会员 token
     *   /uploads/**     不在任何拦截器的路径范围里 → 天然公开
     * </pre>
     *
     * <p>商品图<b>本来就该公开</b>（它要显示给所有人看），所以这不是漏洞。
     * 但这句话必须写在这里 —— 否则下一个人看到「有个路径没有鉴权」，
     * 会以为是漏配的，然后「顺手修好」，把商品图全弄成 403。
     *
     * <p>⚠️ 反过来才是真正的红线：<b>上传接口必须挂在【有鉴权】的路径树下面</b>，
     * 也就是 {@code /api/admin/**} 或 {@code /api/shop/**} 之一。
     * 它要是落在 {@code /api/**} 之外（比如跟着 {@code /uploads/**} 走），
     * 就等于「任何人都能往服务器上写文件」——
     * 那是这个功能最大的一次安全边界移动。
     *
     * <p><b>★ 里程碑 12：这句话原来写的是「必须放在 {@code /api/admin/**} 下」，
     * 现在不准确了。</b>因为会员也要能晒图，而会员 token 进不了
     * {@code AdminAuthInterceptor}，所以多了第二条
     * {@link com.example.mall.controller.shop.ImageShopController}。
     *
     * <p>两个上传端点的分工，正好把「边界在哪」这件事说清楚了：
     * <pre>
     *   POST /api/admin/images   → 管理端传商品图/图集  → 管理员 token
     *   POST /api/shop/images    → 会员传评价晒图      → 会员 token
     * </pre>
     * ★ 注意<b>红线没变、只是措辞更准了</b>：真正的要求从来不是「必须在 admin 下」，
     * 而是「必须在一个有人验身份的树里」。原来只有一棵这样的树，所以两句话等价；
     * 现在有两棵，原来那句话就从「正确但过窄」变成了「说了假话」。
     * <b>配置注释和所有其他注释一样，会随着系统长大而悄悄变假。</b>
     *
     * <h3>★ 为什么用 {@code toUri()} 拼路径，而不是手工拼 "file:" + 路径</h3>
     *
     * <p>Windows 上路径长这样：{@code C:\Users\20380\桌面\java\...}。
     * 反斜杠和中文在 URL 里都需要转义，手工拼很容易拼出一个
     * <b>能编译、能启动、但取不到文件</b>的字符串 —— 而且不会有任何报错。
     * {@code toUri()} 会把盘符、分隔符、特殊字符都处理对。
     *
     * <p>⚠️ 一个曾经怀疑过、核对后确认<b>不存在</b>的坑，记在这里免得下次又去怀疑它：
     * 我担心过「目录不存在时 {@code Path.toUri()} 不返回尾斜杠，
     * 导致资源位置解析错位」。实际上 Spring 自己会补 ——
     * {@code ResourceHandlerUtils.initLocationPath} 在位置不以 {@code /} 结尾时
     * 会 {@code concat("/")} 并打一条 WARN。所以尾斜杠不是必须建目录的理由。
     *
     * <p>那为什么还要在启动时建目录？真正的理由只有一条：
     * <b>第一次上传时目录不存在，{@code Files.copy} 会抛 {@code NoSuchFileException}</b>，
     * 而这个异常会被 {@code GlobalExceptionHandler} 的兜底接住，
     * 用户看到的是一句莫名其妙的「系统繁忙，请稍后重试」。
     * 建目录是让这个错误根本没有机会发生。见
     * {@link com.example.mall.service.impl.FileStorageServiceImpl}。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // toAbsolutePath() 是承重的：mall.upload.dir 的默认值是相对路径 "./uploads"，
        // 而 toUri() 对相对路径会抛出异常（拿不到盘符就没法拼出绝对 URI）。
        // normalize() 把 "a/../b" 这种路径折平，让日志里打出来的路径是干净的。
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(root.toUri().toString());
    }
}
