package com.example.mall.entity;

import lombok.Data;

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

    // ==========================================================================
    // ★★ 里程碑 15 阶段 6：price / stock 两个字段【已从这里删除】。
    //
    //   它们曾经是这张表上最重要的两个字段，阶段 6 起不属于这里了 ——
    //   价格和库存的唯一真源是 product_sku，product 上不再留任何副本
    //   （见那一行的 {@code spec_schema} 下面那段论证）。
    //
    //   ★ 删掉它们【不只是删两个字段】，它删掉的是「第二条写入路径」：
    //     只要这个类上有 price，就会有人写 product.setPrice(...)，
    //     而那句代码在「库存被并发扣减」的场合下必然把汇总写成错的数。
    //     引用的地方现在会编译不过 —— 这正是想要的效果。
    //
    //   ⚠️ 这段注释【故意留在这里】，不删。
    //     一个类上「少了什么」和「为什么少」是两条不同的信息，
    //     前者 git 能看到，后者只能写在这里。
    //
    //   ★ 顺带一条不能跟着它搬走的规矩：金额一律用 {@code BigDecimal}，
    //     不用 double（double 是二进制浮点数，存不下 0.1，
    //     会出现 {@code 0.1 + 0.2 == 0.30000000000000004}）。
    //     这条规矩跟着价格走，现在归 {@code ProductSku.price} 管 ——
    //     所以本类里连 {@code java.math.BigDecimal} 的 import
    //     都一起删掉了：**一个类 import 了什么，就是它负责什么。**
    // ==========================================================================

    /** 封面图 URL */
    private String cover;

    private String description;

    /**
     * 这件商品有哪些规格 —— 规范化 JSON，如
     * {@code [{"name":"颜色","values":["黑","白"]}]}（★ 里程碑 15 新增）。
     *
     * <p>无规格的商品是 {@code "[]"}。
     *
     * <p>⚠️ <b>阶段 6 起库里不会再出现 {@code NULL}</b> ——
     * {@code migration-13b} 把阶段 1 回填出来的那 100 行的 {@code NULL}
     * 一律改成了 {@code '[]'}。但 {@code SpecJson.schemaOf} 仍然把
     * {@code null} 当成「无规格」处理，这一条<b>不要顺手删掉</b>：
     * 它现在防的不是老数据，而是「将来某条 INSERT 又漏了这一列」——
     * 那时读取端至少不会 {@code NullPointerException}。
     *
     * <p><b>为什么规格定义要存在商品上，而不是从 SKU 行推导？</b>
     * 因为<b>规格值的显示顺序除了这一列没有别的地方可存</b>。
     * SKU 行的 id 是插入顺序，管理员把「256G」拖到「128G」前面再保存，
     * 从 SKU 行推导出来的顺序纹丝不动 —— 一个彻底的静默失败。
     * 而「删了重建」来体现新顺序，会打断 {@code order_item.sku_id} 的引用。
     */
    private String specSchema;

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
