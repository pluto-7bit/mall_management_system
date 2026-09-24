package com.example.mall.common;

/**
 * 一个规格项：规格名 + 规格值，例如「颜色 = 黑」。
 *
 * <h3>★ 这个类为什么不放在 {@code dto/}，也不放在 {@code vo/}？</h3>
 *
 * <p>因为它在<b>四个地方</b>同时出现，而那四处的身份完全不同：
 *
 * <pre>
 *   product_sku.spec_json 里的一格    →  它是【存储格式】的一部分
 *   新增/修改商品的请求体             →  它是【入参】
 *   管理端和商城端的响应体            →  它是【出参】
 *   SpecJson.canonical() 的比较单元   →  它是【去重的依据】
 * </pre>
 *
 * <p>放进 {@code dto/}，就等于承认「出参可以依赖入参类」——
 * 那是个方向错误，而且会让下一个人以为「规格只有请求里才有」；
 * 放进 {@code vo/} 同理。它真正的身份是<b>协议无关的小值类型</b>：
 * 它描述的是「规格」这件事本身，不描述「谁在问」。
 *
 * <h3>★ 为什么用 record 而不是 {@code @Data}？</h3>
 *
 * <p>它是不可变的小值类型：没有 setter、没有行为、没有身份，
 * 只有两个字段的相等性判断 —— 和 {@code LoginUser} 是同一条理由。
 * 这正是 {@code LoginUser} 的类注释里写下的那条项目约定：
 * <b>不可变的值容器优先用 record。</b>
 *
 * <p>⚠️ 反过来，{@code SkuSaveDTO}（无 id）和 {@code SkuVO}（有 id）
 * 就<b>不能</b>合并成一个类 —— 它们真的不一样。判据不是「长得像不像」，
 * 而是「字段集合是不是同一个」（见 {@code SkuVO} 的注释）。
 *
 * @param name  规格名，如「颜色」。非空，同一件商品内不重复
 * @param value 规格值，如「黑」。非空
 */
public record SpecItem(String name, String value) {
}
