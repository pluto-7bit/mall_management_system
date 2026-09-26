package com.example.mall.common;

/**
 * 售后原因码。★ 里程碑 17 新增。
 *
 * <h3>★★ 为什么是「码」而不是一段自由文本？</h3>
 *
 * <p>因为用户填的那句话，和运营想知道的「这批退货到底因为什么」
 * 是<b>两件不同的事</b>：
 * <pre>
 *   自由文本  →  「不好看」「有点丑」「不喜欢这个颜色」——
 *                人看得出来是同一件事，SQL 看不出来，
 *                运营永远做不出「按原因统计」这张表
 *   原因码    →  6 个字枚举，能 GROUP BY，能画饼图
 * </pre>
 * 所以两者都要：{@code reason} 是码（可统计），
 * {@code description} 是自由文本（补充说明，可选 255 字）。
 *
 * <p><b>★ 那前端为什么要显示一个下拉框而不是让用户自己写？</b>
 * 因为下拉框是<b>这个码表的唯一来源</b>——
 * 用户在界面上只能选到定义过的值。自由文本第一个月就会出现
 * 「质量」和「質量」和「qualité」三行。
 *
 * <h3>⚠️ 校验仍然必须在服务端做</h3>
 *
 * <p>下拉框管的是「用户能不能选到别的」，管不了「有没有人绕过页面直接调接口」。
 * 所以 {@code AfterSaleServiceImpl} 里有一句 {@link #isValid(Integer)} ——
 * 不写它的后果很安静：落进库里的 {@code reason = 99}，
 * 管理端页面显示不出来原因（前端字典里没有 99），
 * 而<b>没有任何一层会报错</b>。
 *
 * <p>取值和 {@code OrderStatus} 一样：<b>定下来就不再改</b>，
 * 因为已经写进历史数据了。要加就在后面追加。
 */
public final class AfterSaleReason {

    private AfterSaleReason() {
    }

    /** 不喜欢 / 不想要了（最常见的「无理由」退款） */
    public static final int DISLIKE = 1;

    /** 商品质量问题（破损、有瑕疵、失效） */
    public static final int QUALITY = 2;

    /** 商品与描述不符（发错货、色差、规格不对） */
    public static final int NOT_AS_DESCRIBED = 3;

    /** 少件 / 漏发 */
    public static final int MISSING = 4;

    /** 物流问题（太慢、一直没收到） */
    public static final int LOGISTICS = 5;

    /** 其他（必须配合 description 说明） */
    public static final int OTHER = 6;

    /**
     * 这个原因码是不是一个定义过的值。
     *
     * <p>★ 和 {@code AfterSaleType.isValid} 是同一条：请求体里的值
     * 是客户端说了算的，而它会写进数据库、会出现在管理端页面上。
     * 不校验就是让客户端往我们的枚举里塞任意数字。
     */
    public static boolean isValid(Integer reason) {
        return reason != null
                && reason >= DISLIKE
                && reason <= OTHER;
    }

    /**
     * 把原因码转成中文，<b>只给日志用</b>。
     *
     * <p>⚠️ 这个方法和别的 {@code text()} 有一个重要的差别：
     * 它是<b>要给人看的、会出现在管理端页面上的文案的源头之一</b>，
     * 但管理端<b>不用它</b> —— 管理端的文案在
     * {@code mall-web/src/utils/afterSaleStatus.js} 的 {@code AFTER_SALE_REASONS} 里
     * （用户端在 {@code mall-shop/src/utils/afterSaleStatus.js}，那份是刻意重复的）。
     * 这里这份只进日志。
     *
     * <p>两处文案不一致会怎样？<b>只会让日志和页面读起来不一样，
     * 不会产生任何错误行为</b> —— 这正是「展示文案的重复可以接受、
     * 业务规则的重复不行」那条分界线所在。
     */
    public static String text(int reason) {
        return switch (reason) {
            case DISLIKE -> "不喜欢/不想要";
            case QUALITY -> "商品质量问题";
            case NOT_AS_DESCRIBED -> "商品与描述不符";
            case MISSING -> "少件/漏发";
            case LOGISTICS -> "物流问题";
            case OTHER -> "其他";
            default -> "未知原因(" + reason + ")";
        };
    }
}
