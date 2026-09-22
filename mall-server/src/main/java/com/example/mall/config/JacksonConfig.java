package com.example.mall.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 日期格式配置。
 *
 * <p><b>这个类解决的是一个很容易踩的坑。</b>
 *
 * <p>在 {@code application.yml} 里写：
 * <pre>
 *   spring:
 *     jackson:
 *       date-format: yyyy-MM-dd HH:mm:ss
 * </pre>
 * 你会以为所有日期都按这个格式返回了，但 <b>对 LocalDateTime 完全无效</b>，
 * 实际返回的还是 {@code "2026-09-21T20:42:25"} 这种带 T 的 ISO 格式。
 *
 * <p><b>原因</b>：{@code spring.jackson.date-format} 底层设置的是
 * {@code ObjectMapper.setDateFormat(...)}，而它只影响
 * {@code java.util.Date} 和 {@code Calendar} 这两个老类型。
 * {@code LocalDateTime} 属于 {@code java.time} 包，由 Jackson 的
 * JSR-310 模块（JavaTimeModule）单独处理，走的是另一套序列化器，
 * 默认输出 ISO-8601 格式。
 *
 * <p>所以对 {@code java.time} 类型，必须像下面这样显式注册序列化器。
 *
 * <p>顺带一提，这也是为什么 {@code java.util.Date} 该被淘汰 ——
 * 新老两套 API 混在一起，配置都得配两份，纯属自找麻烦。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * {@code Jackson2ObjectMapperBuilderCustomizer} 是 Spring Boot 提供的扩展点，
     * 让你能在它自动配置好的 ObjectMapper 上「补几笔」，
     * 而不是自己造一个 ObjectMapper 覆盖掉默认配置
     * （那样会丢掉 Spring Boot 已经帮你配好的一堆东西）。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsr310DateTimeCustomizer() {
        return builder -> {
            // 序列化：Java 对象 → JSON，即接口返回给前端的时候
            builder.serializerByType(LocalDateTime.class,
                    new LocalDateTimeSerializer(DATE_TIME_FORMATTER));

            // 反序列化：JSON → Java 对象，即前端提交请求体的时候。
            // 这个也要配，否则前端把 "2026-09-21 20:42:25" 传回来时会解析失败，
            // 报 "Cannot deserialize value of type LocalDateTime"。
            // 序列化和反序列化必须用同一个格式，只配一半是常见的疏漏
            builder.deserializerByType(LocalDateTime.class,
                    new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
        };
    }
}
