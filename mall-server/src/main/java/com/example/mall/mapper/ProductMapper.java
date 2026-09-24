package com.example.mall.mapper;

import com.example.mall.dto.ProductQueryDTO;
import com.example.mall.dto.ShopProductQueryDTO;
import com.example.mall.entity.Product;
import com.example.mall.vo.AdminProductDetailVO;
import com.example.mall.vo.ProductVO;
import com.example.mall.vo.ShopProductDetailVO;
import com.example.mall.vo.ShopProductVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品数据访问接口。
 *
 * <p><b>这个接口没有实现类</b>，方法体写在
 * {@code src/main/resources/mapper/ProductMapper.xml} 里。
 * MyBatis 在启动时会扫描接口，用 JDK 动态代理为它生成实现类，
 * 把接口方法和 XML 里的 {@code <select id="...">} 按<b>方法名</b>对应起来。
 *
 * <p>启动类上的 {@code @MapperScan("com.example.mall.mapper")} 就是指定扫描范围。
 *
 * <p><b>本层唯一的职责是「读写数据库」</b>，规则是：
 * <ul>
 *   <li>可以：拼 SQL、传参、映射结果</li>
 *   <li>不可以：写业务判断（如「库存不足就抛异常」）、开事务、调用别的 Service</li>
 * </ul>
 * 一旦 Mapper 里出现 {@code if (stock < 0)} 这类逻辑，就说明业务代码漏到数据层了。
 */
public interface ProductMapper {

    /**
     * 分页查询商品列表（带分类名称）。
     *
     * <p>返回 VO 而不是 Entity，因为要带 join 出来的 categoryName。
     *
     * @param query 查询条件，含 name/categoryId/status 和分页参数
     * @return 当前页的商品列表，可能为空列表但不会是 null
     */
    List<ProductVO> selectPage(ProductQueryDTO query);

    /**
     * 查询满足条件的总记录数，供分页器使用。
     *
     * <p><b>为什么要单独查一次？</b> 因为分页需要知道总数才能算出总页数，
     * 而 {@code LIMIT} 只返回当前页的数据，不告诉你总共多少条。
     * 所以列表接口标准做法是两条 SQL：一条查数据，一条查总数。
     *
     * <p>两条 SQL 的 {@code WHERE} 条件必须完全一致，否则会出现
     * 「翻到第 3 页却是空的」这种诡异现象 —— 页面数按总数算出来有 5 页，
     * 但实际数据只够 2 页。这是新手常犯的错。
     */
    long countByQuery(ProductQueryDTO query);

    /**
     * 统计某个分类下的商品数量。
     *
     * <p>给 {@code CategoryServiceImpl.delete()} 用：分类下还有商品时不允许删除。
     *
     * <p><b>为什么这个方法在 ProductMapper 而不是 CategoryMapper？</b>
     * 因为它查的是 {@code product} 表。规则是「谁的表谁负责查」——
     * 把 product 表的查询集中在一个地方，将来给 product 加逻辑删除
     * （{@code is_deleted}）时，只需要改这一个文件。
     *
     * <p><b>用 COUNT(*) 而不是把商品查出来再 {@code list.size()}：</b>
     * COUNT 由数据库在服务端算完只返回一个数字，
     * 而查出来要传输所有行的所有字段。数量一多差距就是数量级的。
     * 「能用 SQL 算的就别拉到内存里算」是一条基本的性能意识。
     *
     * @return 商品数量，没有商品时返回 0
     */
    long countByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 根据 id 查询商品详情（带分类名称）。
     *
     * <p>★ 里程碑 11：返回类型从 {@link ProductVO} 换成了它的子类
     * {@link AdminProductDetailVO}。<b>{@code resultType} 天然支持子类</b>——
     * MyBatis 是按 setter 往对象里填值的，子类继承了父类全部的 setter，
     * 所以 XML 里那条 SQL 一个字都不用改。
     *
     * <p>⚠️ 但需要知道：<b>改 XML 里的 {@code resultType} 不会改变这个方法的签名</b>，
     * 两处必须一起改。只改 XML 的话，MyBatis 照样能填出一个
     * {@code AdminProductDetailVO} 实例，但 Java 这边拿到的静态类型还是父类，
     * 于是 {@code getImages()} 编译不过，报的还是一个和真实原因
     * （XML 和接口不同步）相距很远的错误。
     *
     * <p>⚠️ 还有一点：{@code images} <b>不在这个方法里查</b>。
     * 它是 Service 拿到商品之后单独查一次装进去的，
     * 理由见 {@code ProductServiceImpl.getById} 的 javadoc。
     * 所以这个方法返回的对象里 {@code images} 永远是 null ——
     * <b>不要以为忘了查</b>。
     *
     * @return 查不到时返回 null，由 Service 决定怎么处理
     */
    AdminProductDetailVO selectById(@Param("id") Long id);

