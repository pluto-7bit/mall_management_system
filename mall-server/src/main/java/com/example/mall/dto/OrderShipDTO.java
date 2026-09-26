package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端发货的请求体。★ 里程碑 18 新增。
 *
 * <pre>
 *   { "logisticsCompany": "顺丰", "trackingNo": "SF1234567890" }
 * </pre>
 *
 * <p>对应状态机上的 <b>「已付款 → 已发货」</b> 这条边。
 *
 * <h3>★★ 这个 DTO 是【新增】的，此前发货【没有请求体】</h3>
 *
 * <p>里程碑 10 的 {@code AdminOrderController.ship} 是
 * {@code ship(@PathVariable String orderNo)} —— 一个参数都没有，
 * 理由是「发哪一单在 URL 里，谁在发在 JWT 里，
 * 没有参数就没有可以被乱填的地方」。
 *
 * <p><b>那句话本身没错，它错在【当时没有东西要填】。</b>
 * 现在有了：货是怎么发出去的（哪家快递、单号多少）是发货这个动作
 * 与生俱来的一部分，里程碑 10 只是没有记它。
 *
 * <h3>★ 为什么长度和 {@link AfterSaleReturnDTO} 一模一样</h3>
 *
 * <p>因为那是<b>同一件事</b>（快递公司 + 快递单号），只是方向相反：
 * 那边是买家寄回，这边是卖家寄出。长度是列宽（{@code VARCHAR(50)} /
 * {@code VARCHAR(64)}）的镜像，两处必须一致 —— 不一致的话，
 * 同一个快递公司名在「寄出」能填、在「寄回」填不了，而没有任何一层会解释为什么。
 *
 * <p>⚠️ 两个字段都必须 {@code @NotBlank}，见
 * {@code OrderAdminMapper.markShipped} 的注释：允许为空会让
 * 「已发货但没有单号」变成一个合法且常态的状态。
 */
@Data
public class OrderShipDTO {

    @NotBlank(message = "请填写快递公司")
    @Size(max = 50, message = "快递公司最多 50 个字")
    private String logisticsCompany;

    @NotBlank(message = "请填写快递单号")
    @Size(max = 64, message = "快递单号最多 64 个字")
    private String trackingNo;
}
