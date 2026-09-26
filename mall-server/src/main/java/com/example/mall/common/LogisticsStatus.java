package com.example.mall.common;

/**
 * 物流轨迹节点状态常量。★ 里程碑 18 新增。
 *
 * <p>和 {@code OrderStatus} / {@code AfterSaleStatus} 同一个形状：
 * {@code public static final int} + 一个只给日志用的 {@code text(int)}。
 * 不写魔法数字的理由见 {@code OrderStatus} 类注释。
 *
 * <h3>★ 取值定下来之后不要再改</h3>
 *
 * <p>理由逐字同 {@code OrderStatus}：这些数字<b>会写进数据库</b>。
 * 重排已有数字会让线上所有老节点的含义静默改变。
 * 要加就往后追加（6、7……），废弃的留着并标「已废弃」。
 *
 * <h3>★★ 为什么这里的节点状态必须是【码】，而承运商和单号是【自由文本】</h3>
 *
 * <p>这一轮同一张表里有一组很好的对照，而依据不是「哪个更规范」，
 * 而是<b>谁读它、读它来做什么</b>：
 *
 * <table border="1">
 *   <caption>同一批新字段里两个相反的决定</caption>
 *   <tr><th>字段</th><th>形态</th><th>依据</th></tr>
 *   <tr>
 *     <td>{@code logistics_company} / {@code tracking_no}</td>
 *     <td><b>自由文本</b></td>
 *     <td>它们是<b>外部世界的标识符</b>，本来就没有一张能穷举的表。
 *         做码表只会得到一张永远在补的「全国快递公司」字典，
 *         然后漏掉用户用的那一家。（论证来自 {@code AfterSaleReturnDTO}）</td>
 *   </tr>
 *   <tr>
 *     <td>本类的 {@code status}</td>
 *     <td><b>码</b></td>
 *     <td>下面三条理由，每条都指向一个具体的失败</td>
 *   </tr>
 * </table>
 *
 * <p><b>① 前端要按它画图标和颜色</b>
 * （已揽收 📦 灰 / 运输中 🚚 蓝 / 派送中 🛵 橙 / 已签收 ✅ 绿 / 异常 ⚠️ 红）。
 * 自由文本没法画 —— 那就只能所有节点一个样子，轨迹时间线退化成一段流水账。
 *
 * <p><b>② {@link #SIGNED} 是「自动完成订单」的触发条件</b>。
 * 触发条件必须<b>可判定</b>，而不能是「描述里有没有『已签收』三个字」——
 * 那是<b>拿一句人写的中文去猜状态</b>，而它一定会在有人写
 * 「已签收失败，改约明天」的那天静默出错。
 *
 * <p><b>③ 码表能被守卫脚本看着</b>（{@code sql/test-frontend-format.py} 的规则 5）。
 * 自由文本谁都管不了。
 *
 * <h3>★★ 不写 {@code isFinal(s) { return s >= 4; }}</h3>
 *
 * <p>{@link #EXCEPTION} <b>恰好证伪它</b>：{@code EXCEPTION = 5}，
 * 按数字大小判会得出「异常是终态」—— 而异常<b>不是</b>终态
 * （快件能找到、能继续派送、能签收）。
 *
 * <p>这和 {@code AfterSaleStatus} 拒绝 {@code isClosed(s) { return s >= 3; }}
 * 是<b>同一条规矩</b>，只是那一次靠的是「设想一个未来的 6」，
 * 而这一次<b>手边就有一个现成的反例</b>。
 *
 * <p>所以要用就写显式 {@code switch}。本项目当前<b>没有任何地方需要判断
 * 「这个节点是不是终态」</b> —— 所以本类干脆不提供这个方法。
 * 需要的时候再加，加的时候写 switch。
 */
public final class LogisticsStatus {

    private LogisticsStatus() {
    }

    /** 已揽收：快递公司收到了包裹 */
    public static final int PICKED_UP = 1;

    /** 运输中：在往目的地走的路上 */
    public static final int IN_TRANSIT = 2;

    /** 派送中：到了目的地网点，快递员在送 */
    public static final int DELIVERING = 3;

    /**
     * 已签收：买家拿到了。★ <b>它会把订单自动推到「已完成」</b>。
     *
     * <p>这是本码表里唯一一个<b>带副作用</b>的值 ——
     * 见 {@code LogisticsServiceImpl.addTrace}。
     * 判断依据是<b>这个常量本身</b>（{@code dto.getStatus() == SIGNED}），
     * 不是描述里的中文，理由见类注释第 ② 条。
     *
     * <p>⚠️ 副作用是不可撤销的：删掉这条签收节点<b>不会</b>把订单退回「已发货」，
     * 见那个方法的注释。
     */
    public static final int SIGNED = 4;

