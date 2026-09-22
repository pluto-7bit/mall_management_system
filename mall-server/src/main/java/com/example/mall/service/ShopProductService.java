package com.example.mall.service;

import com.example.mall.common.PageResult;
import com.example.mall.dto.ShopProductQueryDTO;
import com.example.mall.vo.ShopProductDetailVO;
import com.example.mall.vo.ShopProductVO;

/**
 * 用户端商品浏览业务接口。
 *
 * <p><b>★ 为什么另开一个 Service，而不是复用 {@link ProductService}？</b>
 *
 * <p>因为它们的业务规则<b>真的不一样</b>，而不只是「返回的字段少几个」：
 *
 * <table border="1">
 *   <tr><th></th><th>ProductService（管理端）</th><th>ShopProductService（用户端）</th></tr>
 *   <tr>
 *     <td>能看到什么</td>
 *     <td>全部商品，含下架</td>
 *     <td><b>只有上架商品</b></td>
 *   </tr>
 *   <tr>
 *     <td>能改什么</td>
 *     <td>增删改查全套</td>
 *     <td><b>只读</b>，一个写操作都没有</td>
 *   </tr>
 *   <tr>
 *     <td>主要风险</td>
 *     <td>误操作、越权</td>
 *     <td><b>数据泄漏</b>（把不该公开的商品暴露出去）</td>
 *   </tr>
 * </table>
 *
 * <p>风险方向不同的东西，就该分开。放在一个类里的话，
 * 「用户端只能看上架商品」这条规则会变成某个方法里的一个 if，
 * 而新增方法的人很可能不会记得加 —— 因为他满脑子想的是「管理端要能看全部」。
 *
 * <p>分开之后，这个类的每个方法从命名到 SQL 都是「用户端视角」的，
 * 想让下架商品泄露出去，得主动去改 SQL 才行。
 *
 * <p><b>只有一个方法的 Service 值得建吗？</b>
 * 这里有两个方法（列表 + 详情），而且后面会继续长
 * （搜索、按标签筛选、推荐商品……）。
 * 即使只有一个，只要它的<b>规则和邻居不同</b>，单独放也是对的 ——
 * 这里正是这种情况。
 *
 * <p>★ <b>里程碑 12 起这份「以后会长的清单」上划掉了一项</b>：
 * 商品评价<b>没有</b>加进这个类（当初这里是把它列在里面的），
 * 而是单开了 {@link ProductReviewService}。
 * 理由和上面那条「风险方向不同的东西就该分开」是同一条 ——
 * 评价有写操作（发表评价）、有另一个维度的权限（游客可读但不能写）、
 * 还有管理端的删除，它的规则和「只读的商品浏览」完全不同。
 * <b>「这个类以后会长」不该变成「什么东西都往这里塞」——
 * 长出来的每一块仍然要重新过一遍「它的规则和邻居一样吗」。</b>
 */
public interface ShopProductService {

    /**
     * 分页浏览商品。
     *
     * <p>只返回上架商品，支持关键词搜索、分类筛选、排序。
     * 这个接口<b>允许匿名访问</b>（游客不登录也能逛）。
     *
     * @return 分页结果，没有匹配的商品时返回空列表而不是 null
     */
    PageResult<ShopProductVO> page(ShopProductQueryDTO query);

    /**
     * 查看商品详情。
     *
     * <p>商品不存在<b>或者已下架</b>时，统一抛出「商品不存在」。
     * 为什么合并成一句话，见
     * {@link com.example.mall.mapper.ProductMapper#selectShopById} 的注释。
     *
     * @throws com.example.mall.common.BusinessException 商品不存在或已下架
     */
    ShopProductDetailVO detail(Long id);
}
