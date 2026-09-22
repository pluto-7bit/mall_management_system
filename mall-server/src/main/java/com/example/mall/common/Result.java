package com.example.mall.common;

import lombok.Getter;

/**
 * 统一响应体。所有接口都返回这个形状：
 *
 * <pre>
 * { "code": 200, "message": "success", "data": { ... } }
 * </pre>
 *
 * <p><b>为什么要统一？</b> 如果每个接口各返回各的（有的返回对象、有的返回字符串、
 * 出错时直接抛 500 页面），前端就得为每个接口写一套解析逻辑。
 * 统一之后，前端 axios 拦截器只写一次判断 {@code code != 200} 就是错误，
 * 业务代码里干干净净。
 *
 * <p><b>为什么 code 用业务码而不是 HTTP 状态码？</b>
 * HTTP 状态码只有几十个且语义固定（404 是资源不存在，不是"商品不存在"）。
 * 业务错误种类多，用自定义 code 更灵活。两者不冲突：HTTP 层照常用 200/401/500，
 * 业务层再用 code 细分。
 *
 * @param <T> 业务数据的类型，让调用方能拿到正确的类型而不是 Object
 */
@Getter
public class Result<T> {

    /** 业务状态码：200 成功，其他为失败 */
    private final Integer code;

    /** 提示信息，失败时前端直接展示给用户 */
    private final String message;

    /** 业务数据，失败时为 null */
    private final T data;

    /**
     * 构造方法私有化。
     * 强制外部只能通过下面的静态工厂方法创建，避免出现 code/data 对不上的非法组合
     * —— 这是「用类型系统防止误用」的一个常见做法。
     */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功，带数据 */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS, "success", data);
    }

    /** 成功，不带数据（用于删除这类只需要知道成败的操作） */
    public static Result<Void> success() {
        return new Result<>(ResultCode.SUCCESS, "success", null);
    }

    /** 失败，使用默认的业务失败码 */
    public static <T> Result<T> error(String message) {
        return new Result<>(ResultCode.ERROR, message, null);
    }

    /** 失败，指定业务码（比如参数校验失败用 400） */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