    /**
     * 查询商品实体，供更新、删库存等写操作之前做校验用。
     *
     * <p>和 {@code selectById} 的区别是它不 join 分类表，少一次关联查询。
     * 写操作通常不需要分类名，用这个更省。
     */
    Product selectEntityById(@Param("id") Long id);

    /**
     * 只查一件商品的规格定义（{@code spec_schema} 那一列，原始 JSON 文本）。
     *
     * <p>★ 里程碑 15 新增。管理端详情要把它<b>解析成 {@code List<SpecGroup>}</b>
     * 回给前端，而解析这一步只能由 Service 显式做 —— 见 XML 里那段注释：
     * {@code selectById} 的 {@code resultType} 上那个字段是 List，
     * 数据库里是 VARCHAR，MyBatis 没有现成的 TypeHandler 把前者映射成后者。
     *
     * <p>⚠️ 返回的是<b>文本</b>，可能是 {@code "[]"}，也可能是 {@code null}
     * （阶段 1 回填的那批老数据没写过这一列）。{@code null} 的含义是
     * 「无规格」，不是「查不到」—— 解析请一律走 {@code SpecJson.schemaOf}，
     * 它已经把这两种情况统一处理掉了。
     *
     * @return 原始 JSON 文本，商品不存在时为 null
     */
    String selectSpecSchema(@Param("id") Long id);

    /**
     * 批量查一批商品的规格定义（{@code spec_schema} 那一列，原始 JSON 文本）。
     *
     * <p>★ 里程碑 15 阶段 3 新增。它存在的理由是「<b>别写成 N+1</b>」：
     * 用户端的 {@code ShopSkuServiceImpl} 要渲染一批 SKU 的 {@code specText}，
     * 而那句话里维度的显示顺序来自各自商品的 {@code spec_schema} ——
     * 逐个商品调 {@link #selectSpecSchema} 就是「车里 10 件商品发 10 条 SQL」。
     * 这条一次查完。
     *
     * <p>⚠️ <b>返回值里只有 {@code id} 和 {@code specSchema} 两个字段有值</b>，
     * 其余全是 null —— 这条 SQL 的 SELECT 列表里就只有那两列。
     * 这不是偷懒，是<b>刻意的</b>：调用方只拿它当「id → 规格定义」的载体，
     * 查别的列纯属浪费。{@link Product} 在这里的角色是<b>载体</b>而不是实体，
     * 这一点和它平时「product 表的一行」的用法不同，所以写在注释里而不是靠猜。
     *
     * <p>★ 为什么不干脆返回一个 {@code Map<Long, String>}？
     * 因为 MyBatis 的 {@code resultType="map"} 会得到一个
     * {@code List<Map<String,Object>>}，列名的字符串键和 {@code Object} 值
     * 会让调用处退化成一堆强制转换 —— 那比一个「有两个字段有值」的载体更难读。
     *
     * @param ids 商品 id 列表，<b>调用方必须保证非空</b>（空的 {@code IN ()} 是语法错误）
     * @return 每个商品一行，只有 id 和 specSchema。查不到的商品不会出现在结果里
     */
    List<Product> selectSpecSchemasByProductIds(@Param("ids") List<Long> ids);

