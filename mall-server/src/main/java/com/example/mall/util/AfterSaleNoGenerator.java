package com.example.mall.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 售后单号生成器。格式：{@code AS} + 14 位时间 + 6 位随机，共 <b>22 位</b>。
 *
 * <p>★ 里程碑 17 新增。它和 {@link OrderNoGenerator} 的关系是
 * <b>两份独立的实现，不是「一个带前缀参数的生成器」</b> ——
 * 理由写在下面第二段，那是这个类唯一需要想清楚的地方。
 *
 * <h3>★ 为什么售后单号必须存在（而不是直接用 {@code after_sale.id}）？</h3>
 *
 * <p>和订单号一模一样的三条理由，逐条成立：不能暴露业务量、
 * 不能让人猜到别人的单、它是给外部用的编号（客服报的号、
 * 用户截图里的号）要能独立演进。见 {@code OrderNoGenerator} 的类注释。
 *
 * <h3>★★ 为什么必须带前缀 {@code AS}，而不是「和订单号同一个号码空间」？</h3>
 *
 * <p><b>因为两个不同种类的标识符，不能共用一个号码空间。</b>
 *
 * <p>如果售后单号也是 {@code 20260925005831482913} 这个形状，
 * 那么客服接到一个号来问「这单到哪了」，第一件事是<b>猜它该去哪个表查</b> ——
 * 猜错了就查不到，然后他会得出「系统里没这个号」的结论。
 * 而这个结论是错的，且没有任何一层会提示他查错了表。
 *
 * <p>前缀把这个猜测变成了<b>读一眼就知道</b>：{@code AS…} 是售后单，
 * 其余是订单号。代价只有两个字符。
 * <b>「一个号码属于哪个域」应该是可读的，而不是要查表才知道的。</b>
 *
 * <h3>★★ 为什么不写成 {@code OrderNoGenerator.generate("AS")}？</h3>
 *
 * <p>因为那样「售后单号长什么样」就有了<b>两个定义者</b>：
 * <pre>
 *   OrderNoGenerator          →  格式是「前缀 + 14 位时间 + 6 位随机」
 *   调用方 AfterSaleServiceImpl → 前缀是 "AS"
 * </pre>
 * 于是「售后单号是 22 位、以 AS 开头」这个事实，散在两个文件里，
 * 谁都没有完整地拥有它。哪天有人想让售后单号变成 24 位（随机加 2 位），
 * 他改的是 {@code OrderNoGenerator} —— 而<b>那个类的名字和注释
 * 都在说订单，他不会想到自己正在改售后单号的格式</b>。
 *
 * <p>这正是 {@code OrderNoGenerator} 的类注释里那条纪律的反面应用：
 * <b>一个类不该同时是「订单号的定义者」和「售后单号的定义者」。</b>
 * 所以这里是一份独立的实现，代价是十来行重复代码 ——
 * 而重复的这部分是「时间 + 随机」这个算法，
 * 它是<b>本项目的约定</b>，不是「订单号那个功能」的一部分。
 *
 * <h3>★ 它会撞，撞了怎么办</h3>
 *
 * <p>会撞，概率和订单号同量级（同一秒内 100 个申请约 0.5%）。
 * 兜底也完全一样，两步缺一不可：
 * <pre>
 *   1. 数据库的 uk_after_sale_no 唯一索引 → 保证不会有两张同号的单进库
 *   2. 调用方 catch (DuplicateKeyException) 换一个号重试一次 → 保证这次申请能成功
 * </pre>
 * <b>「加唯一索引」和「处理唯一索引冲突」是两件事。</b>
 * 只加索引不处理，用户会看到一个莫名其妙的系统错误；
 * 只处理不加索引，那就是靠概率防重，迟早出事。
 */
public final class AfterSaleNoGenerator {

    private AfterSaleNoGenerator() {
    }

    /** 售后单号的前缀。★ 它的唯一字面量出处就是这一行 */
    private static final String PREFIX = "AS";

    /** 时间部分的格式：年月日时分秒，共 14 位 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 随机部分的位数 */
    private static final int RANDOM_BOUND = 1_000_000;

    /**
     * 生成一个售后单号。格式：{@code AS} + 14 位时间 + 6 位随机，共 22 位。
     *
     * <p>22 位没有超出 {@code after_sale.after_sale_no} 的 {@code VARCHAR(32)}。
     * <b>生成规则的位数必须小于等于数据库列的宽度</b> ——
     * 改格式的时候（比如把随机部分从 6 位加到 10 位）很容易忘记回来看列宽，
     * 所以这句话写在这里当提醒（{@code OrderNoGenerator} 里也有一份同样的提醒）。
     */
    public static String generate() {
        return PREFIX
                + LocalDateTime.now().format(TIME_FORMAT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(RANDOM_BOUND));
    }
}
