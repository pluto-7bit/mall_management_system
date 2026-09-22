package com.example.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交一条商品评价的请求参数（DTO）。
 *
 * <h3>★★★ 请求体里【没有】{@code productId}、也没有 {@code memberId} —— 这是安全边界</h3>
 *
 * <p>客户端只报<b>一个</b>东西：{@code orderItemId}（评的是哪一条明细）。
 * 商品 id 和会员 id 都由服务端查出来：
 * <pre>
 *   productId  ← 从 order_item 查出来（不能信客户端报的商品）
 *   memberId   ← 从 JWT 里取（不能信客户端报的人）
 * </pre>
 *
 * <p>如果让客户端报 {@code productId}，他就能「评价自己买过的 A 商品、
 * 把内容挂到别人的 B 商品下面」—— 而 {@code orderItemId} 那条唯一索引
 * 完全拦不住这件事，因为它约束的是明细，不是商品。
 * <b>★ 这不是「参数少传一个更简洁」，是「这个字段存在就等于开了一个洞」。</b>
 *
 * <p>（这和 {@code ProductSaveDTO} 注释里「注意这里没有 id 字段」是同一条思路，
 * 只是这里要挡的东西更值钱：那边挡的是「改别人的商品」，
 * 这边挡的是「往别人的商品上写内容」。）
 *
 * <h3>★ 为什么没有 {@code @NotBlank} 之外的内容长度下限</h3>
 *
 * <p>「评价至少 5 个字」这类规则本项目没有做，属于产品决策而不是技术约束。
 * 现在只拦「空白内容」（{@code @NotBlank}）和「超长」（{@code @Size(max = 500)}），
 * 前者是「一条空评价没有意义」，后者是「数据库列只有 500 宽」。
 * <b>两边都是「不做就会坏」，而不是「不做就不够好」。</b>
 */
@Data
public class ReviewSaveDTO {

    /**
     * 要评价的订单明细 id。
     *
     * <p>⚠️ 客户端提交的这个 id <b>本身不构成任何权限</b> ——
     * 它只是一个「我想评这条」的声明。能不能评由 Service 查出来判定：
     * 这条明细必须<b>属于当前登录会员</b>，且它所属订单<b>已确认收货</b>。
     * 详见 {@code ProductReviewServiceImpl.create}。
     *
     * <p>★ 这也是为什么这里可以放心用 {@code @NotNull} + 客户端传值：
     * <b>它不是身份，是一个待验证的引用。</b>真正不能由客户端传的是
     * {@code memberId} 那种「一传就能冒充别人」的东西。
     */
    @NotNull(message = "请指定要评价的订单明细")
    private Long orderItemId;

    /**
     * 评分：1~5 星。
     *
     * <p>★ 数据库的 {@code rating} 列是 TINYINT，<b>没有</b> CHECK 约束 ——
     * 越界的拦截就在这两个注解上。这是刻意的：
     * {@code product_review.order_item_id} 上有唯一索引（「让数据库兜底」的先例），
     * 但两者的区别在于<b>「这件事是不是并发问题」</b>：
     * 「一条明细只能评一次」并发挡不住，所以必须靠索引；
     * 「rating 在 1~5 之间」不是并发问题，校验注解就够了 ——
     * 而且注解失败还能给出「评分最低 1 星」这种说得清的错误信息，
     * 数据库的 CHECK 只会给一句约束名。
     */
    @NotNull(message = "请选择评分")
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    private Integer rating;

    @NotBlank(message = "请填写评价内容")
    @Size(max = 500, message = "评价内容最多 500 字")
    private String content;

    /**
     * 晒图地址列表，最多 3 张。
     *
     * <h4>★★ 这里 {@code null} 和 {@code []} 是【同一个意思】—— 和 ProductSaveDTO 相反</h4>
     *
     * <p>{@code ProductSaveDTO.images} 里两者是<b>两个不同的意思</b>：
     * <pre>
     *   null → 不改图集（请求方根本不关心这件事）
     *   []   → 清空图集
     * </pre>
     * 而这里都是「没晒图」。
     *
     * <p><b>★ 判据不是「字段名」，而是「这个操作有没有『保持不变』这个可能」：</b>
     * <pre>
     *   ProductServiceImpl.update → 有。「不改」和「清空」必须是两件事。
     *   ProductReviewService.create → 没有。评价是新建，
     *                                 没有「之前那几张图」可以保持不变。
     *                                 而且评价一次定终身，根本没有第二次修改。
     * </pre>
     * 所以 Service 里第一件事就是把两者归一化：
     * {@code List<String> urls = dto.getImages() == null ? List.of() : dto.getImages();}
     * —— <b>归一化放在 Service 的开头，后面的代码就只有一种形状要处理。</b>
     *
     * <h4>★ 校验注解：{@code @Size} 管「几个」，「多长」要写在泛型里</h4>
     *
     * <p>和 {@code ProductSaveDTO.images} 逐字相同的规矩：
     * {@code @Size(max = 3)} 约束的是<b>元素个数</b>；
     * 约束单个元素长度必须写成 {@code List<@Size(max = 255) String>}。
     *
     * <p>⚠️ <b>写错了不会有任何报错</b>：写成 {@code @Size(max = 255) private List<String> images}
     * 看起来完全像是在限制长度，实际上是在说「最多 255 张图」，
     * 校验静默地不生效。所以下面两种都写清楚。
     *
     * <p>★ 但<b>只写注解是不够的</b>：地址还必须<b>是本服务自己上传的</b>
     * （{@code /uploads/} 前缀）。那个校验是「值的形状」而不是「个数/长度」，
     * 校验注解表达不了，只能由 Service 调
     * {@code FileStorageService.requireUploadedImages} 来做。
     * <b>两种校验的分工：注解管「形式」，Service 管「来源」。</b>
     */
    @Size(max = 3, message = "晒图最多 3 张")
    private List<@Size(max = 255, message = "图片地址不能超过 255 个字符") String> images;
}
