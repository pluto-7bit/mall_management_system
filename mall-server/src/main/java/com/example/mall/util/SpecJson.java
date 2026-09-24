package com.example.mall.util;

import com.example.mall.common.SpecGroup;
import com.example.mall.common.SpecItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 全项目<b>唯一</b>定义「什么算规范的 {@code spec_json} / {@code spec_schema}」的地方。
 *
 * <h3>★ 为什么这件事必须收口到一个类？</h3>
 *
 * <p>因为 {@code product_sku} 上有一条唯一索引
 * {@code UNIQUE KEY uk_product_spec (product_id, spec_json)}，
 * 而它<b>只在字符串完全相同的时候才拦得住</b>。
 *
 * <p>{@code uk_product_spec} 要保证的是「同一件商品不能有两个相同的规格组合」。
 * 但「相同的规格组合」是个<b>语义</b>判断，唯一索引只懂<b>字符串相等</b>。
 * 两者之间的桥就是本类的 {@link #canonical(List)}：
 *
 * <pre>
 *   管理员提交  [颜色:黑, 内存:128G]   →  canonical →  [{"name":"内存",...},{"name":"颜色",...}]
 *   管理员又提交 [内存:128G, 颜色:黑]   →  canonical →  [{"name":"内存",...},{"name":"颜色",...}]
 *                                                       ↑ 同一个字符串 —— 索引拦住了
 * </pre>
 *
 * <p>如果没有这一层，上面两次提交会变成两个<b>不同的字符串</b>：
 * 数据库老老实实地插进两行「黑色 128G」，然后
 * {@code MIN(price)} 静默地取到更低的那个价格，
 * 前端规格选择器永远匹配不上其中一行 —— <b>没有任何地方会报错</b>。
 *
 * <p>⚠️ 所以「规范化」不是锦上添花，它是那条唯一索引<b>唯一的前提</b>。
 * 任何写 {@code spec_json} 的路径都必须经过这里，一处绕过就是一处漏洞。
 *
 * <h3>★ 读的时候为什么是「抛异常」而不是「兜底成空列表」？</h3>
 *
 * <p>因为库里存着不合法的 JSON 是<b>数据损坏</b>，不是用户输入错误。
 * 把它当成「这件商品没有规格」悄悄放过去，症状会是
 * 「某个商品的规格在页面上凭空消失了」—— 一个看起来像前端的后端 bug。
 * 宁可当场 500 把出问题的行喊出来。
 */
public final class SpecJson {

    /** 工具类不应该被实例化 */
    private SpecJson() {
    }

    /**
     * 规范化的「空」—— 无规格商品的 {@code spec_json} 和 {@code spec_schema} 都是它。
     *
     * <p>用的是 {@code []}（JSON 空数组）而<b>不是</b> {@code ''}，更不是 {@code NULL}：
     * MySQL 的唯一索引把多个 NULL 当成互不相等，
     * 于是「无规格」就能插出两条一模一样的默认 SKU。
     * 详见 {@code product_sku} 建表语句上的注释。
     */
    public static final String EMPTY = "[]";

    /**
     * 独立的 ObjectMapper，<b>不是</b> Spring 容器里那个。
     *
     * <p>★ 这是刻意的：容器的那个 {@code ObjectMapper} 被
     * {@code spring.jackson.default-property-inclusion: non_null} 配过，
     * 凡是 null 的字段都会从 JSON 里消失。这个开关对「返回给前端的响应」
     * 是对的，但对「要拿去撞唯一索引的字符串」是灾难 ——
     * 它会让两个本该不同的规格组合序列化成同一个字符串，
     * 或者让同一个组合因为某个字段为 null 而变成两种形态。
     * 存进数据库的字符串必须由我们自己完全决定长什么样。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<SpecItem>> ITEM_LIST = new TypeReference<>() {
    };

    private static final TypeReference<List<SpecGroup>> GROUP_LIST = new TypeReference<>() {
    };

    // ------------------------------------------------------------------
    // spec_json：一行 SKU 的规格组合
    // ------------------------------------------------------------------

    /**
     * 把一组规格项规范化成可以存进 {@code product_sku.spec_json} 的字符串。
     *
     * <p><b>唯一的规范化动作是：按规格名排序。</b>
     * 理由是「规格组合」在语义上是个 <b>集合</b> ——
     * 「颜色=黑 且 内存=128G」和「内存=128G 且 颜色=黑」说的是同一件事，
     * 它们必须得到同一个字符串，否则唯一索引形同虚设。
     *
     * <p>⚠️ 这也意味着 {@code spec_json} 里维度<b>不是</b>管理员定义的顺序。
     * 要显示给用户看的顺序请走 {@link #text(String, List)}，它按
     * {@code spec_schema} 的顺序排。
     *
     * <p>参数为 null 或空列表都返回 {@link #EMPTY}（{@code "[]"}），
     * 也就是「无规格」的规范形态。
     *
     * @param specs 规格项，调用方需已保证每项的 name/value 都非空
     *              （Service 校验过；name 为 null 时这里按空串参与排序，不会 NPE）
     */
    public static String canonical(List<SpecItem> specs) {
        if (specs == null || specs.isEmpty()) {
            return EMPTY;
        }
        List<SpecItem> sorted = new ArrayList<>(specs);
        sorted.sort(Comparator.comparing(item -> item.name() == null ? "" : item.name()));
        return write(sorted);
    }

    /**
     * 把库里的 {@code spec_json} 解析回规格项列表。
     *
     * <p>无规格的默认 SKU（{@code "[]"}）解析结果就是空列表。
     *
     * @throws IllegalStateException 库里存的东西不是合法 JSON 时
     *                               （数据损坏，见类注释）
     */
    public static List<SpecItem> parse(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return List.of();
        }
        try {
            List<SpecItem> items = MAPPER.readValue(specJson, ITEM_LIST);
            return items == null ? List.of() : items;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("库里的 spec_json 不是合法 JSON：" + specJson, e);
        }
    }

    // ------------------------------------------------------------------
    // spec_schema：这件商品有哪些规格
    // ------------------------------------------------------------------

    /** 把规格定义序列化成可以存进 {@code product.spec_schema} 的字符串。 */
    public static String schemaJson(List<SpecGroup> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY;
        }
        return write(schema);
    }

    /**
     * 把库里的 {@code spec_schema} 解析回规格定义。
     *
     * <p>⚠️ 和 {@link #parse(String)} 有一处<b>刻意的不对称</b>：
     * {@code spec_schema} 在阶段 2 之前是 {@code NULL}（那 100 行是阶段 1 的迁移
     * 回填 SKU 时留下的，那时还没有编辑器去写它），所以 <b>null 和空串都当「无规格」</b>
     * 返回空列表。而 {@code spec_json} 是 NOT NULL，null 只可能是代码写错了，
     * 所以那边会当成损坏抛出来。
     *
     * @throws IllegalStateException 库里存的东西不是合法 JSON 时
     */
    public static List<SpecGroup> schemaOf(String specSchemaJson) {
        if (specSchemaJson == null || specSchemaJson.isBlank()) {
            return List.of();
        }
        try {
            List<SpecGroup> groups = MAPPER.readValue(specSchemaJson, GROUP_LIST);
            return groups == null ? List.of() : groups;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "库里的 spec_schema 不是合法 JSON：" + specSchemaJson, e);
        }
    }

    // ------------------------------------------------------------------
    // 给人看的那一句话
    // ------------------------------------------------------------------

    /**
     * 把 {@code spec_json} 渲染成给人看的一句话，如
     * {@code "颜色:黑 / 内存:128G"}；无规格返回<b>空串</b>。
     *
     * <p>这就是订单明细里 {@code order_item.sku_spec} 快照的内容，
     * 也是购物车行、订单行上显示的那行小字。
     *
     * <h3>★ 为什么要传 {@code schema} 进来？</h3>
     *
     * <p>因为 {@link #canonical(List)} 为了去重把维度<b>按名字排了序</b>，
     * 直接拿它显示会得到「内存:128G / 颜色:黑」——
     * 一个管理员从来没有定义过的顺序，而且不同商品之间顺序还不一致
     * （取决于名字的字典序）。传入 {@code spec_schema} 就能按管理员
     * 定义的维度顺序重排。
     *
     * <p>⚠️ 只在<b>真的排全了</b>的时候才采用重排结果：如果某个维度在
     * {@code spec_schema} 里找不到（说明两者分叉了），宁可退回原顺序 ——
     * 退回最多是顺序难看，而「采用了排不全的结果」会<b>直接丢掉一个维度</b>。
     *
     * <h4>★ 为什么这里【不需要】长度兜底</h4>
     *
     * <p>它的产物要快照进 {@code order_item.sku_spec VARCHAR(255)}。
     * 最坏情况是三维、每维名 10 字值 20 字：
     * {@code 3 × (10 + 1 + 20) + 2 × 3 = 99} 字符，只有 255 的零头。
     * 所以这是一个<b>可证明</b>装得下的值，不需要再写一次长度检查 ——
     * 写了也是一段永远执行不到的代码，而那种代码会让下一个读它的人
     * 以为「这里真的可能超长」。
     *
     * <p>⚠️ 这条算术依赖 {@code BusinessRules} 里那三个上限
     * （3 维 / 名 10 字 / 值 20 字）。改那三个数字的任何一次，
     * 都要回来重算这一行。
     *
     * @param specJson 库里的 {@code spec_json}
     * @param schema   这件商品的规格定义，可以为 null（那时按原顺序渲染）
     * @return 无规格时返回空串 {@code ""}，因为这句话是要直接显示给用户看的，
     *         {@code "[]"} 对用户没有意义
     */
    public static String text(String specJson, List<SpecGroup> schema) {
        List<SpecItem> items = parse(specJson);
        if (items.isEmpty()) {
            return "";
        }
        if (schema != null && !schema.isEmpty()) {
            List<SpecItem> ordered = new ArrayList<>(items.size());
            for (SpecGroup group : schema) {
                for (SpecItem item : items) {
                    if (item.name() != null && item.name().equals(group.name())) {
                        ordered.add(item);
                        break;
                    }
                }
            }
            if (ordered.size() == items.size()) {
                items = ordered;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (SpecItem item : items) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(item.name()).append(':').append(item.value());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 到不了这里：要序列化的都是 String 组成的 record，
            // Jackson 序列化它们不会失败。真到了这一步说明我们的假设错了，
            // 那就宁可 500 —— 也绝不往库里写一个「差不多」的字符串，
            // 因为它会拿去撞唯一索引，写错一次就再也纠正不回来了。
            throw new IllegalStateException("序列化规格 JSON 失败", e);
        }
    }
}
