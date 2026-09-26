package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 买家填写寄回物流的请求体。★ 里程碑 17 新增。
 *
 * <pre>
 *   { "returnCompany": "顺丰", "returnTracking": "SF1234567890" }
 * </pre>
 *
 * <p>对应的动作是状态机上的 <b>T5（1 待买家寄回 → 2 待卖家收货）</b>，
 * 而它只可能由<b>买家本人</b>发起 —— 所以这个 DTO 里同样没有 memberId
 * （身份来自 JWT，并且会被写进 UPDATE 的 WHERE）。
 *
 * <h3>★ 两个字段都是自由文本，这是对的</h3>
 *
 * <p>和 {@code AfterSaleReason}（原因）相反：原因要能被 {@code GROUP BY}，
 * 所以它是码；而快递公司和运单号<b>天生就是外部世界的标识符</b>，
 * 本来就没有一张能穷举的表。给它们做码表只会得到一张
 * 永远在补的「全国快递公司」字典，然后漏掉那个用户用的那一家。
 *
 * <p>⚠️ 但「自由文本」不等于「不校验长度」——
 * 长度必须对齐列宽（{@code VARCHAR(50)} / {@code VARCHAR(64)}），
 * 否则超长会一路走到 INSERT，用户看到的是「服务器错误」
 * 而不是「单号太长」。同 {@code AfterSaleApplyDTO.description}。
 */
@Data
public class AfterSaleReturnDTO {

    @NotBlank(message = "请填写快递公司")
    @Size(max = 50, message = "快递公司最多 50 个字")
    private String returnCompany;

    @NotBlank(message = "请填写快递单号")
    @Size(max = 64, message = "快递单号最多 64 个字")
    private String returnTracking;
}
