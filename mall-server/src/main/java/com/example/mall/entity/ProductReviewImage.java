package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价晒图实体 —— 与数据库 {@code product_review_image} 表一一对应。
 *
 * <p>一行 = 某条评价的一张晒图。
 *
 * <h3>★★ 它和 {@link ProductImage} 长得几乎一样，但少了一个 {@code sortNo} —— 这是刻意的</h3>
 *
 * <p>把两张表并排放在一起看：
 * <pre>
 *   product_image         id, product_id, url, sort_no, create_time
 *   product_review_image  id, review_id,  url,          create_time
 *                                              ↑ 少了这个
 * </pre>
 * 判据还是那一条：<b>有读者才加列。</b>
 * <ul>
 *   <li>{@code product_image.sort_no} —— 图集管理界面上有<b>上移/下移两个按钮</b>，
 *       顺序是「用户能改、下次打开还要原样看到」的东西 → 有确定的读者，加。</li>
 *   <li>评价晒图 —— 评价一旦提交就<b>不可修改</b>，没有任何界面能让用户调整顺序
 *       → 「顺序」这件事<b>没有读者</b>，不加。</li>
 * </ul>
 * 所以这里的展示顺序 = 上传顺序 = 插入顺序，读的时候 {@code ORDER BY id} 就够了
 * （{@code id} 唯一，天然满足「{@code ORDER BY} 必须以唯一列结尾」那条规矩）。
 *
 * <p><b>★ 同一个形态的东西第二次出现时，该复用的复用、该分岔的分岔，
 * 但理由都要重新问一遍 —— 不能因为「上次加了这次也加」。</b>
 * 这条注释就是那个「重新问一遍」的留痕。
 *
 * <h3>★ 它不是快照 —— 和 {@link ProductImage} 一样，和 {@link OrderItem} 相反</h3>
 *
 * <p>{@code url} 存的是「这张图在哪」，是指向文件的一个引用。
 * 图片文件本身在磁盘上、不在数据库里，所以这个引用没有「当时」和「现在」的区别。
 *
 * <h3>⚠️ 磁盘文件从来不会被删</h3>
 *
 * <p>删评价、删商品都<b>只删数据库行</b>，{@code uploads/} 里的文件留在原地。
 * 这是里程碑 11 确认过的取舍（不引入「这个文件还有没有别的引用」这个问题），
 * 代价是删完之后会有孤儿文件，见 {@code ProductReviewServiceImpl}
 * 和 {@code ProductServiceImpl.delete} 的注释。
 */
@Data
public class ProductReviewImage {

    private Long id;

    /** 所属评价 id（{@code product_review.id}） */
    private Long reviewId;

    /**
     * 图片地址，形如 {@code /uploads/2026/09/<uuid>.png}。
     *
     * <p>★ 存的是 <b>URL 路径</b>，不是文件名，也不是这台机器的绝对路径 ——
     * 换台机器、换个部署目录，绝对路径会全部指错，而这个值已经写进数据库了。
     *
     * <p>★ 这个值<b>只能由服务端生成</b>（{@code FileStorageService.saveImage}）。
     * 客户端提交上来的地址要过 {@code FileStorageService.requireUploadedImages}
     * 那道 {@code /uploads/} 前缀校验 —— 否则就是「往我的页面上引外站图片」，
     * 轻则盗链、重则跟踪像素。
     */
    private String url;

    private LocalDateTime createTime;
}
