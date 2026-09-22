package com.example.mall.controller;

import com.example.mall.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口 —— 里程碑 1 的验收点。
 *
 * <p>它存在的意义是：一次请求就能验证整条链路是否打通
 * <pre>
 *   浏览器/curl → Tomcat → Spring MVC → Controller → JdbcTemplate → MySQL
 * </pre>
 * 这条链路任何一环断了，这个接口都会失败。所以它能跑通，就说明环境没问题。
 *
 * <p><b>{@code @RestController} = {@code @Controller} + {@code @ResponseBody}</b>
 * 后者表示「方法返回值直接序列化成 JSON 写进响应体」，而不是当成页面名去找模板。
 * 前后端分离的项目里，所有 Controller 都用这个注解。
 *
 * <p><b>{@code @RequestMapping("/api/health")}</b> 加在类上，
 * 类里所有方法的路由都会带上这个前缀，避免每个方法重复写。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入。
     *
     * <p>Spring 4.3 之后，类只有一个构造器时可以省略 {@code @Autowired}，Spring 会自动注入。
     * 相比字段注入（在字段上写 {@code @Autowired}），构造器注入有三个实际好处：
     * <ol>
     *   <li>字段可以声明为 {@code final}，保证不会被中途改掉，线程安全</li>
     *   <li>依赖关系写在构造器签名上，一眼能看出这个类依赖谁；字段注入要翻遍全文</li>
     *   <li>脱离 Spring 容器也能 new 出来做单元测试</li>
     * </ol>
     * Spring 官方也推荐构造器注入。
     */
    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * GET /api/health/ping
     *
     * <p>顺带查一下数据库里的商品数量，用来证明数据库连接也是通的
     * —— 只返回 "pong" 的话，数据源配错了你也看不出来。
     */
    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        Integer productCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product", Integer.class);

        // 用 LinkedHashMap 保证返回的 JSON 字段顺序和插入顺序一致，方便阅读
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("application", "mall-server");
        info.put("productCount", productCount);

        return Result.success(info);
    }
}
