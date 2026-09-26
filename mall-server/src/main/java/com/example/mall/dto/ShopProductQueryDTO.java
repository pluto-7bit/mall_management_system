package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 用户端商品列表的查询条件。
 *
 * <p><b>★ 它和管理端的 {@link ProductQueryDTO} 只差一个字段，却是刻意分开的两个类。</b>
 *
 * <p>对比一下：
 * <pre>
 *   ProductQueryDTO（管理端）：name / categoryId / <b>status</b> / 分页
 *   ShopProductQueryDTO（用户端）：keyword / categoryId / <b>sort</b> / 分页
 * </pre>
 *
 * <p>最关键的是那个 <b>status</b>。
 *
 * <p>用户端<b>只能看到上架商品</b>，所以这个类里根本没有 status 字段 ——
 * 不是「不传」，是<b>结构上不存在</b>，Service 想漏都漏不掉。
 *
 * <p>如果图省事复用 {@code ProductQueryDTO}，会出现什么？
 * 前端只要请求 {@code /api/shop/products?status=0}，
 * 就能把商家下架的商品全翻出来 ——
 * 而下架往往是因为「质量有问题」「图片侵权」「等换季再上」，
 * 属于不该公开的经营信息。
 *
 * <p>更糟的是，这类漏洞<b>不会报错、不会有异常日志</b>，
 * 页面一切正常，只是多返回了一些本不该返回的数据。
 * 没有测试专门去验的话，可能几年都不会有人发现。
 *
 * <p><b>所以「能不能传某个参数」这件事，最好在类型层面就解决掉，
 * 而不是靠 Service 里的一句 if 去挡。</b>
 * 结构上做不到的事，比代码里记得去做的事，可靠得多。
 *
 * <p>（那 Service 里还需不需要再检查一遍？要，但性质变了 ——
 * 那里是「强制加上 status=1 这个条件」，是查询语句的一部分，
 * 而不是「校验前端有没有乱传」。两件事的可靠程度完全不同。）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ShopProductQueryDTO extends PageQueryDTO {

    /**
     * 搜索关键词，对商品名做模糊匹配。
     *
     * <p><b>为什么叫 keyword 而不是像管理端那样叫 name？</b>
     *
     * <p>因为这是搜索框里的内容，不是「商品名称」这个字段。
     * 名字差一点，含义差很多：
     * <ul>
     *   <li>{@code name} 暗示「我要按名称字段精确筛选」</li>
     *   <li>{@code keyword} 暗示「这是用户随手输入的一串字，
     *       具体匹配哪些字段由后端决定」</li>
     * </ul>
     *
     * <p>现在它只匹配商品名，但等以后要做「搜索商品名或描述」
     * 甚至接入全文索引时，改的是 SQL，
     * <b>而字段名一直是对的，不用跟着改</b>。
     * 命名上留出这点余地，成本是零。
     */
    private String keyword;

    /**
     * 分类 id，为 null 时不筛选（等于「全部分类」）。
     *
     * <p>★ 这是<b>用户传进来的那个值</b>，不是最终用来筛 SQL 的值 ——
     * 里程碑 16 起，点「手机数码」要连同它的子分类一起筛。
     */
    private Long categoryId;

    /**
     * ★ 真正进 SQL 的筛选条件：{@code categoryId} 自己 + 它<b>所有后代</b>的 id。
     *
     * <p>和管理端的 {@code ProductQueryDTO.categoryIds} 是<b>同一个概念</b>，
     * 但它是另一个类上的另一个字段 —— 这正是本项目「两个端各有一套 DTO」
     * 这条约定要付的账。所以这里必须把话说全，否则下一个读代码的人
     * 会以为是复制粘贴时留下的残骸：
     *
     * <ul>
     *   <li><b>值由 Service 填，且无条件覆盖</b>（{@code ShopProductServiceImpl.page}）。
     *       URL 上直接传 {@code ?categoryIds=1,2} 会被算出来的结果整个盖掉，
     *       所以它不是一条绕过规则的入口。</li>
     *   <li><b>算了些什么，由谁算</b>：{@code CategoryService.selfAndDescendantIds()} ——
     *       ★ 管理端和用户端<b>必须调同一个方法</b>。如果只有一端含后代，
     *       同一件商品在两个端上的件数会对不上（管理端 20 件、用户端 28 件），
     *       而运营会先怀疑后台统计错了，不会先怀疑这是两个不同的规则。</li>
     *   <li><b>漏填 = 筛选被静默忽略</b>，接口 200、返回全部商品。
     *       哨兵断言见 {@code ProductQueryDTO} 里那段说明（用不存在的分类 id 筛 → 必须空页）。</li>
     * </ul>
     */
    private List<Long> categoryIds;

    /**
     * 排序方式。
     *
     * <p>取值必须是下面四个常量之一，其他一律按 {@link #SORT_DEFAULT} 处理。
     *
     * <p><b>★ 为什么这里用字符串而不是直接传 SQL 片段？</b>
     *
     * <p>因为排序字段没法用 {@code #{}} 占位符。想想 SQL 长什么样：
     * <pre>
     *   SELECT ... ORDER BY <b>price</b> ASC     ← 价格在这里是个「标识符」，不是「值」
     *   SELECT ... WHERE price = <b>?</b>        ← 这里 price 是列名，? 是值
     * </pre>
     * 预编译占位符只能替换<b>值</b>，替换不了列名。
     * 所以动态排序只能用 {@code ${}} 拼字符串 —— 而 {@code ${}} 是 SQL 注入的入口。
     *
     * <p>那怎么办？<b>用白名单把输入映射成有限的几个安全值</b>，
     * 前端永远碰不到 SQL 片段本身。见 {@link #normalize()} 和
     * {@code ProductMapper.xml} 里的 {@code <choose>}。
     *
     * <p>⚠️ 攻击者会尝试传 {@code sort=price; DROP TABLE product--} 这类值。
     * 只要白名单做对了，它连 {@code <choose>} 的任何分支都匹配不上，
     * 直接退化成默认排序，什么也不会发生。
     */
    private String sort;

    /** 默认排序：最新上架的在前 */
    public static final String SORT_DEFAULT = "default";

    /** 价格从低到高 */
    public static final String SORT_PRICE_ASC = "price_asc";

    /** 价格从高到低 */
    public static final String SORT_PRICE_DESC = "price_desc";

    /**
     * 规范化查询参数。
     *
     * <p>先调父类处理分页，再处理自己加的三个字段。
     *
     * <p><b>★ sort 的白名单校验就在这里做。</b>
     * 不在 Controller 里做，也不在 XML 里做 ——
     * 因为这是「这个 DTO 认为什么值是合法的」，
     * 属于 DTO 自己的规则，放在自己身上最不容易被绕过。
     *
     * <p>注意对非法值<b>不抛异常，而是退化成默认排序</b>。
     * 这是刻意的：
     * <ul>
     *   <li>排序方式被篡改不会造成任何安全问题（反正走的是白名单分支），
     *       没必要为此给用户报一个错</li>
     *   <li>如果前端某天加了个新的排序选项而后端还没支持，
     *       用户会看到「按默认排序展示的结果」，而不是一个红色报错</li>
     * </ul>
     * <b>「安全上必须拒绝的」和「业务上无所谓的」要区别对待：</b>
     * 前者抛异常，后者静默降级。
     * 拿不准的时候问自己一句：「这个非法输入，会不会让用户看到不该看的数据？」
     */
    @Override
    public void normalize() {
        super.normalize();

        if (keyword != null && keyword.isBlank()) {
            keyword = null;
        }

        // 白名单：不在名单里的一律退化成默认排序
        if (sort == null
                || !(sort.equals(SORT_DEFAULT)
                || sort.equals(SORT_PRICE_ASC)
                || sort.equals(SORT_PRICE_DESC))) {
            sort = SORT_DEFAULT;
        }
    }
}
