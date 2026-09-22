package com.example.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体 —— 与数据库 {@code product} 表<b>一一对应</b>。
 *
 * <p><b>Entity / DTO / VO 三者为什么要分开？</b>这是分层设计里很关键的一点。
 *
 * <p>新手常见的做法是「一个 Product 类从头传到尾」：前端传它、Service 收它、
 * Mapper 存它、Controller 返回它。看着省事，但会带来三个问题：
 *
 * <ol>
 *   <li><b>安全问题</b>：前端提交表单时如果多传一个 {@code "stock": 999999}，
 *       直接映射到 Entity 就会把库存改了。分开之后 DTO 里根本没有这个字段，
 *       多传的字段会被自动忽略 —— 这叫「参数白名单」</li>
 *   <li><b>职责混乱</b>：数据库加了个内部字段（比如 {@code is_deleted} 逻辑删除标记），
 *       实体直接返回给前端就会泄露内部实现</li>
 *   <li><b>职责不同</b>：查询列表时需要「分类名称」，但 {@code product} 表里
 *       只有 {@code category_id}。这个字段只能靠 join 出来，Entity 装不下</li>
 * </ol>
 *
 * <p>所以三者分工是：
 * <table border="1">
 *   <tr><th>类型</th><th>对应物</th><th>用途</th></tr>
 *   <tr><td>Entity</td><td>数据库表</td><td>只在 Service ↔ Mapper 之间传递</td></tr>
 *   <tr><td>DTO</td><td>前端请求</td><td>接收参数，只包含允许前端传的字段</td></tr>
 *   <tr><td>VO</td><td>前端响应</td><td>返回数据，可包含 join 出来的额外字段</td></tr>
 * </table>
 */
@Data
public class Product {

    private Long id;

    /** 所属分类 id */
    private Long categoryId;

    private String name;

    /**
     * 金额用 BigDecimal，不用 double。
     *
     * <p>double 是二进制浮点数，存不下 0.1 这类十进制小数，
     * 会出现 {@code 0.1 + 0.2 == 0.30000000000000004} 这种结果。
     * 涉及钱的运算必须用 BigDecimal，这是硬规矩。
     */
    private BigDecimal price;

    /** 库存数量 */
    private Integer stock;

    /** 封面图 URL */
    private String cover;

    private String description;

    /** 状态：1=上架 0=下架 */
    private Integer status;

    /**
     * 创建时间用 LocalDateTime 而不是 java.util.Date。
     * 后者是 JDK 1.0 的老 API，设计有缺陷（可变、线程不安全、月份从 0 开始），
     * 新代码一律用 java.time 包下的类型。
     *
     * <p>MyBatis 从 MySQL 的 DATETIME 列映射到这个类型不需要额外配置。
     */
    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
