package com.example.mall.common;

/**
 * 当前请求的登录用户 —— 基于 {@link ThreadLocal} 的「请求级全局变量」。
 *
 * <h3>解决什么问题</h3>
 *
 * <p>没有它的话，Controller 想拿当前用户 id 就得这样：
 * <pre>
 *   public Result&lt;Void&gt; create(@Valid @RequestBody ProductSaveDTO dto,
 *                              HttpServletRequest request) {   // ← 多一个参数
 *       LoginUser user = (LoginUser) request.getAttribute("user");
 *       productService.create(dto, user.getId());            // ← Service 方法全要改签名
 *   }
 * </pre>
 * 而且这个 id 会被一路往下传：Controller → Service → 别的 Service……
 * 每一层的方法签名上都要多一个「跟业务逻辑毫无关系」的参数。
 *
 * <p>有了 UserContext，业务代码里任何地方都能直接 {@code UserContext.get()}，
 * 方法签名保持干净。
 *
 * <h3>ThreadLocal 是什么</h3>
 *
 * <p>可以理解成一个「每个线程各自一份的储物柜」。
 * Tomcat 处理每个请求都用一个独立的线程，
 * 所以请求 A 存进去的东西，请求 B 读不到 —— 这正是我们要的效果。
 *
 * <pre>
 *   请求A → 线程1 → UserContext.set(张三)  ┐
 *   请求B → 线程2 → UserContext.set(李四)  ┘ 互不干扰
 * </pre>
 *
 * <h3>★ 必须清理，否则会串数据</h3>
 *
 * <p><b>这是 ThreadLocal 最经典的坑。</b>Tomcat 的线程是<b>重复使用</b>的
 * （线程池），一个线程处理完请求 A 后不会销毁，而是去处理请求 B。
 * 如果 A 存进去的值没清掉，B 就会读到 A 的身份：
 * <pre>
 *   线程1 处理「张三的请求」→ set(张三) → 请求结束，没清理
 *   线程1 被复用，处理「匿名请求」→ get() 拿到的是【张三】
 *   → 匿名用户拥有了张三的权限
 * </pre>
 *
 * <p>所以拦截器的 {@code afterCompletion} 里<b>一定要调 {@code clear()}</b>。
 * 这个 bug 极难复现（取决于线程调度），但一旦出现就是严重的越权事故。
 *
 * <p>另外注意 {@code clear()} 里用的是 {@code remove()} 而不是 {@code set(null)}：
 * {@code set(null)} 会让这个 ThreadLocal 在 Map 里留下一个 key 存在、
 * value 为 null 的条目，仍然占着内存；{@code remove()} 才是真正把条目删掉。
 */
public final class UserContext {

    private UserContext() {
    }

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 由拦截器在校验通过后调用 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /**
     * 获取当前登录用户。
     *
     * @return 未登录时返回 null。需要「必须登录」语义的代码
     *         可以直接用 {@link #require()}，省掉一次判空
     */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 获取当前登录用户，未登录直接抛异常。
     *
     * <p>为什么要有这个方法？因为理论上被拦截器保护的接口里
     * {@code get()} 不可能返回 null。但「理论上不可能」和
     * 「代码里能看出来」是两回事：如果每个地方都写
     * {@code if (user == null) throw ...}，一是啰嗦，
     * 二是总有一天有人会忘记写，然后得到一个莫名其妙的空指针。
     *
     * <p>把「不可能发生」这件事集中在一个地方断言，
     * 比散落在十几处判空更可靠。
     */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            // 走到这里说明拦截器配置漏了某个路径，
            // 属于配置错误而不是用户操作问题，所以用 IllegalStateException
            throw new IllegalStateException("当前请求没有登录用户，请检查拦截器是否覆盖了该路径");
        }
        return user;
    }

    /** 由拦截器在请求结束时调用。★ 必须调用，见类注释 */
    public static void clear() {
        HOLDER.remove();
    }
}