    /**
     * 新增商品。
     *
     * <p>插入成功后，MyBatis 会把数据库生成的自增主键<b>回填</b>到传入的
     * {@code product} 对象的 id 字段上（靠 {@code useGeneratedKeys="true"} 配置）。
     * 所以调用完这个方法，{@code product.getId()} 就有值了，
     * 不需要再查一次数据库。
     *
     * @return 影响行数，正常为 1
     */
    int insert(Product product);

    /**
     * 根据 id 更新商品。
     *
     * <p>只更新非 null 的字段（靠 XML 里的 {@code <if>} 判断），
     * 这样同一个方法既能做「全量更新」也能做「只改某几个字段」。
     *
     * @return 影响行数。返回 0 说明 id 不存在
     */
    int updateById(Product product);

    /**
     * 根据 id 删除商品。
     *
     * <p>学到这里先做物理删除（真的 DELETE 掉）。
     * 真实业务里更常用「逻辑删除」——加一个 {@code is_deleted} 字段标记，
     * 数据其实还在库里，只是查询时过滤掉。
     * 原因是订单要关联商品，商品被真删了历史订单就成了孤儿数据。
     * 等做完订单模块，可以回来把它改成逻辑删除。
     *
     * @return 影响行数。返回 0 说明 id 不存在
     */
    int deleteById(@Param("id") Long id);

    // ==========================================================================
    // 以下是用户端（商城前台）用的查询
    //
    // ★ 为什么另开一组方法，而不是给上面的方法加个 isShop 参数？
    //
    //   因为两组查询的规则不同，而且是【安全规则】不同：
    //     管理端：status 由前端指定，想看下架商品随时可以
    //     用户端：**永远只查 status = 1**，这个条件不能由任何输入改变
    //
    //   如果把「要不要过滤 status」做成一个参数，那就等于把这个决定权
    //   交给了调用方 —— 哪天有人在用户端的 Service 里漏传了这个参数，
    //   或者传了个 false，下架商品就泄露了，而且不会有任何报错。
    //   写死在 SQL 里，才是真的写死。
    //
    //   多写两个方法，换「这条规则不可能被绕过」，非常划算。
    // ==========================================================================

    /**
     * 用户端分页查询商品（只返回上架商品）。
     *
     * @param query 关键词 / 分类 / 排序 / 分页。注意它<b>没有 status 字段</b>
     * @return 当前页的商品列表
     */
    List<ShopProductVO> selectShopPage(ShopProductQueryDTO query);

    /**
     * 用户端列表的总数，配合 {@link #selectShopPage} 使用。
     *
     * <p>WHERE 条件同样用 {@code <include>} 共享，保证和列表查询一致 ——
     * 不一致的话分页会算出「有 5 页但只能翻到 2 页」这种诡异结果。
     */
    long countShopByQuery(ShopProductQueryDTO query);

    /**
     * 用户端查商品详情。
     *
     * <p>只查上架商品。下架的、不存在的，一律返回 null，
     * 由 Service 统一转成「商品不存在」——
     *
     * <p><b>★ 注意这里刻意不区分「不存在」和「已下架」。</b>
     * 如果下架时返回「该商品已下架」，而 id 不存在时返回「商品不存在」，
     * 就等于告诉外界「这个 id 是存在的，只是下架了」。
     * 对于「按 id 遍历探测商品」的人来说，这是有效信息 ——
     * 他能据此摸出你的商品编号范围和大概的商品总量。
     * （同理，管理端登录的「账号或密码错误」也是这个思路。）
     *
     * @return 查不到或已下架时返回 null
     */
    ShopProductDetailVO selectShopById(@Param("id") Long id);

