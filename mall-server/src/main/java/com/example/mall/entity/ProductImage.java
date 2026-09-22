package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品图集实体 —— 与数据库 {@code product_image} 表一一对应。
 *
 * <p>一行 = 某个商品的一张图，以及它在图集里排第几位。
 *
 * <h3>★ 为什么是独立一张表，而不是给 product 加一个 images 列？</h3>
 *
 * <p>理由和 {@link OrderItem} 那段「一对多」的判断完全一样：
 * 一个商品的图片张数是<b>不定</b>的，所以它不属于 product 表。
 *
 * <p>而这里还多一层 —— 图集<b>有顺序，而且用户可以改顺序</b>。
 * 如果把图片塞进一个列（JSON 或者逗号拼接的字符串）：
 * <pre>
 *   「把第 3 张图往上挪一位」= 读出整个字符串 → 在 Java 里拆开
 *                            → 换位 → 拼回去 → 整列覆盖写
 * </pre>
 * 这是一个<b>读改写</b>操作，两个人同时编辑就会互相覆盖，
 * 而 SQL 层面完全看不出来发生过这件事（没有报错、没有冲突、就是有一边的改动没了）。
 * 独立成行之后，顺序就是 {@code sort_no} 一个列的值，改它是一条 UPDATE。
 *
 * <h3>★ 这张表和 product 之间没有外键</h3>
 *
 * <p>这是<b>全库一致的约定</b>（8 张表一个 FOREIGN KEY 都没有），
 * 详细的理由写在 {@code sql/mall.sql} 里那段「全库约定」注释中。
 * 一句话概括：加外键之后「删商品」会变成一条可能失败的语句，
 * 而数据库约束拦下来的错误只能翻译成一句「系统繁忙」。
 *
 * <p>⚠️ 代价是：<b>删商品的顺序必须是「先删图集行，再删商品行」</b>，
 * 而这个顺序只能靠写代码的人记住。见
 * {@link com.example.mall.service.impl.ProductServiceImpl#delete}。
 *
 * <h3>★ 它不是快照，这和 order_item 不一样</h3>
 *
 * <p>{@link OrderItem} 的 {@code productName} / {@code price} 是<b>下单当时的快照</b>，
 * 商品改名改价都不影响历史订单。图集恰好<b>相反</b>：
 *
 * <p>{@code url} 存的是「这个商品现在的图是哪一张」，商品换了图，
 * 详情页<b>就应该</b>显示新图 —— 图集是<b>当前状态</b>，不是历史事实。
 * 所以这里全都是实时的引用，没有任何要冻结的东西。
 * （顺带：这也是为什么 {@code product.cover} 和图集可以指向同一个路径 ——
 * 它们都是引用，不是所有权。）
 */
@Data
public class ProductImage {

    private Long id;

    /** 所属商品 id */
    private Long productId;

    /**
     * 图片地址，形如 {@code /uploads/2026/09/<uuid>.png}。
     *
     * <p>★ 存的是<b>URL 路径</b>，不是文件名，也不是这台机器的绝对路径。
     * 换台机器、换个部署目录，绝对路径会全部指错，而这个值已经写进数据库了。
     */
    private String url;

    /**
     * 展示顺序，从 0 开始。
     *
     * <p>★ 读取时<b>必须</b>配一个决胜列，即 {@code ORDER BY sort_no, id}。
     * 因为 {@code sort_no} 是可以重复的（默认值都是 0，
     * 将来批量导入的数据也会全是 0），而<b>顺序相同行的返回顺序是不保证的</b> ——
     * 同一份数据两次查出来顺序不一样，用户会以为是 bug。
     * 这条规矩和订单列表的 {@code ORDER BY create_time DESC, id DESC} 是同一条。
     */
    private Integer sortNo;

    private LocalDateTime createTime;
}
