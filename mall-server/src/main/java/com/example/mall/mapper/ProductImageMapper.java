package com.example.mall.mapper;

import com.example.mall.entity.ProductImage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品图集的数据库操作。
 *
 * <p>这个 Mapper 一共四个方法，都围着同一件事转：
 * 把「一个商品的一组有序图片」从数据库里读出来、或者整个换掉。
 *
 * <h3>★ 为什么有一对「返回实体」和「只返回 URL」的方法？</h3>
 *
 * <p>{@link #selectByProductId} 返回完整实体，{@link #selectUrlsByProductId}
 * 只返回一列字符串。两者查的是同一张表、同一批行，区别只在<b>谁在用</b>：
 * <pre>
 *   selectUrlsByProductId  ← 商品详情页用它。那里只要一串 URL，
 *                             id / sort_no / createTime 一个都不显示
 *   selectByProductId      ← 管理端将来要做「按 id 删某一张图」之类的
 *                             行级操作时用它。本轮没有调用方
 * </pre>
 *
 * <p>⚠️ {@code selectByProductId} <b>目前没有调用方</b>，这是刻意的：
 * 排序功能走的是「整个数组提交、全删重插」（见 ProductServiceImpl.update
 * 的注释），所以管理端也不需要按行操作。留着它是因为
 * <b>它是这张表最自然的读法</b>（读实体），删掉的话，
 * 下次需要「按 id 改某一行」时又得从零写一遍。
 *
 * <h3>★ 为什么这里没有 update 方法</h3>
 *
 * <p>因为图集的更新语义是「整个替换」，不是「改某一行」——
 * 客户端提交的是一个有序数组，服务端直接 delete + batchInsert。
 * 没有 update，就没有「哪一行对应哪个元素」这个映射问题。
 */
public interface ProductImageMapper {

    /**
     * 查某个商品的图集（完整实体），按展示顺序排列。
     *
     * @param productId 商品 id
     * @return 图集列表，商品没有图时返回<b>空列表</b>（不是 null）
     */
    List<ProductImage> selectByProductId(@Param("productId") Long productId);

    /**
     * 查某个商品的图集地址，按展示顺序排列。
     *
     * <p>商品详情页唯一需要的东西就是这一串 URL。
     *
     * <p>★ 比查实体更省 —— 少传三列（id / sort_no / createTime）不说，
     * 更重要的是<b>返回值里没有多余的东西，调用方就没法拿它们去做别的事</b>。
     * 「接口只给调用方它需要的」是一条便宜的约束。
     *
     * @param productId 商品 id
     * @return URL 列表，商品没有图时返回<b>空列表</b>（不是 null）
     */
    List<String> selectUrlsByProductId(@Param("productId") Long productId);

    /**
     * 删掉某个商品的全部图集。
     *
     * <p>两个地方会调它：{@code update}（先清空再按新顺序插入）
     * 和 {@code delete}（删商品前必须先删图集，见 ProductImage 的类注释）。
     *
     * @param productId 商品 id
     * @return 影响行数。<b>不判断是否为 0</b> ——
     *         「这个商品本来就没有图」和「图被删掉了」是同一个结果，
     *         都不是错误。
     */
    int deleteByProductId(@Param("productId") Long productId);

    /**
     * 批量插入图集。
     *
     * <p>⚠️⚠️ <b>调用前必须判空。</b>{@code <foreach>} 遇到空集合会拼出
     * {@code INSERT INTO product_image (...) VALUES} —— 一个没有 VALUES 的
     * INSERT，SQL 语法错误，直接抛异常。
     *
     * <p>这不是理论风险：里程碑 10 的 {@code attachItems} 已经踩过
     * 同一个坑（那边的形状是 {@code IN ()}，同样是空集合导致的语法错误）。
     * <b>空集合必须在 Java 层拦住，不能指望 SQL 兜底。</b>
     *
     * @param productId 这些图都属于哪个商品
     * @param images    要插入的行，{@code sortNo} 由调用方按数组下标填好。
     *                  <b>必须非空</b>
     * @return 影响行数
     */
    int batchInsert(@Param("productId") Long productId, @Param("images") List<ProductImage> images);
}
