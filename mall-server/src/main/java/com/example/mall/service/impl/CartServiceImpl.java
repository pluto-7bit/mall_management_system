package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.BusinessRules;
import com.example.mall.common.LoginUser;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.dto.CartAddDTO;
import com.example.mall.dto.CartQuantityDTO;
import com.example.mall.service.CartService;
import com.example.mall.service.ShopSkuService;
import com.example.mall.vo.CartItemVO;
import com.example.mall.vo.CartVO;
import com.example.mall.vo.ShopSkuVO;
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
 *   field  "204"                ← skuId（字符串）★ 里程碑 15 阶段 4 前是 productId
 *   value  "3"                  ← 数量
 *
 *   用 redis-cli 看：
 *     HGETALL mall:cart:1
 *     1) "204"  2) "3"
 *     3) "311"  4) "1"          ← 同一件商品的两个规格，两行
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
    private final ShopSkuService shopSkuService;

    /**
     * key 前缀。提成常量，避免在六七个方法里各写一遍字符串。
     *
     * <h3>⚠️⚠️ 里程碑 15 阶段 4：换 field 的那一天，这里的老数据【必须清掉】</h3>
     *
     * <p>field 从 productId 换成 skuId 是一次<b>语义变化</b>，而不是「改了参数名」。
     * 老数据不会报错，它会<b>换个意思继续存在</b>：
     * <pre>
     *   Redis 里还躺着 field = "5"（老数据，意思是「商品 5」）
     *   新的代码读它，理解成「SKU 5」
     *   → 购物车里出现一件用户从来没加过的商品
     * </pre>
     * 而这两张表的 id 都从 1 开始自增，<b>撞车几乎是必然的</b>。
     *
     * <p><b>★ 为什么不写个脚本把老数据转换过来？</b>
     * 因为那需要一张「productId → 该商品的默认 SKU id」的映射，
     * 而对<b>已被删除的商品</b>和<b>有多规格的商品</b>这个映射是错的 ——
     * 脚本会静默地改错、或者默默丢掉那些行，
     * 而这两种结果都不会有任何报错。
     *
     * <p>所以处理方式是：<b>一次性的 {@code KEYS mall:cart:*} 看一眼条数 → {@code DEL}</b>。
     * 代价是所有用户丢一次购物车；收益是<b>没有一行数据带着错误的意思活着</b>。
     * 购物车本来就是「临时意图」（见 {@code CartService} 的类注释），
     * 丢了重新加就是 —— 这也正是它敢放 Redis 的同一个理由。
     *
     * <p>⚠️ 日常运营里 {@code KEYS} 是禁用命令（会阻塞整个 Redis），
     * 这里是<b>一次性的人工操作</b>，不是代码。别把这段抄进任何定时任务。
     */
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
     * 单个规格在购物车里的数量上限。
     *
     * <p><b>★ 里程碑 15 阶段 4：这个私有副本【已经删掉了】，
     * 现在用的是 {@link BusinessRules#MAX_QUANTITY_PER_ITEM}。</b>
     *
     * <p>它本来在这里的理由写得很足（「只定义一次」「DTO 上那两个 @Max 让
     * 这段变成死代码」），也确实把 DTO 上那两份收掉了 ——
     * <b>但它自己没走</b>。于是从里程碑 7 到 15，
     * 「最多买几件」在 Service 层一直是两份：这里一份、
     * {@code BusinessRules} 里一份，两个值都是 99。
     *
     * <p><b>★ 两个值恰好相等，所以没有任何测试能发现它。</b>
     * 这正是「同一规则两处定义」最阴的形态 ——
     * 值不等的话早就有人撞上去了，值相等的话它可以一直错下去，
     * 而两份注释都写着「只有一处」。
     *
     * <p>为什么会变成两份？因为里程碑 8 的订单模块也要用这个数字，
     * 而它不该去引用购物车的私有常量（方向反了），
     * 于是那个数字被提到了 {@code BusinessRules} ——
     * <b>提上去的那一刻，这里就应该被删掉，但没有。</b>
     * 「提取一个共用的常量」这个动作里，最容易被忘掉的一步是<b>删掉原来的那份</b>。
     *
     * <p>所以现在这里没有常量了，直接用 {@code BusinessRules} 里那一个。
     * 「上限只有一个出处」这句话，到此才真的成立。
     */
    // ★ 这里本来有一个 private static final int MAX_QUANTITY_PER_ITEM = 99;（已删除，见上）

    /**
     * 算出「这个规格这次最多能买多少件」= min(业务上限, 该规格库存)。
     *
     * <p><b>为什么这条规则必须留在 Service 而不是 DTO？</b>三个理由，缺一不可：
     * <ul>
     *   <li>DTO 只拦得住「单次请求传了 500」，拦不住「传两次 60」——
     *       上限要基于<b>累加后</b>的总数判断</li>
     *   <li>它还得和<b>库存</b>取最小值，而库存要查库才知道，DTO 里根本拿不到</li>
     *   <li>绕过页面直接调接口的请求，走的也是这里</li>
     * </ul>
     *
     * <p><b>DTO 校验是给用户看的友好提示，Service 里的校验才是真的规则。</b>
     * 两者都要有，但管的不是同一件事。
     *
     * <p>★ 里程碑 15 阶段 4 顺手把「取 min」这一步也收进了这个方法：
     * 加购和改数量原来各写了一遍 {@code Math.min(99, stock)}，
     * 两处都对着同一份库存语义 —— 今天它们一致，但要改的时候就未必了。
     */
    private int quantityLimit(int skuStock) {
        return Math.min(BusinessRules.MAX_QUANTITY_PER_ITEM, skuStock);
    }

    // ==========================================================================
    // 写操作
    // ==========================================================================

    @Override
    public void add(CartAddDTO dto) {
        Long memberId = currentMemberId();
        Long skuId = dto.getSkuId();

        // ① 先确认这个规格真的能买。
        //    这一步查的是 MySQL —— getAvailable 内部会查 SKU 行、
        //    再用商品那条带 status = 1 的查询确认它所属商品在架上，
        //    所以「往购物车里塞一个不存在的 skuId」是做不到的。
        //
        //    ★ 这个判断在阶段 4 之前叫 requireAvailableProduct，
        //      是【这个类】的私有方法。搬到 ShopSkuServiceImpl 是因为
        //      它现在有三个调用者：加购、改数量、以及 GET /shop/skus/{id}。
        //      留在这里的话，另外两个地方就得各抄一份 ——
        //      「什么算可买」这条规则会变成三份。
        ShopSkuVO sku = shopSkuService.getAvailable(skuId);

        String key = cartKey(memberId);
        String field = String.valueOf(skuId);

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
        int limit = quantityLimit(sku.getStock());
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
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "该规格已售罄");
            }
            redisTemplate.opsForHash().put(key, field, String.valueOf(limit));
            touch(key);
            log.info("加购数量超过上限，已压到上限: memberId={}, skuId={}, 请求后={}, 上限={}",
                    memberId, skuId, newQty, limit);
            throw new BusinessException(ResultCode.CART_QUANTITY_LIMIT,
                    "最多只能买 " + limit + " 件，已为你调整");
        }

        touch(key);
        log.info("加入购物车: memberId={}, skuId={}, +{}, 现在={}", memberId, skuId, dto.getQuantity(), newQty);
    }

    @Override
    public void updateQuantity(Long skuId, CartQuantityDTO dto) {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);
        String field = String.valueOf(skuId);

        // ★ 先确认这个规格【已经在车里】。
        //
        //   如果不检查，PUT 一个从没加过的规格就会凭空创建一条记录 ——
        //   那 PUT 就默默变成了 POST，"改了数量"和"新增商品"混在一起，
        //   接口就没法只靠 URL 和方法表达意图了。
        //
        //   ★ 注意这个检查和后面的写操作之间也有竞态（可能刚检查完
        //     就被另一个标签页删掉了）。但后果仅仅是"删了又出现"，
        //     用户重新删一次即可，不值得为它加锁。
        //     判断一个竞态要不要处理，标准是【后果有多严重】，不是【存不存在】。
        Boolean exists = redisTemplate.opsForHash().hasKey(key, field);
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "购物车里没有这个规格");
        }

        // ★ 注意改数量也要重新确认「可买」，不能只看 Redis。
        //   这一行的价格和库存都可能已经变了，而检查的依据必须是
        //   「数据库里现在是多少」——Redis 里只有数量，没有真相。
        ShopSkuVO sku = shopSkuService.getAvailable(skuId);

        int limit = quantityLimit(sku.getStock());
        if (limit <= 0) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "该规格已售罄");
        }
        if (dto.getQuantity() > limit) {
            throw new BusinessException(ResultCode.CART_QUANTITY_LIMIT, "最多只能买 " + limit + " 件");
        }

        // 这个场景可以用 HSET（直接设值）而不是 HINCRBY ——
        // 因为 PUT 的语义就是「改成某个确定的值」，
        // 两次同样的请求结果相同，本身是幂等的
        redisTemplate.opsForHash().put(key, field, String.valueOf(dto.getQuantity()));
        touch(key);
        log.info("修改购物车数量: memberId={}, skuId={}, -> {}", memberId, skuId, dto.getQuantity());
    }

    @Override
    public void remove(Long skuId) {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // ★ 返回值是「实际删掉了几条」，0 表示本来就不在车里。
        //   我们【不】把它当错误 —— 幂等语义，见 CartService.remove 的注释
        Long removed = redisTemplate.opsForHash().delete(key, String.valueOf(skuId));
        touch(key);
        log.info("从购物车移除: memberId={}, skuId={}, 实际删除={}", memberId, skuId, removed);
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

        // 把 field（skuId）转成 Long。
        // ★ 这里用了 LinkedHashMap 保留 Redis 返回的顺序 ——
        //   注意这只是「尽量」，Redis 不保证 Hash 的顺序稳定
        //   （元素少时用 listpack 编码恰好有序，元素多了会变成 hashtable 就无序了）。
        //   所以下面会显式排序，不依赖这个顺序。
        Map<Long, Integer> cartMap = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            Long sid = parseLongOrNull(e.getKey());
            Integer qty = parseIntOrNull(e.getValue());
            // Redis 里理论上不会出现脏数据（只有本类会写它），
            // 但「理论上不会」和「一定不会」是两回事。
            // 读到无法解析的值时跳过并记一条 warn，别让整个购物车打不开
            if (sid == null || qty == null || qty <= 0) {
                log.warn("购物车里有无法解析的记录，已跳过: memberId={}, key={}, field={}, value={}",
                        memberId, key, e.getKey(), e.getValue());
                continue;
            }
            cartMap.put(sid, qty);
        }

        if (cartMap.isEmpty()) {
            vo.setItems(new ArrayList<>());
            vo.setTotalQuantity(0);
            vo.setTotalAmount(BigDecimal.ZERO);
            return vo;
        }

        // ★ 两条 SQL 把所有 SKU 连同它们的商品、规格定义一次捞出来，不是循环查。
        //
        //   阶段 4 之前这里是一句 productMapper.selectShopByIds(...)，
        //   现在换成 shopSkuService.listAvailable(...) —— 它内部就是
        //   原来那条 SQL 加一次「按 productId 查商品」和一次「查 spec_schema」，
        //   仍然是常数条（3 条），和车里有多少件无关。
        //
        //   ★ 它【只返回可买的】，不可买的那些不会出现在结果里 ——
        //     所以下面用「在 cartMap 里但不在 skuMap 里」当失效判据。
        //     这个「差集 = 失效」的设计是阶段 3 就定下来的，见 ShopSkuVO 的注释。
        List<ShopSkuVO> skus = shopSkuService.listAvailable(new ArrayList<>(cartMap.keySet()));
        Map<Long, ShopSkuVO> skuMap = new HashMap<>();
        for (ShopSkuVO s : skus) {
            skuMap.put(s.getId(), s);
        }

        List<CartItemVO> items = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Map.Entry<Long, Integer> e : cartMap.entrySet()) {
            Long sid = e.getKey();
            Integer qty = e.getValue();

            CartItemVO item = new CartItemVO();
            item.setSkuId(sid);
            item.setQuantity(qty);

            ShopSkuVO s = skuMap.get(sid);
            if (s == null) {
                // ★★ 这就是「购物车里有，但查不出可买的 SKU」的情况 ——
                //    两种原因，而这里【刻意不区分】：
                //      · 商品下架了或者被删了（product.status ≠ 1）
                //      · 这个 SKU 行本身被删了（管理员改了规格定义）
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
                //
                //    ⚠️ 这一行上【只有 skuId 和 quantity】有值，
                //       商品 id、名称、价格、规格文本全是 null。
                //       理由（不为它另开一条绕过 status = 1 的查询）见
                //       CartItemVO.specText 的注释。
                item.setAvailable(false);
                item.setUnavailableReason("商品已下架");
                items.add(item);
                continue;
            }

            // ★ 下面这一段的每个字段都换了个来源：
            //   阶段 4 之前从 ShopProductVO 取（商品级），现在从 ShopSkuVO 取（规格级）。
            //   价格和库存的唯一真源是 product_sku，商品级那两个数已经不存在了。
            item.setProductId(s.getProductId());
            item.setSpecText(s.getSpecText());
            item.setName(s.getProductName());
            item.setPrice(s.getPrice());
            item.setCover(s.getCover());
            item.setCategoryName(s.getCategoryName());
            item.setStock(s.getStock());

            if (s.getStock() <= 0) {
                // 规格还在，但这一档卖光了。
                // 注意【保留 price】：用户有权知道自己当初想买的东西多少钱
                //
                // ★ 文案从「已售罄」改成「该规格已售罄」不是措辞讲究：
                //   同一件商品可能别的规格还有货，说「已售罄」会让用户
                //   以为整件商品都买不了了，直接离开页面。
                item.setAvailable(false);
                item.setUnavailableReason("该规格已售罄");
                items.add(item);
                continue;
            }

            if (qty > s.getStock()) {
                // 规格还在、还有货，但【不够用户加购的数量】。
                // 这种情况很常见：用户加了 5 件，然后被别人买走了 3 件。
                //
                // 这里刻意【不自动改小数量】—— 那是替用户做决定。
                // 只标记出来让他自己改，改动权在他手上。
                item.setAvailable(false);
                item.setUnavailableReason("库存只剩 " + s.getStock() + " 件");
                // 小计照算，方便前端展示「原价 × 数量」
                item.setSubtotal(s.getPrice().multiply(BigDecimal.valueOf(qty)));
                items.add(item);
                continue;
            }

            item.setAvailable(true);
            // ★ 金额计算一律用 BigDecimal。
            //   用 double 的话 0.1 + 0.2 != 0.3，
            //   购物车里 3 件 0.1 元的商品会算出 0.30000000000000004 元。
            //   钱的计算里出现这种数字是灾难性的 —— 它会一路传到订单、发票、对账
            item.setSubtotal(s.getPrice().multiply(BigDecimal.valueOf(qty)));

            // ★ 只有【能买】的商品才计入合计。
            //   已经售罄/下架的东西不该出现在「你要付多少钱」里
            totalQuantity += qty;
            totalAmount = totalAmount.add(item.getSubtotal());

            items.add(item);
        }

        // ★ 显式排序：失效的排后面，然后按 skuId 倒序。
        //
        //   不能依赖 Redis Hash 的返回顺序 —— 那取决于它的内部编码，
        //   元素少的时候恰好有序，多了就变了。依赖它就是依赖一个
        //   「现在碰巧成立」的假设。
        //
        //   「失效的沉到底部」是电商购物车的通行做法：
        //   用户打开购物车是为了结算，能买的必须一眼看到
        //
        //   ★ 排序键从 productId 换成了 skuId，这一步是【必须的】而不是顺手：
        //     失效行的 productId 是 null（商品查不到才失效的），
        //     拿它排序会直接 NPE；而 skuId 来自 Redis，永远有值。
        //     ⚠️ 另外原来这里写着「新加的在前」——那是个不准确的说法：
        //     id 是商品/SKU 被【创建】的顺序，和用户什么时候加进车里无关。
        //     真实的顺序 Redis 里没有存（Hash 不保序），所以这里只是
        //     「一个稳定的顺序」，让每次刷新长得一样。
        //     附带好处：同一件商品的几个规格 id 是连着生成的，会排在一起。
        items.sort(Comparator
                .comparing(CartItemVO::getAvailable, Comparator.reverseOrder())
                .thenComparing(CartItemVO::getSkuId, Comparator.reverseOrder()));

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
    public Map<Long, Integer> readQuantities(List<Long> skuIds) {
        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // ★ 用 HMGET 而不是 HGETALL + 自己筛。
        //
        //   购物车里可能有 20 种规格，而这次只结算 2 种。
        //   HGETALL 会把 20 种全部拉回应用服务器，再丢掉 18 种 ——
        //   网络传了 10 倍的数据，只为了扔掉 90%。
        //
        //   但 HMGET 需要把 field 转成 String 数组：
        //     HMGET mall:cart:1 "204" "311"
        //   返回按顺序对应的值列表，不存在的 field 返回 null。
        //
        //   ★ 这里有个容易踩的坑：HMGET 返回的顺序【和请求的顺序一一对应】，
        //     所以可以按下标把 field 和 value 配起来。
        //     但一定要用【同一个数组】去遍历，不能一边按原 list 遍历
        //     一边按返回的 list 取下标 —— 两边顺序一旦不一致就错位了，
        //     而且错位后是把 A 的数量算到 B 头上，金额会出错。
        List<String> fields = new ArrayList<>(skuIds.size());
        for (Long sid : skuIds) {
            fields.add(String.valueOf(sid));
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
                result.put(skuIds.get(i), qty);
            }
        }
        return result;
    }

    @Override
    public void removeItems(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            // 空集合不报错。调用方（下单）会在"没结算任何商品"时
            // 更早地失败，走不到这里；但这个判断留着，
            // 是因为删空集合本身就不是错误操作。
            return;
        }

        Long memberId = currentMemberId();
        String key = cartKey(memberId);

        // HDEL 支持一次删多个 field：HDEL mall:cart:1 "204" "311"
        // 返回真正删掉的数量 —— 如果某个 field 本来就不在，不计入。
        // 所以它天然是幂等的，不需要额外判断。
        //
        // ★★ 里程碑 15 阶段 4 的【头号静默风险点】就在这一行。
        //    它的参数从 productIds 改成了 skuIds，而错误是不会报出来的：
        //    传一个 productId 进来，HDEL 会去删那个【数字】对应的 field ——
        //    如果恰好有别的 SKU 用着这个 id，删掉的就是别人的购物车行。
        //    而且调用它的是订单事务提交后的回调，那里的异常只打日志
        //    （见 OrderServiceImpl.registerCartCleanupAfterCommit），
        //    所以症状是「用户的车里少了一件东西」，没有任何报错。
        Object[] fields = skuIds.stream().map(String::valueOf).toArray();

        Long removed = redisTemplate.opsForHash().delete(key, fields);
        log.info("下单后清理购物车: memberId={}, 请求清理={} 件, 实际删除={} 件",
                memberId, skuIds.size(), removed);

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

    // ★ 里程碑 15 阶段 4：这里原本有一个 requireAvailableProduct(Long productId)，
    //   已经【整个方法删除】了 —— 它搬去了 ShopSkuServiceImpl.getAvailable(Long skuId)。
    //
    //   搬家的理由不是「这个方法现在更长一点」，而是【它从 1 个调用者变成了 3 个】：
    //     · CartServiceImpl.add()          → 加购时确认能买
    //     · CartServiceImpl.updateQuantity() → 改数量时确认能买
    //     · ShopSkuController              → GET /api/shop/skus/{id}
    //   留在购物车里，另外两个地方就得各抄一份，而「什么算可买」
    //   就会有三份实现 —— 三份里只要有一份忘了查 status，
    //   下架商品就能从那条路被买走，而且不会有任何报错。
    //
    //   ⚠️ 它原来还有一点值得留着的话：为什么【不区分】「不存在」和「已下架」。
    //     那段理由现在写在 ShopSkuServiceImpl.notFound 上，一个字没丢。

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
