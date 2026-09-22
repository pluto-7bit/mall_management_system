package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.LoginUser;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.dto.CartAddDTO;
import com.example.mall.dto.CartQuantityDTO;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.service.CartService;
import com.example.mall.vo.CartItemVO;
import com.example.mall.vo.CartVO;
import com.example.mall.vo.ShopProductDetailVO;
import com.example.mall.vo.ShopProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物车业务实现（Redis Hash）。
 *
 * <h3>Redis 里的数据结构</h3>
 *
 * <pre>
 *   key    mall:cart:1          ← 会员 id = 1 的购物车
 *   field  "5"                  ← 商品 id（字符串）
 *   value  "3"                  ← 数量
 *
 *   用 redis-cli 看：
 *     HGETALL mall:cart:1
 *     1) "5"    2) "3"
 *     3) "7"    4) "1"
 * </pre>
 *
 * <p>为什么是 Hash 而不是「一个 key 一个商品」（{@code cart:1:5 = 3}）？
 * <ul>
 *   <li>Hash 能一条命令拿到整车（{@code HGETALL}），
 *       而分散的 key 要先用 {@code KEYS cart:1:*} 找出来再逐个取 ——
 *       {@code KEYS} 在生产上是禁用命令，它会把整个 Redis 阻塞住</li>
 *   <li>Hash 能一条命令清空整车（{@code DEL} 一个 key），
 *       分散的 key 得先找再一个个删</li>
 *   <li>「整车」本来就是这个数据天然的边界，用 Hash 正好把它表达出来</li>
 * </ul>
 *
 * <p>为什么 key 前面要加 {@code mall:} 前缀？因为 Redis 是<b>共享的</b> ——
 * 同一台 Redis 上可能还跑着别的项目、别的服务。
 * 不加前缀的话，别人也用 {@code cart:1} 这个 key 就直接撞了，
 * 而且撞车时的现象极其诡异（两边的数据互相覆盖、数量乱跳），
 * 排查起来会怀疑人生。<b>给 key 加业务前缀，是使用共享 Redis 的基本礼貌。</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final StringRedisTemplate redisTemplate;
    private final ProductMapper productMapper;

    /** key 前缀。提成常量，避免在六七个方法里各写一遍字符串 */
    private static final String KEY_PREFIX = "mall:cart:";

    /**
     * 购物车的过期时间：30 天。
     *
     * <p><b>为什么购物车要设过期时间？</b>因为不设的话它会<b>永远存在</b>。
     * 一个用户注册后加了件东西再也没回来，这条记录就永久占着 Redis 内存。
     * 日积月累，Redis 会被这些「僵尸购物车」撑爆 ——
     * 而 Redis 是内存数据库，撑爆的后果是<b>整个服务不可用</b>，不只是购物车坏掉。
     *
     * <p><b>为什么是 30 天？</b>这是业务判断，不是技术判断：
     * <pre>
     *   太短（1 天）→ 用户周末看上的东西，周一回来发现购物车空了，很恼火
     *   太长（1 年）→ 和不过期区别不大，内存还是会被占满
     * </pre>
     * 电商行业普遍在 15~90 天之间，30 天是个常见值。
     *
     * <p><b>★ 注意过期时间是「每次写操作都刷新」的</b>（见 {@link #touch}）。
     * 这是「滑动过期」：只要你还在用购物车，
     * 它就一直续期；一旦 30 天没有任何操作，才会真的被清掉。
     * 这正是我们想要的效果 —— 衡量标准是「用户还活不活跃」，
     * 而不是「这个购物车创建了多久」。
     */
    private static final Duration CART_TTL = Duration.ofDays(30);

    /**
     * 单个商品在购物车里的数量上限。
     *
     * <p><b>★ 这个数字只在这里定义一次，这就是刻意的。</b>
     *
     * <p>最初 {@code CartAddDTO} 和 {@code CartQuantityDTO} 上也各写了
     * 一个 {@code @Max(99)}，看起来是「双重保险」，实际有两个坏处：
     * <pre>
     *   1. 「最多买 99 件」这条规则有了三个出处。哪天运营说改成 50，
     *      改了两个漏了一个，就出现「加不进去但改得上去」的矛盾现象
     *   2. 更要命的是，DTO 上的 @Max 会在【参数绑定阶段】就把请求拒掉，
     *      于是这里判 99 上限的代码永远执行不到 —— 变成死代码。
     *      读代码的人会以为它在生效，实际上它在骗人
     * </pre>
     *
     * <p>现在的分工：DTO 只做协议层的合理性检查（不为空、是正数、
     * 不是 999999999 这种明显乱填的），业务上限一律由这里说了算。
     *
     * <p><b>为什么这个上限必须放在 Service 而不是 DTO？</b>
     * 三个理由，缺一不可：
     * <ul>
     *   <li>DTO 只拦得住「单次请求传了 500」，拦不住「传两次 60」——
     *       上限要基于<b>累加后</b>的总数判断</li>
     *   <li>它还得和<b>库存</b>取最小值（{@code Math.min}），
     *       而库存要查库才知道，DTO 里根本拿不到</li>
     *   <li>绕过页面直接调接口的请求，走的也是这里</li>
     * </ul>
     *
     * <p><b>DTO 校验是给用户看的友好提示，Service 里的校验才是真的规则。</b>
     * 两者都要有，但管的不是同一件事。
     */
    private static final int MAX_QUANTITY_PER_ITEM = 99;

    // ==========================================================================
    // 写操作
    // ==========================================================================

    @Override
    public void add(CartAddDTO dto) {
        Long memberId = currentMemberId();
        Long productId = dto.getProductId();

        // ① 先确认这个商品真的能买。
        //    这一步查的是 MySQL（有 status = 1 的过滤），
        //    所以「往购物车里塞一个不存在的商品 id」是做不到的
        ShopProductDetailVO product = requireAvailableProduct(productId);

        String key = cartKey(memberId);
        String field = String.valueOf(productId);

        // ② ★ 用 HINCRBY 原子累加，而不是「先 HGET 读出来，加一下，再 HSET 写回去」。
        //
        //    区别在并发下会暴露：用户连点两下「加入购物车」，
        //    两个请求同时读到 2，各自算出 3，各自写回 3 —— 结果只加了 1 件，
        //    用户以为加了 2 件。这就是经典的「读-改-写」竞态，
        //    也叫「丢失更新」（lost update）。
        //
        //    HINCRBY 是 Redis 的【单条原子命令】，整个过程不可分割，
        //    所以两次点击会得到 4，符合预期。
        //
        //    ★ 这个模式在数据库里同样存在，而且更重要 ——
        //      里程碑 8 扣库存时会是同一个问题的另一个版本。
        //      到时候的解法是「UPDATE product SET stock = stock - 1
        //      WHERE id = ? AND stock >= ?」，把「读」和「改」
        //      合并进一条 SQL，思路和 HINCRBY 完全一样。
        Long newQty = redisTemplate.opsForHash().increment(key, field, dto.getQuantity());

        // ③ 累加之后才判断上限。顺序反过来（先读、判断、再写）就又回到竞态了。
        //    所以宁可「先写下去，发现超了再改回来」——
        //    这样即使并发，最终值也是被修正过的
        int limit = Math.min(MAX_QUANTITY_PER_ITEM, product.getStock());
        if (newQty != null && newQty > limit) {
            // 超过库存时，把数量【压到能买的上限】而不是直接拒绝。
            //
            // 为什么不拒绝？因为「拒绝」意味着用户点加入购物车后
            // 什么都没发生，还得自己猜原因。压到上限则是
            // 「我帮你加了，但只能加这么多」—— 更友好。
            // 但如果连 1 件都买不到（库存 0），那就必须明确报错
            if (limit <= 0) {
                // 把刚才 HINCRBY 加上去的数字扣回来，别让非法数量留在车里
                rollbackIncrement(key, field, dto.getQuantity());
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "该商品已售罄");
            }
            redisTemplate.opsForHash().put(key, field, String.valueOf(limit));
            touch(key);
            log.info("加购数量超过上限，已压到上限: memberId={}, productId={}, 请求后={}, 上限={}",
                    memberId, productId, newQty, limit);
            throw new BusinessException(ResultCode.CART_QUANTITY_LIMIT,
                    "最多只能买 " + limit + " 件，已为你调整");
        }

        touch(key);
        log.info("加入购物车: memberId={}, productId={}, +{}, 现在={}", memberId, productId, dto.getQuantity(), newQty);
    }

    @Override
    public void updateQuantity(Long productId, CartQuantityDTO dto) {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);
        String field = String.valueOf(productId);

        // ★ 先确认这件商品【已经在车里】。
        //
        //   如果不检查，PUT 一个从没加过的商品就会凭空创建一条记录 ——
        //   那 PUT 就默默变成了 POST，"改了数量"和"新增商品"混在一起，
        //   接口就没法只靠 URL 和方法表达意图了。
        //
        //   ★ 注意这个检查和后面的写操作之间也有竞态（可能刚检查完
        //     就被另一个标签页删掉了）。但后果仅仅是"删了又出现"，
        //     用户重新删一次即可，不值得为它加锁。
        //     判断一个竞态要不要处理，标准是【后果有多严重】，不是【存不存在】。
        Boolean exists = redisTemplate.opsForHash().hasKey(key, field);
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "购物车里没有这个商品");
        }

        ShopProductDetailVO product = requireAvailableProduct(productId);

        int limit = Math.min(MAX_QUANTITY_PER_ITEM, product.getStock());
        if (limit <= 0) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "该商品已售罄");
        }
        if (dto.getQuantity() > limit) {
            throw new BusinessException(ResultCode.CART_QUANTITY_LIMIT, "最多只能买 " + limit + " 件");
        }

        // 这个场景可以用 HSET（直接设值）而不是 HINCRBY ——
        // 因为 PUT 的语义就是「改成某个确定的值」，
        // 两次同样的请求结果相同，本身是幂等的
        redisTemplate.opsForHash().put(key, field, String.valueOf(dto.getQuantity()));
        touch(key);
        log.info("修改购物车数量: memberId={}, productId={}, -> {}", memberId, productId, dto.getQuantity());
    }

    @Override
    public void remove(Long productId) {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // ★ 返回值是「实际删掉了几条」，0 表示本来就不在车里。
        //   我们【不】把它当错误 —— 幂等语义，见 CartService.remove 的注释
        Long removed = redisTemplate.opsForHash().delete(key, String.valueOf(productId));
        touch(key);
        log.info("从购物车移除: memberId={}, productId={}, 实际删除={}", memberId, productId, removed);
    }

    @Override
    public void clear() {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // ★ 用 DEL 删整个 key，而不是 HGETALL 拿到所有 field 再一个个 HDEL。
        //   一条命令 vs N+1 条命令。这是「选择正确的数据结构和命令」
        //   带来的直接收益 —— 设计对了，代码自然就简单且快
        Boolean deleted = redisTemplate.delete(key);
        log.info("清空购物车: memberId={}, key 是否存在并已删除={}", memberId, deleted);
    }

    // ==========================================================================
    // 读操作
    // ==========================================================================

    @Override
    public CartVO getCart() {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // entries() 就是 HGETALL。它返回 Map<String, String>：
        // { "5": "3", "7": "1" }
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);

        CartVO vo = new CartVO();
        if (raw.isEmpty()) {
            // ★ 空购物车要返回【结构完整的空对象】，而不是 null。
            //   返回 null 的话前端得写 if (cart) {...}，
            //   而 {items: [], totalQuantity: 0, totalAmount: 0.00}
            //   可以直接渲染成「购物车是空的」。
            //
            //   这是接口设计里很实用的一条：**能返回空集合/空对象，就别返回 null。**
            //   每次让调用方判空，都是在给未来的空指针埋雷。
            vo.setItems(new ArrayList<>());
            vo.setTotalQuantity(0);
            vo.setTotalAmount(BigDecimal.ZERO);
            return vo;
        }

        // 把 field（商品 id）转成 Long。
        // ★ 这里用了 LinkedHashMap 保留 Redis 返回的顺序 ——
        //   注意这只是「尽量」，Redis 不保证 Hash 的顺序稳定
        //   （元素少时用 listpack 编码恰好有序，元素多了会变成 hashtable 就无序了）。
        //   所以下面会显式排序，不依赖这个顺序。
        Map<Long, Integer> cartMap = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            Long pid = parseLongOrNull(e.getKey());
            Integer qty = parseIntOrNull(e.getValue());
            // Redis 里理论上不会出现脏数据（只有本类会写它），
            // 但「理论上不会」和「一定不会」是两回事。
            // 读到无法解析的值时跳过并记一条 warn，别让整个购物车打不开
            if (pid == null || qty == null || qty <= 0) {
                log.warn("购物车里有无法解析的记录，已跳过: memberId={}, key={}, field={}, value={}",
                        memberId, key, e.getKey(), e.getValue());
                continue;
            }
            cartMap.put(pid, qty);
        }

        if (cartMap.isEmpty()) {
            vo.setItems(new ArrayList<>());
            vo.setTotalQuantity(0);
            vo.setTotalAmount(BigDecimal.ZERO);
            return vo;
        }

        // 一条 SQL 把所有商品捞出来，而不是循环查（N+1 查询）
        List<ShopProductVO> products = productMapper.selectShopByIds(new ArrayList<>(cartMap.keySet()));
        Map<Long, ShopProductVO> productMap = new HashMap<>();
        for (ShopProductVO p : products) {
            productMap.put(p.getId(), p);
        }

        List<CartItemVO> items = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Map.Entry<Long, Integer> e : cartMap.entrySet()) {
            Long pid = e.getKey();
            Integer qty = e.getValue();

            CartItemVO item = new CartItemVO();
            item.setProductId(pid);
            item.setQuantity(qty);

            ShopProductVO p = productMap.get(pid);
            if (p == null) {
                // ★★ 这就是「购物车里有，但商品表里查不到」的情况 ——
                //    商品下架了或者被删了。
                //
                //    这是购物车功能【必然会遇到】的场景，不是异常情况：
                //    运营下架一个商品，所有把它加进购物车的用户都会进入这个分支。
                //    所以它必须有一条明确的、想清楚了的处理路径。
                //
                //    我们的处理是：**保留在列表里，但标记为失效**。
                //    为什么不是直接删掉？
                //      - 用户会困惑：「我明明加了 3 件，怎么没了？」
                //      - 更糟的是，如果用户加购后一直在等降价，
                //        东西悄悄消失了他根本不知道发生过什么
                //    显示成灰色的「已失效」并告诉他原因，用户才知道该怎么办。
                item.setAvailable(false);
                item.setUnavailableReason("商品已下架");
                items.add(item);
                continue;
            }

            item.setName(p.getName());
            item.setPrice(p.getPrice());
            item.setCover(p.getCover());
            item.setCategoryName(p.getCategoryName());
            item.setStock(p.getStock());

            if (p.getStock() <= 0) {
                // 商品还在，但卖光了。
                // 注意【保留 price】：用户有权知道自己当初想买的东西多少钱
                item.setAvailable(false);
                item.setUnavailableReason("已售罄");
                items.add(item);
                continue;
            }

            if (qty > p.getStock()) {
                // 商品还在、还有货，但【不够用户加购的数量】。
                // 这种情况很常见：用户加了 5 件，然后被别人买走了 3 件。
                //
                // 这里刻意【不自动改小数量】—— 那是替用户做决定。
                // 只标记出来让他自己改，改动权在他手上。
                item.setAvailable(false);
                item.setUnavailableReason("库存只剩 " + p.getStock() + " 件");
                // 小计照算，方便前端展示「原价 × 数量」
                item.setSubtotal(p.getPrice().multiply(BigDecimal.valueOf(qty)));
                items.add(item);
                continue;
            }

            item.setAvailable(true);
            // ★ 金额计算一律用 BigDecimal。
            //   用 double 的话 0.1 + 0.2 != 0.3，
            //   购物车里 3 件 0.1 元的商品会算出 0.30000000000000004 元。
            //   钱的计算里出现这种数字是灾难性的 —— 它会一路传到订单、发票、对账
            item.setSubtotal(p.getPrice().multiply(BigDecimal.valueOf(qty)));

            // ★ 只有【能买】的商品才计入合计。
            //   已经售罄/下架的东西不该出现在「你要付多少钱」里
            totalQuantity += qty;
            totalAmount = totalAmount.add(item.getSubtotal());

            items.add(item);
        }

        // ★ 显式排序：失效的排后面，然后按商品 id 倒序（新加的在前）。
        //
        //   不能依赖 Redis Hash 的返回顺序 —— 那取决于它的内部编码，
        //   元素少的时候恰好有序，多了就变了。依赖它就是依赖一个
        //   「现在碰巧成立」的假设。
        //
        //   「失效的沉到底部」是电商购物车的通行做法：
        //   用户打开购物车是为了结算，能买的必须一眼看到
        items.sort(Comparator
                .comparing(CartItemVO::getAvailable, Comparator.reverseOrder())
                .thenComparing(CartItemVO::getProductId, Comparator.reverseOrder()));

        vo.setItems(items);
        vo.setTotalQuantity(totalQuantity);
        vo.setTotalAmount(totalAmount);
        return vo;
    }

    @Override
    public int count() {
        Long memberId = currentMemberId();
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(cartKey(memberId));

        int total = 0;
        for (Object v : raw.values()) {
            Integer qty = parseIntOrNull(v);
            if (qty != null && qty > 0) {
                total += qty;
            }
        }
        return total;
    }

    // ==========================================================================
    // 给下单用的两个方法（里程碑 8）
    // ==========================================================================

    @Override
    public Map<Long, Integer> readQuantities(List<Long> productIds) {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // ★ 用 HMGET 而不是 HGETALL + 自己筛。
        //
        //   购物车里可能有 20 种商品，而这次只结算 2 种。
        //   HGETALL 会把 20 种全部拉回应用服务器，再丢掉 18 种 ——
        //   网络传了 10 倍的数据，只为了扔掉 90%。
        //
        //   但 HMGET 需要把 field 转成 String 数组：
        //     HMGET mall:cart:1 "5" "7"
        //   返回按顺序对应的值列表，不存在的 field 返回 null。
        //
        //   ★ 这里有个容易踩的坑：HMGET 返回的顺序【和请求的顺序一一对应】，
        //     所以可以按下标把 field 和 value 配起来。
        //     但一定要用【同一个数组】去遍历，不能一边按原 list 遍历
        //     一边按返回的 list 取下标 —— 两边顺序一旦不一致就错位了，
        //     而且错位后是把 A 的数量算到 B 头上，金额会出错。
        List<String> fields = new ArrayList<>(productIds.size());
        for (Long pid : productIds) {
            fields.add(String.valueOf(pid));
        }

        // ★ 这里用 redisTemplate.<String, String>opsForHash() 显式指定泛型，
        //   是为了拿到 HashOperations<String, String, String>。
        //
        //   如果只写 opsForHash()，StringRedisTemplate 会把它推断成
        //   HashOperations<String, Object, Object> —— 那么 multiGet 要的是
        //   Collection<Object>，传 List<String> 就编译不过。
        //   显式写出类型参数，field 和 value 都是 String，
        //   签名和我们的数据形状对得上，还省掉了转型。
        HashOperations<String, String, String> hash = redisTemplate.opsForHash();
        List<String> values = hash.multiGet(key, fields);

        Map<Long, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            Integer qty = parseIntOrNull(values.get(i));
            // 值为 null（不在车里）或者数量不合法（数据脏了）都跳过 ——
            // 调用方通过"返回的 key 少了谁"来发现这种情况
            if (qty != null && qty > 0) {
                result.put(productIds.get(i), qty);
            }
        }
        return result;
    }

    @Override
    public void removeItems(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            // 空集合不报错。调用方（下单）会在"没结算任何商品"时
            // 更早地失败，走不到这里；但这个判断留着，
            // 是因为删空集合本身就不是错误操作。
            return;
        }

        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // HDEL 支持一次删多个 field：HDEL mall:cart:1 "5" "7"
        // 返回真正删掉的数量 —— 如果某个 field 本来就不在，不计入。
        // 所以它天然是幂等的，不需要额外判断。
        Object[] fields = productIds.stream().map(String::valueOf).toArray();

        Long removed = redisTemplate.opsForHash().delete(key, fields);
        log.info("下单后清理购物车: memberId={}, 请求清理={} 件, 实际删除={} 件",
                memberId, productIds.size(), removed);

        // ★ 不调 touch() 刷新过期时间，是刻意的：
        //   下单是"购物车使命完成"的时刻，不是"用户还在逛"的时刻。
        //   刷新 TTL 会让一个已经清空的购物车又多活 30 天
        //   （虽然空 Hash 会被 Redis 自动删掉 key，但逻辑上不该依赖这个）。
        //   购物车的过期时间衡量的是"用户还活跃吗"，
        //   而"刚下完单"并不代表他还会回来继续加购。
    }

    // ==========================================================================
    // 私有辅助方法
    // ==========================================================================

    /**
     * 取当前登录会员的 id。
     *
     * <p>{@code UserContext.require()} 在没登录时会直接抛 401，
     * 所以这里不用判空。不过实际上根本走不到那一步 ——
     * {@code MemberAuthInterceptor} 已经保证进到 Service 的请求都是登录的。
     *
     * <p><b>那为什么还要 require 而不是 get？</b>
     * 因为这是<b>第二道防线</b>。将来如果这个 Service 被别的地方调用
     * （比如管理端要代客清空购物车、定时任务要清理僵尸购物车），
     * 那条调用链可能不经过拦截器。用 require 的话，
     * 忘了传身份就会立刻炸出明确的错误，而不是拿着 null 去拼出一个
     * {@code mall:cart:null} 的 key，把所有人的购物车搞混在一起。
     *
     * <p><b>拼 key 用的变量，值绝对不能让它是 null。</b>
     * 这类 bug 一旦发生，后果是所有用户的数据互相覆盖，而且很难查。
     */
    private Long currentMemberId() {
        LoginUser user = UserContext.require();
        return user.id();
    }

    /**
     * 拼购物车的 Redis key。
     *
     * <p>{@code mall:cart:1}。写成方法而不是到处字符串拼接，
     * 是为了保证「拼 key 的规则只有一处」—— 哪天要改成
     * {@code mall:cart:v2:1}，改这一个地方就够了。
     */
    private String cartKey(Long memberId) {
        return KEY_PREFIX + memberId;
    }

    /**
     * 查出一个「现在可以买」的商品，查不到就抛异常。
     *
     * <p>用的是 {@code selectShopById}，它内部带了 {@code status = 1} ——
     * 所以下架的商品在这里就会被拦下，加不进购物车。
     */
    private ShopProductDetailVO requireAvailableProduct(Long productId) {
        if (productId == null || productId < 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
        }
        // 这里复用商品详情的方法，返回的是 ShopProductDetailVO（比列表 VO 多一个
        // description）。购物车其实用不到 description，多查一列有点浪费。
        //
        // ★ 知道这个浪费、并且接受它，比不知道要好。
        //   为什么不干脆再写一个只查必要列的 SQL？因为「复用已有查询」
        //   带来的好处（字段增加了自动就有、逻辑只有一处）大于
        //   「多查一列 description」的代价（几百字节的网络传输）。
        //   如果哪天商品描述变成几 MB 的大文本，这个判断就要翻过来。
        //   **优化要针对真实的瓶颈，而不是想象中的浪费。**
        ShopProductDetailVO product = productMapper.selectShopById(productId);
        if (product == null) {
            // 和商品详情接口一样，【不区分】「不存在」和「已下架」——
            // 区分了就等于告诉外界「这个 id 是存在的」
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
        }
        return product;
    }

    /**
     * 刷新购物车的过期时间（滑动过期）。
     *
     * <p>{@code expire} 是「从现在起再活 30 天」，不是「在原有基础上加 30 天」——
     * Redis 没有「续期」这种相对操作，每次都是重设一个绝对时间。
     * 效果上等价于「只要 30 天内动过，就不会过期」。
     *
     * <p><b>★ 为什么放在写操作的最后，而不是每次读也刷新？</b>
     * 因为读操作刷新过期时间会产生一个反直觉的后果：
     * 一个用户只要打开购物车页面（哪怕什么都没做），
     * 购物车就会续命 30 天。这样「30 天没用过」这个判断就失真了。
     * <b>过期时间应该衡量「用户的行为了」，而打开页面不算行为。</b>
     */
    private void touch(String key) {
        redisTemplate.expire(key, CART_TTL);
    }

    /**
     * 把刚才 {@code HINCRBY} 加上去的数量扣回来。
     *
     * <p>用于「加完之后发现超限、且一件都不能买」时回滚。
     * 用 {@code increment(key, field, -delta)} 而不是 {@code put(key, field, 0)} ——
     * 因为理论上可能有别的请求在这中间也加了购，
     * 直接 put 0 会把别人加的数量一起抹掉。
     *
     * <p><b>★ 这里要说清楚一件事：这不是「事务」，它不完美。</b>
     * 真正的回滚需要 Redis 的 MULTI/EXEC 或者 Lua 脚本把
     * 「读-判断-写」变成一个原子操作。这里选择不做的理由是：
     * 出错的那条分支本身就很罕见（库存突然变成 0），
     * 而即使回滚得不完美，最坏结果也只是车里多了几件买不到的东西 ——
     * 下单时还会再校验一次库存，不会真的超卖。
     *
     * <p><b>知道一个方案不完美、并说清楚「为什么接受它」，比假装它完美重要。</b>
     * 技术在能说清边界的时候才叫掌握。
     */
    private void rollbackIncrement(String key, String field, Integer delta) {
        redisTemplate.opsForHash().increment(key, field, -delta);
        log.warn("加购失败，已回滚数量: key={}, field={}, 回滚量={}", key, field, delta);
    }

    /** 安全地把 Object 转 Long，转不了返回 null 而不是抛异常 */
    private Long parseLongOrNull(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 安全地把 Object 转 Integer */
    private Integer parseIntOrNull(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