    /**
     * 按一组 id 批量查询上架商品，给购物车用。
     *
     * <p>购物车在 Redis 里只存了「商品 id → 数量」，
     * 展示需要的商品名、价格、封面都得回 MySQL 查。而购物车里有几件商品
     * 就要查几条 —— <b>如果循环调 {@code selectShopById}，就是经典的 N+1 查询</b>：
     * 10 件商品发 10 条 SQL。用 {@code IN} 一条搞定。
     *
     * <p>数据量小的时候两种写法看起来一样快，差别要到几十上百条才显出来。
     * 但「能一条 SQL 查完的绝不循环查」应该是条件反射，
     * 而不是等到性能出问题才想起来。
     *
     * <p><b>★ 注意返回的条数可能少于传入的 id 个数，而且这是正常的。</b>
     * 购物车里的商品可能已经下架或被删了（本查询带 {@code status = 1}），
     * 那么它就查不出来。调用方必须自己处理「id 在购物车里、
     * 但结果里没有」的情况 —— 这正是购物车页面「失效商品」功能的来源。
     * <b>不要假设返回的 list 和传入的 ids 一一对应。</b>
     *
     * @param ids 商品 id 列表。<b>调用方必须保证非空</b> ——
     *            空的 {@code IN ()} 是 SQL 语法错误，
     *            XML 里用 {@code <foreach>} 也生成不出合法 SQL
     * @return 其中【仍在架上】的商品，可能为空列表
     */
    List<ShopProductVO> selectShopByIds(@Param("ids") List<Long> ids);

    // ==========================================================================
    // ★ 里程碑 15 阶段 4：decreaseStock / increaseStock 【已从这里删除】，
    //   搬去了 ProductSkuMapper（decreaseSkuStock / increaseSkuStock）。
    //
    //   理由不是「同类的东西放一起好看」，而是【库存的定义者换人了】：
    //     阶段 4 之前  product.stock 是库存      → 扣它
    //     阶段 4 之后  product_sku.stock 是库存  → 扣它
    //
    //   而本轮的不变量是「product 表上没有价格，也没有库存」。
    //   一个表上没有的东西，这里就不该有改它的方法 ——
    //   留着 decreaseStock 等于留了一条「把库存写回一个已经不表示库存的列」的路，
    //   而且它【不会报错】：product.stock 那一列要到阶段 6 才删。
    //   静默地扣错表，比编译不过危险得多。
    //
    //   ★ 阶段 6 跑完 migration-13b 之后，那两列真的没有了 ——
    //     于是一个假设可以回头核对一下：**这个删除到底有没有必要？**
    //     如果当初把 decreaseStock 留在 ProductMapper 里，
    //     现在它会是编译不过（列没了，INSERT/UPDATE 语句里写着它）
    //     而不是「静默扣错表」。也就是说阶段 6 之后，
    //     「删方法」这件事本身不再救你 —— 救你的是**阶段 4 那时就删掉**。
    //     先删方法、再删列，两件事的次序不能反：
    //     列先没了，方法就变成「编译期报错」这种最好的形式了，
    //     但那要等到阶段 6；而阶段 4~6 之间那段有两列都在的时间，
    //     才是静默出错的高危窗口。
    //     ★ 教训：**「删掉一个定义者」和「删掉它留下的副本」不是同一个动作，
    //       而且前者必须在前。**
    //
    //   ⚠️ 而 selectShopById / selectShopByIds 【留在这里，没有删】——
    //      它们不是库存方法，是「用户端能看见什么」的唯一定义
    //      （WHERE p.status = 1）。阶段 3 的 ShopSkuServiceImpl 正是靠
    //      「SKU 查到了、但它所属商品查不到 → 不可买」复用这一条规则。
    //      删掉它们，这条规则就会长出第二份实现。
    // ==========================================================================
}
