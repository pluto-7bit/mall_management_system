package com.example.mall.common;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p><b>为什么不用返回值表示失败？</b>
 * 假设 Service 返回 {@code null} 表示"商品不存在"，那调用方每次都得判空，
 * 判漏一处就是空指针。而且返回值只能表达一种失败，实际有"不存在""已下架"
 * "库存不足"很多种，靠返回值区分很别扭。
 *
 * <p>用异常的好处是：业务代码里只管正常流程，出错就 {@code throw}，
 * 一路向上抛到全局异常处理器统一转成 JSON 返回。中间层不用写任何错误处理代码
 * —— 这叫"异常穿透"。
 *
 * <p><b>为什么继承 RuntimeException 而不是 Exception？</b>
 * 前者是非受检异常，不用在方法签名上写 {@code throws}，调用方也不强制 try-catch，
 * 代码干净。Spring 的 {@code @Transactional} 默认也只在遇到 RuntimeException 时回滚，
 * 继承 Exception 反而会导致事务不回滚 —— 这是很常见的一个坑。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务状态码，会原样返回给前端 */
    private final Integer code;

    public BusinessException(String message) {
        this(ResultCode.ERROR, message);
    }

    public BusinessException(Integer code, String message) {
        // 把 message 传给父类，这样打印堆栈时能看到原因，方便排查
        super(message);
        this.code = code;
    }
}
