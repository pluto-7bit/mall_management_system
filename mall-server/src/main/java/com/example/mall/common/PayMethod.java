package com.example.mall.common;

import java.util.Set;

/**
 * 支付方式常量。
 *
 * <p>和 {@code OrderStatus} / {@code ResultCode} 同一个形状：{@code final} 类、
 * 私有构造、只放常量和一个给日志用的 {@code text()}。
 * {@code final} + 私有构造是为了<b>让人没法 new 它、也没法继承它</b> ——
 * 一个纯常量类被实例化出来本身就是没有意义的。
 *
 * <h3>★ 为什么存的是码（ALIPAY）而不是中文（支付宝）？</h3>
 *
 * <p>和 {@code OrderStatus} 的理由完全一样，而且这里更明显：
 * {@code pay_method} 是<b>要写进数据库</b>的。
 * 如果存中文，「支付宝」哪天想改成「支付宝支付」，
 * 库里已有的历史数据就和新写入的对不上了 —— 而且不会有任何报错，
 * 只是某天统计「各支付方式占比」时发现多了个从没见过的分类。
 *
 * <p><b>能被历史数据引用的值，就不再是你的了。</b>
 *
 * <h3>★ 为什么这里【不】允许扩展成任意字符串？</h3>
 *
 * <p>因为「校验」这件事必须有一份<b>唯一的、权威的清单</b>。
 * 如果只靠 DTO 上的一个正则去限制，那么将来加一种支付方式，
 * 需要改的是「所有写了正则的地方」—— 漏一处就是一个不一致。
 * 把清单放在这个类里、并且让 Service 从这里校验，
 * 加支付方式就只需要改这一个文件。
 *
 * <p>（顺带一提：真正需要「可扩展」的场景应该建一张字典表，
 * 让运营自己加。但本项目只有三种，写死在代码里更简单也更快 ——
 * <b>不要为了「将来可能要扩展」提前引入一张表。</b>）
 */
public final class PayMethod {

    private PayMethod() {
    }

    /** 支付宝 */
    public static final String ALIPAY = "ALIPAY";

    /** 微信支付 */
    public static final String WECHAT = "WECHAT";

    /** 银行卡 */
    public static final String BANK = "BANK";

    /**
     * 全部合法的支付方式。
     *
     * <p><b>★ 这个集合是「校验的唯一依据」</b>，{@code OrderServiceImpl.pay}
     * 用它来判断传入的值合不合法。前端收银台上那几个选项也应该由后端
     * 的这份清单驱动 —— 但本项目前端是写死的三个常量，
     * 见 {@code mall-shop/src/utils/orderStatus.js} 里关于「抄来的常量」那段说明。
     *
     * <p>用 {@code Set.of(...)} 而不是 {@code Arrays.asList(...)}：
     * 前者不可变（想往里加会抛异常，而不是静默成功），
     * 而且 {@code contains} 是 O(1)。
     * <b>给「校验依据」用的集合，一定不能是能被改的。</b>
     */
    private static final Set<String> ALL = Set.of(ALIPAY, WECHAT, BANK);

    /**
     * 这个支付方式是否合法。
     *
     * @param method 待校验的值，可以是 null
     * @return 合法返回 true；null 和未知值都返回 false
     */
    public static boolean isValid(String method) {
        return method != null && ALL.contains(method);
    }

    /**
     * 把支付方式码转成中文，<b>给日志用</b>。
     *
     * <p>不给前端用的理由见 {@code OrderStatus.text()} 的注释 —— 那条纪律
     * （<b>码给前端判断、中文给日志</b>）是全局的，不是 {@code OrderStatus} 一处的特例。
     * 前端的展示文案由前端自己维护（{@code mall-shop/src/utils/orderStatus.js}）。
     */
    public static String text(String method) {
        if (method == null) {
            return "未支付";
        }
        return switch (method) {
            case ALIPAY -> "支付宝";
            case WECHAT -> "微信支付";
            case BANK -> "银行卡";
            default -> "未知方式(" + method + ")";
        };
    }
}
