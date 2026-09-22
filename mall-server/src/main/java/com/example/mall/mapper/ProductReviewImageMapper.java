package com.example.mall.mapper;

import com.example.mall.entity.ProductReviewImage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评价晒图的数据库操作。
 *
 * <h3>★ 它和 {@code ProductImageMapper} 长得像，但少了三样东西 —— 每一样都是理由</h3>
 *
 * <pre>
 *                      ProductImageMapper       本接口
 *   查询               两条（实体 / 只 URL）     一条
 *   更新               没有（整体替换）          没有（晒图不可改）
 *   删单行             没有                        没有
 *   按行重排           没有（回退到全删重插）      没有（顺序没有读者）
 * </pre>
 *
 * <p>对照着看能发现：<b>两个接口都没有 update，但理由完全不同。</b>
 * 图集那边是「更新语义是整个数组替换」，晒图这边是
 * <b>「评价一次定终身，根本没有第二次修改」</b>——
 * 前者是设计选择，后者是业务规则。
 *
 * <p>★ 本接口一共只有四个方法，而且<b>每一个都只被「删商品」或「删评价」
 * 或「读评价」这三条链子之一用到</b>。没有一个是「将来可能会用」而预先写下的。
 */
public interface ProductReviewImageMapper {

    /**
     * 批量插入一条评价的晒图。
     *
     * <p>★ <b>{@code reviewId} 单独传参，不从每个 image 对象里取</b> ——
     * 和 {@code ProductImageMapper.batchInsert} 逐字相同的理由：
     * 让 id 从参数来而不是从每个元素来，就消灭了
     * 「第 3 个元素的 reviewId 忘了赋值」这种可能，
     * 而那种情况会插出一行 {@code review_id = NULL}（这一列是 NOT NULL），
     * 报一条和真实原因相距很远的 SQL 错误。
     *
     * <p>⚠️⚠️ <b>调用前必须判空。</b>{@code <foreach>} 遇到空集合会拼出
     * 一个没有 {@code VALUES} 的 INSERT —— <b>SQL 语法错误，不是「插入 0 行」</b>。
     * 「这条评价没晒图」必须在 Java 里提前 return。
     *
     * <p>这个坑在本项目已经踩过两次（里程碑 10 的 {@code attachItems} 和
     * 里程碑 11 的图集），所以这里不再当成理论风险看 ——
     * {@code ProductReviewServiceImpl.create} 里那句
     * {@code if (urls.isEmpty()) return;} 是承重的。
     *
     * <p>★ 晒图<b>没有 sort_no</b>，所以这里不需要
     * {@code ProductImageMapper.batchInsert} 里那个
     * 「按数组下标填 sortNo」的约定 —— 展示顺序就是插入顺序。
     * 详见 {@link com.example.mall.entity.ProductReviewImage} 的类注释。
     *
     * @param reviewId 这些图都属于哪条评价。<b>必须是 insert 之后回填的那个 id</b>
     * @param images   要插入的行。<b>必须非空</b>
     * @return 影响行数，正常应等于 {@code images.size()}
     */
    int batchInsert(@Param("reviewId") Long reviewId,
                    @Param("images") List<ProductReviewImage> images);

    /**
     * 一次查多条评价的晒图（用于消掉评价列表里的 N+1）。
     *
     * <p>一页 10 条评价，逐个查就是 1 + 10 = 11 次往返；
     * 这个方法是 1 + 1 = 2 次，和这一页有几条评价无关。
     * 和 {@code OrderItemMapper.selectByOrderIds} 是同一个手法。
     *
     * <p>★★ 返回的行是<b>按 {@code review_id} 分好组的</b>，
     * 调用方（{@code ProductReviewServiceImpl.attachImages}）顺序扫一遍
     * 就能分组，不需要再排序、也不需要 {@code Map} 兜住乱序。
     *
     * <p>⚠️⚠️ <b>调用方必须保证 {@code reviewIds} 非空</b> ——
     * 空的 {@code IN ()} 是 <b>SQL 语法错误</b>，不是「返回 0 行」。
     * 「这一页一条评价都没有」必须在 Java 里提前返回。
     * 这是 MyBatis 动态 SQL 最经典的翻车点之一，
     * 也是为什么 {@code attachImages} 的第一行要判 {@code isEmpty()}。
     *
     * <p>⚠️⚠️ <b>返回的 {@code ProductReviewImage} 是【部分填充】的：
     * 只有 {@code reviewId} 和 {@code url} 两个字段有值，
     * {@code id} / {@code createTime} 是 null。</b>
     * 这不是 bug，是刻意的投影 —— 两个调用点都是「按评价分组出一串 URL」，
     * 那两列没有任何读者（判据见 {@code migration-12-review.sql} 里
     * 关于 sort_no 的那段论证）。<b>所以调用方只能读这两个字段。</b>
     * 哪天需要按行操作这张表（比如管理端支持删单张晒图），
     * 要新写一条查完整实体的查询，而不是「顺手」读这里的 id。
     *
     * @param reviewIds 评价 id 列表，<b>调用方必须保证非空</b>
     * @return 这些评价的全部晒图，按 {@code review_id, id} 排序
     *         （同一条评价的晒图一定相邻，且组内保持插入顺序）
     */
    List<ProductReviewImage> selectByReviewIds(@Param("reviewIds") List<Long> reviewIds);

    /**
     * 删掉某条评价的全部晒图。
     *
     * <p>★ 管理端删评价时，<b>必须先调它、再调
     * {@code ProductReviewMapper.deleteById}</b> —— 顺序反了会留下
     * {@code review_id} 指向不存在评价的孤儿行，而且没有任何东西会报错。
     * 全库没有外键，这个顺序只能靠写代码的人记住。
     *
     * <p>⚠️ 影响 0 行不是错误 ——「这条评价本来就没晒图」是正常的
     * （晒图是可选的）。
     *
     * @return 影响行数
     */
    int deleteByReviewId(@Param("reviewId") Long reviewId);

    /**
     * 删掉某个商品下所有评价的晒图 —— 服务于「删商品」。
     *
     * <p>★ 它是「删商品」四级级联的<b>第一级</b>（最深的那个）：
     * <pre>
     *   晒图 → 评价 → 图集 → 商品
     * </pre>
     *
     * <p>★ 写法是<b>子查询</b>而不是 {@code review_id IN (查出来的 id 列表)}：
     * <pre>
     *   DELETE FROM product_review_image
     *   WHERE review_id IN (SELECT id FROM product_review WHERE product_id = ?)
     * </pre>
     * MySQL 允许这样写（删的是 A 表、选的是 B 表，不是同表自引用），
     * 而且<b>一条 SQL 干完，不用先把 id 查回 Java 再拼一个 IN</b> ——
     * 后者不但多一次往返，还会在评价数量大时拼出一个巨大的 IN。
     *
     * <p>★ 这也正是 {@code product_review.product_id} 那个冗余列的收益之一：
     * 不用绕 {@code product_review → order_item → product} 两跳。
     *
     * <p>⚠️ 同样：影响 0 行不是错误。
     *
     * @return 影响行数
     */
    int deleteByProductId(@Param("productId") Long productId);
}
