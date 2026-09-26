package com.example.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员手工录入一个物流轨迹节点的请求体。★ 里程碑 18 新增。
 *
 * <pre>
 *   { "status": 2, "description": "快件已到达【杭州转运中心】",
 *     "traceTime": "2026-09-25 09:00:00" }
 * </pre>
 *
 * <p>⚠️ 这个 DTO 里没有 {@code orderNo} —— 它在 URL 里；
 * 也没有操作人 —— 本项目不记「谁录的」，
 * 理由同 {@code after_sale} 不记审核人（单人项目，管理员只有一个）。
 *
 * <h3>★ 三个字段都是必填，各有各的理由</h3>
 *
 * <p><b>{@code status}</b>：{@code @NotNull} 只管「填了没有」，
 * <b>不管「填的是不是 1~5 里那个」</b> —— 白名单校验在 Service 里
 * （{@code LogisticsStatus.isValid}）。这个分层照抄 {@code AfterSaleApplyDTO}：
 * DTO 管「填了没有」，码表管「填的对不对」。
 *
 * <p><b>{@code description}</b>：一个只有状态码、没有说明的节点对用户没有意义 ——
 * 「运输中」三个字谁都会看，用户想知道的是<b>哪到哪了</b>。
 *
 * <p><b>{@code traceTime}</b>：★★ <b>这个字段最不能省。</b>
 * 让服务端在它为 null 时兜底成 {@code NOW()} 看起来更「宽容」，
 * 但那样一个前端 bug（没把管理员选的时间发出来）会<b>静默地</b>把
 * 补录的节点全记成今天 —— 而管理员以为记的是昨天。
 * <b>时间线排错顺序，页面却一切正常。</b>
 * 必填能让这种 bug 在入口就失败（400），而不是把错数据写进库里。
 *
 * <h3>★ 刻意【不】加 {@code @PastOrPresent}</h3>
 *
 * <p>「节点时间不能是未来」听起来天经地义，但加它有两个坏处：
 *
 * <p><b>① 它会误伤正常操作。</b>前端的时间选择器默认取<b>浏览器</b>的当前时间，
 * 而校验用的是<b>服务器</b>的时钟 —— 浏览器快一分钟，管理员就录不进去了，
 * 提示还是「不能是未来」，而他明明选的就是现在。
 * 时钟偏差是常态，不是异常。
 *
 * <p><b>② 它挡的那个错本来就【看得见、且能改】。</b>
 * 真填了 2126 年，那条节点会一直顶在时间线最上面（排序是
 * {@code trace_time DESC}）—— 但它是<b>显眼地错</b>（写着 2126 年），
 * 而且管理员删掉重录就行了。本项目真正怕的是<b>看不见的</b>错，
 * 这一个不是。
 *
 * <p>★ 「过去的时刻」相反地<b>必须允许</b>：补录是这个功能的默认用法，
 * 见 {@code OrderLogistics} 的类注释。
 */
@Data
public class LogisticsTraceSaveDTO {

    @NotNull(message = "请选择节点状态")
    private Integer status;

    @NotBlank(message = "请填写这一节点的说明")
    @Size(max = 255, message = "说明最多 255 个字")
    private String description;

    @NotNull(message = "请选择这一节点发生的时间")
    private LocalDateTime traceTime;
}
