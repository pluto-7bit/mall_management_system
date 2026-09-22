package com.example.mall.common;

/**
 * 业务状态码常量。
 *
 * <p>用常量而不是到处写魔法数字 {@code 200}，好处是：
 * 以后要改规则（比如成功码改成 0）只改一个地方；
 * 写错 {@code 2000} 这种手误编译期也不会报错，但用常量写错名字编译直接失败。
 *
 * <p><b>为什么要有业务错误码，直接用 HTTP 状态码不行吗？</b>
 *
 * <p>HTTP 状态码表达的是「<b>这次请求本身</b>怎么样了」：
 * <pre>
 *   200 请求成功  400 请求格式错  401 没登录  403 没权限  500 服务器炸了
 * </pre>
 * 而业务错误表达的是「<b>请求没错，但你要求的这件事做不成</b>」：
 * <pre>
 *   库存不足、订单状态不允许取消、分类下有商品不能删、余额不够
 * </pre>
 * 这些都是「请求完全合法」的情况，用 400 表达不准确，用 500 更是误导
 * （明明是正常的业务分支，却报「服务器错误」，监控上会一片红）。
 *
 * <p>所以标准做法是：<b>HTTP 状态码一律 200（表示请求处理完了），
 * 业务结果看响应体里的 code 字段</b>。
 * 本项目就是这么做的，前端拦截器里判断的正是这个 code。
 *
 * <p><b>分段规划</b>（业务变多以后不冲突）：
 * <ul>
 *   <li>200      成功</li>
 *   <li>400      参数校验失败</li>
 *   <li>401      未登录 / token 失效</li>
 *   <li>403      已登录但无权限</li>
 *   <li>500      系统内部错误</li>
 *   <li>1xxx     业务错误（库存不足、订单状态不允许等）</li>
 * </ul>
 *
 * <p><b>为什么业务错误码要从 1001 开始而不是 1、2、3？</b>
 * 留出位数，将来可以按模块分段：
 * 1xxx 商品/分类，2xxx 订单，3xxx 会员。
 * 现在还没这个必要，但把起点定在 1001，
 * 以后要分段时不用把已有代码全改一遍。
 */
public final class ResultCode {

    /** 工具类不应该被实例化 */
    private ResultCode() {
    }

    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int ERROR = 500;

    // ---- 业务错误码 ----

    /** 库存不足 */
    public static final int STOCK_NOT_ENOUGH = 1001;

    /** 订单状态不允许该操作 */
    public static final int ORDER_STATUS_INVALID = 1002;

    /** 数据不存在 */
    public static final int NOT_FOUND = 1003;

    /**
     * 分类下还挂着商品，不允许删除。
     *
     * <p>这个码是给前端做<b>差别处理</b>用的：
     * 前端可以判断 {@code code === 1004} 时弹一个「去查看该分类的商品」的按钮，
     * 而其他错误就只弹一句提示。
     * 如果所有业务错误都用同一个码，前端就只剩「显示一句错误信息」这一个选择了。
     */
    public static final int CATEGORY_HAS_PRODUCT = 1004;

    /** 名称重复（分类名、商品名等） */
    public static final int DUPLICATE_NAME = 1005;

    /**
     * 账号或密码错误。
     *
     * <p>注意「账号不存在」和「密码错误」共用这一个码 ——
     * 这是刻意的，目的是防止攻击者通过错误码差异枚举出
     * 哪些账号真实存在（见 AuthServiceImpl.login 的注释）。
     *
     * <p>「账号被禁用」也复用它，因为告诉攻击者「这个账号存在但被禁用了」
     * 同样是信息泄漏。
     */
    public static final int LOGIN_FAILED = 1006;

    /**
     * 注册时账号已被占用。
     *
     * <p><b>为什么不复用 {@link #DUPLICATE_NAME}（1005）？</b>
     *
     * <p>因为这两件事前端要做的事情不一样：
     * <ul>
     *   <li>1005 用在管理端新增分类/商品时 —— 弹一句提示就够了，
     *       操作的人知道自己刚填了什么</li>
     *   <li>1007 用在用户注册时 —— 前端要把光标<b>聚焦回账号输入框</b>，
     *       提示「换一个试试」，而不是让用户自己去找是哪一项出了问题</li>
     * </ul>
     *
     * <p>这是一种常见的取舍：错误码分得越细，前端能做的差别处理就越多，
     * 但码表也越长。判断标准是<b>「前端需不需要针对它做不同的动作」</b>，
     * 而不是「这两个错误在语义上是不是同一类」。
     */
    public static final int USERNAME_TAKEN = 1007;

    /**
     * 购物车里单个商品的数量超出上限，或者超过库存。
     *
     * <p><b>为什么不用 {@link #STOCK_NOT_ENOUGH}（1001）？</b>
     *
     * <p>虽然「加购数量超过库存」看起来就是库存不足，但前端要做的事不一样：
     * <ul>
     *   <li>1001 库存不足 —— 发生在<b>下单</b>时。用户已经点了「提交订单」，
     *       前端要做的是<b>刷新购物车</b>让他看到最新库存，因为刚刚
     *       很可能被别人买走了一部分</li>
     *   <li>1008 加购超限 —— 发生在<b>购物车</b>里。用户还在挑东西，
     *       前端要做的是<b>把数量输入框改回一个合法值</b>，
     *       页面其他部分不用动</li>
     * </ul>
     *
     * <p>判断标准依然是 {@link #USERNAME_TAKEN} 注释里那条：
     * <b>「前端需不需要针对它做不同的动作」</b>。
     */
    public static final int CART_QUANTITY_LIMIT = 1008;
}