    /**
     * 异常：快件出了问题（破损、丢件、拒收、改约……）。
     *
     * <p>★ <b>它不是终态</b> —— 异常之后还能继续派送、还能签收。
     * 这个值的存在正是本类拒绝「按数字大小判终态」的原因。
     */
    public static final int EXCEPTION = 5;

    /**
     * ★★ <b>刻意没有 {@code UNKNOWN = 0} 这个"兜底常量"</b>
     * （本类初稿写了它，然后删掉了）。两个理由：
     *
     * <p><b>① 没有任何代码会引用它。</b>
     * 兜底逻辑在 {@code text()} 的 {@code default} 分支和
     * {@link #isValid} 的 {@code default} 分支里 ——
     * 那两处都不需要这个名字。而一个<b>零引用的 public 常量</b>
     * 会立刻在三个地方惹麻烦：<br>
     * 　· 规则 5（{@code test-frontend-format.py}）会要求前端字典也抄一份
     * 　　{@code UNKNOWN: 0}，而前端没有任何地方会用到它；<br>
     * 　· 规则 6 会要求管理端的下拉里<b>也有</b>「未知状态」这一项 ——
     * 　　「管理员手选一个『未知』」是没有意义的；
     * 　· 将来有人看到它，会合理地以为「有个 0 要处理」。
     *
     * <p><b>② 和兄弟类不一致。</b>
     * {@code OrderStatus} 和 {@code AfterSaleStatus} 都<b>没有</b>兜底常量，
     * 它们的 {@code text()} 直接 {@code default -> "未知状态(...)"}。
     * 本类照同一形状写就行 —— 多一个常量不是"更周到"，是多一份要维护的东西。
     *
     * <p>★ 判据还是那一条（里程碑 16 立的第 ③ 条）：<b>有读者才加列/加常量。</b>
     */

    /**
     * 把节点状态码转成中文，<b>给日志用</b>。
     *
     * <p><b>为什么不给前端用？</b>因为前端需要的是「按状态画不同的图标和颜色」，
     * 那就必须拿到<b>状态码本身</b>去判断，而不是一句中文。
     * 前端拿中文比较的话，后端哪天改一个字，前端就静默失效了 ——
     * 完整论证见 {@code OrderStatus.text}。
     */
    public static String text(int status) {
        return switch (status) {
            case PICKED_UP -> "已揽收";
            case IN_TRANSIT -> "运输中";
            case DELIVERING -> "派送中";
            case SIGNED -> "已签收";
            case EXCEPTION -> "异常";
            default -> "未知状态(" + status + ")";
        };
    }

    /**
     * 这个节点状态码是不是一个定义过的值。形状同 {@code AfterSaleType.isValid}。
     *
     * <p>★ 新增节点的 {@code status} 来自请求体，是<b>客户端说了算</b>的，
     * 所以必须校验。不校验的后果很安静：传一个 {@code status = 99} 进来，
     * <b>落库成功</b>，然后前端 {@code logisticsStatusLabel(99)} 走 default ——
     * 时间线上显示「未知状态」。页面正常、控制台干净。
     *
     * <p>★★ <b>用显式 {@code switch} 而不是 {@code status >= 1 && status <= 5}</b>：
     * 后者就是本类注释里拒绝的那种「按数字大小当语义」。
     * 这里它<b>恰好</b>能得出正确答案（1~5 确实是全部），
     * 但那是巧合 —— 一旦往后追加 {@code 6 = 退回中}，
     * 范围判断会默默地连新值一起放行，而这份「放行」是谁写的、为什么，没人说得清。
     * <b>写显式 switch 的代价是多一行，收益是加新码时有一个必须停下来的地方。</b>
     *
     * <p>★ 白名单校验放在这里、而不是 DTO 的 {@code @Min/@Max} 上，
     * 是本项目的既定分层（见 {@code AfterSaleApplyDTO} 的注释）：
     * <b>DTO 管「填了没有」，码表管「填的对不对」。</b>
     */
    public static boolean isValid(Integer status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case PICKED_UP, IN_TRANSIT, DELIVERING, SIGNED, EXCEPTION -> true;
            default -> false;
        };
    }
}
