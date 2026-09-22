package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.PageResult;
import com.example.mall.common.ResultCode;
import com.example.mall.dto.ProductQueryDTO;
import com.example.mall.dto.ProductSaveDTO;
import com.example.mall.entity.Category;
import com.example.mall.entity.Product;
import com.example.mall.entity.ProductImage;
import com.example.mall.mapper.CategoryMapper;
import com.example.mall.mapper.ProductImageMapper;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.mapper.ProductReviewImageMapper;
import com.example.mall.mapper.ProductReviewMapper;
import com.example.mall.service.FileStorageService;
import com.example.mall.service.ProductService;
import com.example.mall.vo.AdminProductDetailVO;
import com.example.mall.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品业务实现。
 *
 * <p><b>{@code @Service} 的作用</b>是把这类交给 Spring 管理（注册为 Bean），
 * 这样 Controller 里才能通过构造器注入拿到它。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    /**
     * {@code @RequiredArgsConstructor} 是 Lombok 注解，
     * 它会为所有 {@code final} 字段生成一个构造器。
     *
     * <p>配合 Spring「单构造器自动注入」的规则，
     * 等价于手写下面这段代码，但省掉了样板：
     * <pre>
     *   public ProductServiceImpl(ProductMapper productMapper, CategoryMapper categoryMapper) {
     *       this.productMapper = productMapper;
     *       this.categoryMapper = categoryMapper;
     *   }
     * </pre>
     *
     * <p>声明成 final 还有一个好处：保证依赖注入后不会被误改。
     */
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final ProductImageMapper productImageMapper;

    /**
     * 只用来删「属于这个商品的那些评价」和它们的晒图（★ 里程碑 12 起）。
     *
     * <p>★ 为什么依赖的是 <b>Mapper</b> 而不是 {@code ProductReviewService}：
     * 因为「删商品」这件事要在一个事务里连着删四张表，
     * 中间不能有别的业务规则的插口。调 Service 意味着调它的
     * {@code delete}（那是「管理员删一条评价」的语义，会判「评价不存在」并抛错）——
     * 而我们这里要的是「把这个商品下的评价全删掉，一条都没有也是正常的」。
     * <b>两个语义，两个方法，不要用一个去凑另一个。</b>
     *
     * <p>这个先例是里程碑 11 立的：删 {@code product_image} 时直接用
     * {@code ProductImageMapper}，没走 Service。
     */
    private final ProductReviewMapper productReviewMapper;
    private final ProductReviewImageMapper productReviewImageMapper;

    /**
     * 只用来校验图集里的地址（★ 里程碑 12 起）。
     *
     * <p>这个依赖是里程碑 12 才加的，加它的原因值得记一句：
     * 「地址必须是本服务上传的」这条规则原来在本类里有一个私有方法
     * {@code checkImageUrls}。<b>它被【搬走了】，不是被【复制走了】</b> ——
     * 评价晒图也要守同一条边界，而一条安全边界出现两处定义，
     * 一定会有一天分岔。所以规则回到了生成这些地址的那个服务里
     * （{@code FileStorageService.requireUploadedImages}），
     * 这里只留下「调用」。
     *
     * <p><b>★ 一次搬家比一次复制贵，但它只贵一次；复制贵很多次。</b>
     */
    private final FileStorageService fileStorageService;

    // =======================================================================
    //  查询
    // =======================================================================

    /**
     * 分页查询。
     *
     * <p>这个方法<b>不加事务</b>，因为只读查询不需要。
     * 加 {@code @Transactional(readOnly = true)} 理论上能让数据库做一点优化，
     * 但收益很小，而且会增加心智负担。查询方法保持干净即可。
     */
    @Override
    public PageResult<ProductVO> page(ProductQueryDTO query) {
        // 先规范化分页参数，防止前端传 pageSize=100000 把数据库拖垮。
        // 这类「不信任前端」的防御必须做在后端 —— 前端的校验只是给用户看的，
        // 用户完全可以绕过页面直接调接口
        query.normalize();

        // 先查总数。如果一条都没有，就不必再发第二条 SQL 去查列表了
        long total = productMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<ProductVO> list = productMapper.selectPage(query);

        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    /**
     * 管理端商品详情（★ 里程碑 11 起带图集）。
     *
     * <h4>★ 图集为什么是「单独查一次」，而不是塞进 selectById 那条 SQL</h4>
     *
     * <p>{@code ProductMapper.selectShopById}（用户端详情那条）被购物车的
     * 「加入购物车 / 改数量」复用了 —— {@code CartServiceImpl.requireAvailableProduct}
     * 调它，而那边<b>只需要 {@code stock}</b>。
     *
     * <p>如果靠 MyBatis 的 {@code <collection>} 把图集装进那条 SQL，
     * 就等于<b>每次加购物车都多查一次 product_image</b> ——
     * 一个和「加购物车」毫无关系、而且频率高得多的路径，
     * 因为另一个场景的需求而变慢。这种损伤在代码里看不见
     * （SQL 是共用的一句），只有压测或者某天突然发现购物车慢才知道。
     *
     * <p>而且 {@code resultType} 本来就装不下 {@code List<String>}：
     * 要加就得把 statement 从 {@code resultType} 改成 {@code resultMap}，
     * 改动面还会扩大到那条共用的查询本身。
     *
     * <p>★ 所以这里的做法是：<b>查完商品，再单独查一次图集，set 进去。</b>
     * 多一次数据库往返（详情页本来就不频繁），换共用的热路径完全不受影响。
     *
     * <p>⚠️ <b>注意这里仍然只有【一条】结果集到 VO 的转换路径</b>：
     * {@code productMapper.selectById} 的 {@code resultType} 从
     * {@code ProductVO} 改成了 {@code AdminProductDetailVO}，
     * 由 MyBatis 按 setter 直接填充（{@code resultType} 天然支持子类，
     * 因为子类继承了父类所有的 setter）。
     * 不是「查出 ProductVO 再手工 new 一个子类拷字段」——
     * 那会造出第二条转换路径，而 {@code OrderServiceImpl.toVO} 的注释
     * 专门警告过：<b>第二条转换路径就是将来加字段时漏掉一处的来源</b>。
     */
    @Override
    public AdminProductDetailVO getById(Long id) {
        AdminProductDetailVO product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        // 商品没有图时这里返回空列表（不是 null）——
        // 前端因此可以直接写 product.images.length，见 AdminProductDetailVO 的注释
        product.setImages(productImageMapper.selectUrlsByProductId(id));
        return product;
    }

    // =======================================================================
    //  写操作
    // =======================================================================

    /**
     * 新增商品。
     *
     * <p><b>关于 {@code @Transactional}：</b>
     * 里程碑 11 之前这个方法内部只有一条 INSERT，本身就是一个原子操作，
     * 加事务在功能上<b>没有实际作用</b>（当时的注释就是这么写的）。
     *
     * <p>★ <b>里程碑 11 起那句话不再成立了</b>：现在这里有两条写 ——
     * {@code INSERT product} 和 {@code INSERT product_image}。
     * 「商品建出来了但图集没进去」是必须避免的不一致，
     * 所以事务从「防御性习惯」变成了<b>真正承重</b>的东西。
     * 判据没变（多步写才需要事务），变的是这个方法本身。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductSaveDTO dto) {
        // 业务校验：分类必须真实存在且启用。
        // 前端下拉框里的选项虽然是从后端查的，但接口是可以被直接调用的，
        // 传一个不存在的 categoryId 完全可能，所以后端必须自己校验一遍
        checkCategoryAvailable(dto.getCategoryId());

        Product product = new Product();
        product.setCategoryId(dto.getCategoryId());
        product.setName(dto.getName());
        product.setPrice(dto.getPrice());
        product.setStock(dto.getStock());
        product.setCover(dto.getCover());
        product.setDescription(dto.getDescription());
        // 前端不传状态时默认上架。
        // 这里体现 DTO 和 Entity 分离的另一个好处：
        // 默认值的填充逻辑放在 Service，而不是散落在各处
        product.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        productMapper.insert(product);

        // 靠 XML 里的 useGeneratedKeys 配置，id 已经被回填进 product 对象了，
        // 不需要再查一次数据库
        //
        // ★ 图集的插入必须在 insert 之后 —— 图集行需要一个 productId，
        //   而商品没有 id 之前，这个值根本不存在。
        //   （这正是「上传接口不能挂在 /api/admin/products/{id} 下」的原因：
        //     新增商品时先传图、后提交表单，传图那一刻商品还没有 id。）
        replaceImages(product.getId(), dto.getImages());

        log.info("新增商品成功, id={}, name={}, 图集 {} 张",
                product.getId(), product.getName(),
                dto.getImages() == null ? 0 : dto.getImages().size());
        return product.getId();
    }

    /**
     * 修改商品。
     *
     * <p>{@code rollbackFor = Exception.class} 的作用：
     * Spring 默认只在遇到<b>运行时异常</b>（RuntimeException）时回滚，
     * 遇到受检异常（Exception 的子类但不是 RuntimeException）不回滚。
     * 显式写 {@code rollbackFor = Exception.class} 就是告诉 Spring
     * 「不管什么异常都回滚」，避免哪天有人在事务方法里抛了受检异常，
     * 导致事务悄悄不回滚这种很难查的 bug。
     *
     * <h4>★ 里程碑 11：事务在这里从「防御性习惯」变成了「真正必需」</h4>
     *
     * <p>上面那段注释在里程碑 11 <b>之前</b>配着一句「这个方法内部只有一条
     * INSERT，加事务在功能上没有实际作用」—— 那句话当时是对的，
     * 现在<b>已经变成假话</b>，所以删掉了。现在这里有两条写：
     * {@code UPDATE product}，加上（当 {@code images} 非 null 时）
     * {@code DELETE product_image} + 可能还有一次 {@code INSERT product_image}。
     *
     * <p>⚠️ 尤其危险的是「清空图集」那一步：{@code images: []} 会执行
     * DELETE 而<b>不</b>执行 INSERT。如果这时事务没有兜住（比如 DELETE 成功、
     * 后面的日志或别的语句失败），用户的图集就被<b>永久抹掉了</b> ——
     * 旧数据已经删除，磁盘上的图还在，但「哪张图属于哪个商品」这个信息没了。
     * 事务保证这两步一起成败。
     *
     * <h4>★ 图集的更新语义：整个替换，不是「改某一行」</h4>
     *
     * <p>{@code dto.images} 是一个<b>有序数组</b>，服务端按它的下标写
     * {@code sort_no}（第 0 个元素 → sort_no 0，以此类推）。
     * 所以「上移一张图」在前端只是换了数组里两个元素的位置，
     * 提交上来之后服务端照做即可 —— <b>不需要任何单独的排序接口</b>。
     *
     * <p>代价是每次保存都会 {@code delete + insert}，
     * 于是 {@code product_image.id} 每次都变。<b>id 没有读者</b>
     * （前端拿的是 {@code url}），所以无害 —— 这是一个刻意的取舍，
     * 换来的是「sort_no 的值永远和前端看到的顺序一致」。
     *
     * <p>⚠️ 并发编辑下这是「后提交的覆盖整个图集」，和其他字段的语义一致
     * （两个人同时改商品名，也是后提交的赢）。本项目不做乐观锁，
     * 所以这里不需要为图集单独做。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProductSaveDTO dto) {
        // 先确认商品存在，顺便拿到它的信息。
        // 这里的判断顺序有讲究：先查存在性，再校验关联的分类。
        // 反过来的话，如果 id 不存在，用户会先收到「分类不存在」的误导性提示
        Product existing = productMapper.selectEntityById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }

        checkCategoryAvailable(dto.getCategoryId());

        // 构造一个只带 id 和待更新字段的对象。
        // 没赋值的字段保持 null，XML 里的 <if> 会让它们不参与 UPDATE
        Product product = new Product();
        product.setId(id);
        product.setCategoryId(dto.getCategoryId());
        product.setName(dto.getName());
        product.setPrice(dto.getPrice());
        product.setStock(dto.getStock());
        product.setCover(dto.getCover());
        product.setDescription(dto.getDescription());
        product.setStatus(dto.getStatus());

        productMapper.updateById(product);

        // ★ 图集是「有值才动」的 —— null 表示调用方不关心这件事。
        //   注意这里【不能】写成 replaceImages(id, dto.getImages()) 然后
        //   在方法里判空，因为那样就分不出「null（不改）」和「[]（清空）」了。
        //   判断必须在调用处，因为只有这里知道 dto 的语义。
        if (dto.getImages() != null) {
            replaceImages(id, dto.getImages());
        }

        log.info("修改商品成功, id={}, 图集{}", id,
                dto.getImages() == null ? "未改动" : "替换为 " + dto.getImages().size() + " 张");
    }

    /**
     * 删除商品。
     *
     * <p>现在做的是物理删除（真的从表里 DELETE）。
     * 等做完订单模块后建议改成<b>逻辑删除</b>：
     * 给表加 {@code is_deleted} 字段，删除时只是把它置为 1，
     * 所有查询条件里加上 {@code AND is_deleted = 0}。
     *
     * <p>为什么？因为订单明细引用了商品。商品被真删了，
     * 历史订单里的 product_id 就指向了一个不存在的记录，
     * 数据完整性被破坏。真实业务几乎不会物理删除商品。
     *
     * <h4>⚠️ ★ 里程碑 11：删商品之前必须先删图集行，顺序不能反</h4>
     *
     * <p>{@code product_image} 和 {@code order_item} 一样<b>没有外键</b>
     * （全库约定，见 {@code sql/mall.sql} 里那段说明）。没有外键，
     * 数据库就<b>不会</b>拦住「商品被删了但图集行还在」这件事 ——
     * 留下的是一批永远查不到、也永远删不掉的孤儿行。
     *
     * <p>所以顺序必须是：<b>先删图集行，再删商品行</b>。
     * 反过来（先删商品成功、再删图集失败）那些图集行就彻底失去了归属，
     * 而且没有任何东西会报错。
     *
     * <p>★ 那<b>先删图集、商品却没删成</b>不是也会出问题吗？会 ——
     * 但那个方向的损坏是「商品还在，图没了」，用户看得见、能重传。
     * 反方向是「数据静默地烂掉」。<b>两个方向的错误都要避免，
     * 而事务能同时兜住两边</b>，这也是 {@code @Transactional} 在这里
     * 从「习惯」变成「必需」的第二个理由。
     *
     * <h4>★★★ 里程碑 12：级联变成【四级】了，而且顺序一层都不能反</h4>
     *
     * <pre>
     *   晒图 (product_review_image)   ← 孙
     *   评价 (product_review)         ← 子
     *   图集 (product_image)          ← 子
     *   商品 (product)                ← 父
     * </pre>
     *
     * <p>多出来的这两级是<b>两级</b>、不是一级：评价挂在商品上，
     * 晒图又挂在评价上。所以「先删商品行」在这里的后果比里程碑 11 更糟 ——
     * 它会让<b>两</b>张表各留下一批孤儿行，而且从商品那一侧再也找不到它们了。
     *
     * <p><b>★ 为什么晒图必须在评价之前删？</b>
     * 因为晒图是用 {@code review_id} 关联的，而 {@code review_id}
     * 是评价的 id。评价行没了，晒图行里的那个 id 就变成一个
     * 「指向不存在的评价」的数字 —— 它不再属于任何东西，
     * 也没有任何查询会再找到它（连「某个商品的晒图」都查不出来，
     * 因为要先经过评价才知道商品）。
     *
     * <p>⚠️ <b>注意「晒图」和「图集」没有先后关系</b>：
     * 它们挂在不同的父行上（一个挂评价、一个挂商品），互不影响。
     * 下面代码里的顺序只是读起来顺（顺着四级链子从深到浅），
     * 不是一条必须遵守的约束。<b>真正的约束是这四条的两个位置：
     * 评价要在商品之前、晒图要在评价之前。</b>
     *
     * <p>⚠️ 另一件要记住的事：<b>删商品不会删磁盘上的图片文件</b>，
     * 这是本轮的明知简化（见 {@code FileStorageServiceImpl} 的类注释）。
     * 文件只增不减，将来由定时扫孤儿文件的机制处理。
     * 所以删商品之后，那个商品晒图的文件也会留在 {@code uploads/} 里
     * —— 这是<b>已知的、写出来的</b>取舍，不是漏了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // ★ 四级级联，顺序一层都不能反：晒图 → 评价 → 图集 → 商品。
        //   见上面 javadoc 里的完整论证。
        //
        //   ⚠️ 这两条（以及下面的图集）【都不判影响行数】：
        //      返回 0 表示「这个商品本来就没评价 / 没晒图 / 没图集」，
        //      是完全正常的情况，不是错误。
        //
        //   ★ 为什么直接用 Mapper 而不调 ProductReviewService：
        //     见本类字段 productReviewMapper 上的注释 ——
        //     那边是「管理员删一条评价」的语义（会判「评价不存在」并抛错），
        //     这里要的是「把这个商品下的评价全删掉，一条都没有也正常」。
        productReviewImageMapper.deleteByProductId(id);
        productReviewMapper.deleteByProductId(id);

        productImageMapper.deleteByProductId(id);

        int affected = productMapper.deleteById(id);

        // 用影响行数判断，而不是先 select 一次再 delete。
        // 少一次查询，而且在高并发下更准确 ——
        // 「先查后删」两步之间，记录可能已经被别人删掉了
        if (affected == 0) {
            // ⚠️ 这个分支会让整个事务回滚，包括上面那条「删图集」——
            //    所以「商品不存在」时报错【不会】留下「图集被删了但我们说商品不存在」
            //    这种半截状态。这正是把它们放进同一个事务的价值。
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已被删除");
        }

        log.info("删除商品成功, id={}", id);
    }

    // =======================================================================
    //  私有辅助方法
    // =======================================================================

    /**
     * 校验分类是否存在且启用。
     *
     * <p>抽成私有方法，是因为新增和修改都要做这个校验。
     * 提取出来后业务规则只写一份，将来规则变了（比如允许挂到禁用分类下）
     * 也只改一个地方。
     */
    private void checkCategoryAvailable(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "所选分类不存在");
        }
        if (category.getStatus() != null && category.getStatus() == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "所选分类已被禁用");
        }
    }

    /**
     * 用一组 URL <b>整体替换</b>某个商品的图集（★ 里程碑 11 新增）。
     *
     * <p>做的事情就是「先删光、再按顺序插入」。{@code create} 和
     * {@code update} 都调它 —— <b>转换逻辑只有这一处</b>，
     * 这是从 {@code OrderServiceImpl.toVO} 那段注释里学来的：
     * 「第二条转换路径就是将来加字段时漏掉一处的来源」。
     *
     * <p>★ {@code sort_no} 直接取<b>数组下标</b>。
     * 这个「下标即顺序」的约定是整个图集排序功能的地基 ——
     * 前端的上移/下移只是换数组里两个元素的位置，
     * 提交上来之后顺序就自然落在 sort_no 上了，
     * 服务端不需要知道「谁和谁换了」。
     *
     * @param productId 商品 id，必须已经存在（create 里是在 insert 之后调的）
     * @param urls      图集 URL，按展示顺序。<b>可以是 null 或空</b> ——
     *                  对「整体替换」这个语义来说，两者都是「替换成 0 张」。
     *                  （注意这和 {@code update} 那个「null = 不改」的语义
     *                  <b>不是同一层</b>：那个判断在调用处，因为只有
     *                  {@code update} 分得清「字段没传」和「传了空数组」。
     *                  到了这里，null 早就已经被解释过了。）
     */
    private void replaceImages(Long productId, List<String> urls) {
        // ⚠️⚠️ 写这个方法的时候踩过一次，记下来：
        //
        //   原本这里是「urls == null || urls.isEmpty() 就直接 return」，
        //   理由是下面那个 <foreach> 的空集合问题（见下）。
        //   —— 那个理由本身是对的，但【位置放错了】：
        //   把判空放在方法最顶上，等于顺手把 update 传进来的 [] 也吞掉了，
        //   于是「清空图集」变成了「什么都不做」。
        //
        //   这个 bug 不报错、不抛异常、删到 0 行也是合法结果 ——
        //   用户点「删除全部图片」再保存，看起来成功，重新打开图还在。
        //   是 test-upload.py 里那条「images: [] 把图集清空了」逮住的。
        //
        //   ★ 提炼：一个「防止炸掉」的守门条件，必须放在【它真正保护的那一步】
        //     前面，不能放在方法开头 —— 放在开头就变成了「提前放弃整个方法」，
        //     而这个方法的其余部分（DELETE）本来是该执行的。
        List<String> targets = urls == null ? List.of() : urls;

        // 校验放在这里而不是分散在两个调用方，同样是「规则只有一处定义」。
        //
        // ★★ 里程碑 12：这条校验【搬去了】 FileStorageService.requireUploadedImages。
        //   搬家的理由不是「两个地方都在用」，是「这条规则属于谁」——
        //   那个服务生成这些地址，所以「什么算合法地址」由它定义。
        //   评价晒图要守同一条边界，而安全边界出现第二处定义一定会分岔
        //   （改了一处忘了另一处，两处代码看起来都完全正确）。
        //
        //   ⚠️ 是【搬走】不是【复制走】。如果这里留一份、那边也有一份，
        //      这次重构就只是多造了一个将来会分岔的地方。
        //
        // ★ 为什么这条边界在【图集】上守得这么严，而 product.cover 不管：
        //   ① 图集没有历史包袱 —— product_image 是里程碑 11 新建的表，
        //      库里 40 多件商品一条图集都没有，所以严格不需要兼容任何东西。
        //      而 cover 那一列从建表起就是手填的，里面全是 /images/*.svg
        //      这类相对路径和一堆外链，动它就是把现在能用的东西弄坏。
        //   ② 图集的输入来源【只有上传按钮一个】（ProductForm.vue 里的
        //      URL 文本框只管 cover），那就把这条边界守住。
        fileStorageService.requireUploadedImages(targets);

        // ★ 先删光。这一步【必须无条件执行】—— 空数组的语义就是「清空」。
        productImageMapper.deleteByProductId(productId);

        // ⚠️⚠️ 这个判空才是【承重】的，它保护的正是下面那条 INSERT。
        //   batchInsert 的 <foreach> 遇到空集合会拼出
        //       INSERT INTO product_image (...) VALUES
        //   —— 一个没有 VALUES 的 INSERT，SQL 语法错误，直接抛异常。
        //   这不是理论风险：里程碑 10 的 attachItems 已经踩过同一个坑
        //   （那边是 IN () 的形态，空集合同样是语法错误）。
        //   空集合必须在 Java 层拦住，不能指望 SQL 兜底。
        //
        //   ⚠️ 注意这个 return 在 DELETE 之后 —— 位置是这段代码的全部要点。
        if (targets.isEmpty()) {
            return;
        }

        List<ProductImage> rows = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            ProductImage row = new ProductImage();
            row.setProductId(productId);
            row.setUrl(targets.get(i));
            row.setSortNo(i);
            rows.add(row);
        }

        // 到这里 rows 一定非空（上面已经拦住了空集合）
        productImageMapper.batchInsert(productId, rows);
    }

    /**
     * ★ 这里原来有一个私有方法 {@code checkImageUrls}（里程碑 11 写的），
     * <b>里程碑 12 把它搬去了 {@code FileStorageService.requireUploadedImages}</b>。
     * 调用点在 {@link #replaceImages} 里。
     *
     * <p><b>为什么要把「本类的一个私有方法」搬到另一个服务的接口上？</b>
     * 因为评价晒图需要同一条规则，而在两个 Service 里各留一份，
     * 就是一条安全边界的两处定义 —— <b>它们一定会有一天分岔</b>，
     * 而分岔之后两处代码看起来都完全正确。
     *
     * <p>搬家的判据不是「两个地方都在用」（那只是搬家的<b>时机</b>），
     * 而是<b>「这条规则属于谁」</b>：{@code FileStorageService.saveImage}
     * 定义了「什么算一个合法的图片地址」，所以校验地址形状的规则
     * 就该和它待在一起。<b>换实现（比如换 OSS）时要改的仍然只有一个类。</b>
     *
     * <p>⚠️ 那段「条数和长度的校验为什么写了三遍」的完整表格
     * 现在在 {@code FileStorageService.requireUploadedImages} 的 javadoc 里，
     * 这边不重抄 —— <b>注释和代码一样，复制粘贴出来的第二份迟早会不一致。</b>
     */
}
