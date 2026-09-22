package com.example.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商城管理系统 —— 启动类。
 *
 * <p>{@code @SpringBootApplication} 是三个注解的组合：
 * <ul>
 *   <li>{@code @SpringBootConfiguration} —— 声明这是一个配置类</li>
 *   <li>{@code @EnableAutoConfiguration} —— 自动配置，Spring Boot 的"魔法"来源。
 *       它会扫描 classpath，发现你引了 web 就配 Tomcat，引了 MySQL 驱动就配数据源，
 *       你什么都不用写。理解它「按 classpath 推断你要什么」这个思路，就理解了 Spring Boot</li>
 *   <li>{@code @ComponentScan} —— 扫描<b>本类所在包及其子包</b>下的
 *       {@code @Component/@Service/@Controller} 等注解并注册为 Bean。
 *       这就是为什么启动类必须放在最外层包：放错位置会导致 Controller 扫不到</li>
 * </ul>
 *
 * <p>{@code @MapperScan} 告诉 MyBatis 去哪里找 Mapper 接口。
 * 没有它，每个 Mapper 接口上都得自己写 {@code @Mapper} 注解，麻烦且容易漏。
 *
 * <h3>★ {@code @EnableScheduling} 是里程碑 9 加的</h3>
 *
 * <p>它开启 Spring 的定时任务支持。<b>没有这个注解，{@code @Scheduled}
 * 会【完全不生效】—— 不报错、不警告，方法就是再也不会被调用。</b>
 * 这是个很容易踩的坑：注解写好了、类也注册成 Bean 了、
 * 启动日志一片正常，但任务从来不跑。
 *
 * <p>（顺带一提，这个坑和 {@code @Transactional} 加在私有方法上、
 * 或者自己调自己不经过代理，是同一类问题：
 * <b>「声明式注解失效是静默的」</b>。读代码时看到这类注解，
 * 一定要顺手确认一下「让它生效的那个开关在哪」。）
 *
 * <p>加了之后 Spring 会创建一个单线程的调度器，按 {@code @Scheduled}
 * 的配置去调。所以定时任务的方法是<b>串行执行</b>的 ——
 * 本项目只有一个任务，不存在互相影响的问题。
 */
@SpringBootApplication
@MapperScan("com.example.mall.mapper")
@EnableScheduling
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
