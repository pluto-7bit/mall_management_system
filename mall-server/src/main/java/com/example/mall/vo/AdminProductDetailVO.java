package com.example.mall.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 管理端商品<b>详情</b>（VO）。
 *
 * <p>比 {@link ProductVO} 只多一个 {@code images}。
 *
 * <h3>★ 为什么是 {@code extends ProductVO}，而不是往 ProductVO 里塞一个列表字段？</h3>
 *
 * <p>理由和 {@link AdminOrderVO} {@code extends OrderVO} 完全一样：
 * 管理端详情要显示的东西是列表的<b>超集</b>，继承精确表达了这个关系，
 * 也让字段定义只有一份。
 *
 * <p>★ 决定性的理由是项目里一直在用的那条判据：<b>有读者才加字段。</b>
 * {@code images} 的读者是<b>编辑弹窗</b>（{@code ProductForm.vue}），
 * 不是列表表格。放进 {@link ProductVO} 就是给一个没有读者的地方
 * 每次都查一遍数据 —— 管理端列表一页 10 行，
 * 要么每行多一次查询（N+1），要么在列表的 SQL 里多一次 join，
 * 而两种代价都是为了一个不显示的东西。
 *
 * <p>用户端<b>早就这么拆了</b>：{@code ShopProductVO}（列表）和
 * {@code ShopProductDetailVO}（详情）本来就是两个类。那个类的注释里
 * 写着判断标准：「<b>不是字段差几个，而是这两个场景的数据量级和调用频率差多少</b>」。
 * 这里是同一条标准的管理端版本。
 *
 * <h3>★ 为什么用户端那边加字段，管理端这边要新建一个类？</h3>
 *
 * <p>因为两边的<b>现有结构不同</b>，而这个项目的做法是「改动尽量小、
 * 尽量不碰已经在跑的东西」：
 * <pre>
 *   用户端：列表 VO 和详情 VO 已经分开了 → 详情那个加一个字段就行（一行改动）
 *   管理端：列表和详情共用一个 ProductVO  → 要么拆，要么污染列表
 * </pre>
 * 管理端这边「拆」的代价是新建一个类 + 改一处 resultType，
 * 而「污染」的代价是每次翻列表页都白查一遍图集。所以拆。
 *
 * <p>⚠️ 还有一条必须提的：<b>{@code ProductVO} 是多处共用的</b> ——
 * 管理端列表在用，用户端的部分接口也在用（见它的类注释）。
 * 往一个被多处引用的 VO 里加字段，是「伤到别人的最经典方式」：
 * 加的人只看了自己那一处，而字段会跟着 JSON 出现在所有引用它的地方。
 * 新建子类则<b>一个现有调用点都不影响</b>。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminProductDetailVO extends ProductVO {

    /**
     * 商品图集，按展示顺序排列，元素是可直接用于 {@code <img src>} 的 URL 路径。
     *
     * <p>★ 它是<b>在 Service 里单独查一次装进来的</b>，
     * 不是靠 MyBatis 的 {@code <collection>} 从
     * {@code ProductMapper.selectById} 里带出来的。
     * 原因是一条很具体的调用链：{@code selectShopById}（用户端详情那条 SQL）
     * 被购物车的「加入购物车 / 改数量」复用了，而那边只需要 {@code stock}。
     * 给共用查询加 {@code <collection>}，就是让每次加购物车都多查一次图集。
     * 详见 {@code ProductServiceImpl.getById} 的注释。
     *
     * <p>⚠️ 商品没有图时这个字段是<b>空列表</b>，不是 null ——
     * 而且因为 Jackson 配了 {@code default-property-inclusion: non_null}，
     * 只有<b>真的为 null</b> 的字段才会从 JSON 里消失。
     * 空列表会原样返回 {@code "images": []}，
     * 前端因此可以放心地写 {@code product.images.length}，
     * 不用先判 undefined。这是「Service 保证不返回 null」的价值。
     */
    private List<String> images;
}
