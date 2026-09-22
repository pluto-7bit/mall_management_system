package com.example.mall.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件存储业务接口。
 *
 * <p>当前只有一个实现，而且只做一件事：把上传的图片写进本机磁盘。
 * 之所以还是拆成「接口 + 实现」（理由和 {@link ProductService} 那段一样），
 * 这里多了一条更实在的：<b>上传是这个项目里最可能换掉的一块</b>。
 *
 * <p>★ 本机的磁盘目录只是「现在的选择」，真实项目里几乎都会换成对象存储
 * （阿里云 OSS / 七牛 / MinIO）。换的时候要改的只有
 * {@link com.example.mall.service.impl.FileStorageServiceImpl} 一个类 ——
 * <b>调用方（ProductServiceImpl）拿到的始终是一个 URL 字符串，
 * 它不知道也不需要知道这个字符串背后是磁盘还是 OSS。</b>
 * 这个「不知道」正是接口存在的意义。
 */
public interface FileStorageService {

    /**
     * 保存一张图片，返回它的访问路径。
     *
     * <p>★ 返回的是<b>URL 路径</b>（形如 {@code /uploads/2026/09/<uuid>.png}），
     * 不是 {@code File}，也不是这台机器上的绝对路径。
     *
     * <p>原因是调用方会把这个字符串直接存进数据库的
     * {@code product.cover} / {@code product_image.url} 列 ——
     * 而<b>存进数据库的东西绝不能是某台机器的绝对路径</b>。
     * 换台机器、换个部署目录，库里所有图片的路径就全指错了，
     * 而数据已经写死了，改起来是一次全表 UPDATE。
     *
     * <p>存相对的 URL 路径，把「拼上域名」这件事留给浏览器 ——
     * 同一份数据在开发机（localhost:8080）、测试环境、生产环境都能用。
     *
     * @param file 上传的文件，由 Spring 从 multipart/form-data 请求里解析出来
     * @return 可直接被 {@code <img src>} 使用、也可直接入库的路径
     * @throws com.example.mall.common.BusinessException 文件为空、或者不是受支持的图片格式
     */
    String saveImage(MultipartFile file);

    /**
     * 校验一批地址都是<b>本服务自己上传的</b>（也就是都以 {@code /uploads/} 开头）。
     *
     * <h3>★★ 为什么这个方法在【这里】，而不是留在用它的那个 Service 里</h3>
     *
     * <p>它原来是 {@code ProductServiceImpl} 的一个私有方法 {@code checkImageUrls}，
     * 只服务于商品图集。里程碑 12 的评价晒图需要<b>同一条规则</b> ——
     * 而<b>一条安全边界出现两处定义，一定会有一天分岔</b>
     * （改了一处忘了另一处，于是「晒图能塞外链、图集不能」，
     * 而两处代码看起来都完全正确）。
     *
     * <p>搬到这里的判据不是「两个地方都在用」，而是<b>「这条规则属于谁」</b>：
     * <b>「什么算一个合法的图片地址」是【生成这些地址的那个服务】才知道的事。</b>
     * {@link #saveImage} 定义了地址的形状，所以校验地址形状的规则就该和它待在一起。
     * 客户端提交上来的地址要过这一关，正是「地址只能由服务端生成」这条规矩的兑现。
     *
     * <p>★ 换实现的时候这一点会更明显：如果哪天换成 OSS，
     * {@code /uploads/} 这个前缀很可能会变成 {@code https://bucket.oss-cn-xxx.aliyuncs.com/} ——
     * 那时候要改的只有这一个类。<b>规则和它所描述的事实放在一起，换实现才只需要动一处。</b>
     *
     * <h3>⚠️ 为什么这条边界值得守</h3>
     *
     * <p>放开之后这个字段会变成一个<b>流量放大器</b>：
     * 列表里/详情页里的每一个地址，都会被<b>访问这个页面的每一个浏览器</b>
     * 主动请求一次。允许外链 = 允许把任意第三方地址塞进来 ——
     * 那是一个天然的追踪像素（能统计谁看了什么），也可以用来刷别人的流量。
     *
     * <h3>★ 它校验两件事，因为这两件事都是「地址本身的形状」</h3>
     * <ol>
     *   <li><b>{@code /uploads/} 前缀</b> —— 注解表达不了它，只能写代码。</li>
     *   <li><b>长度不超过 255</b> —— 和 {@code product_image.url} /
     *       {@code product_review_image.url} 两列的 {@code VARCHAR(255)} 对齐。
     *       数据库那一列当然也会拦，但拦下来的是一条 SQL 异常，
     *       在这里拦住才能给用户一句人话。</li>
     * </ol>
     *
     * <p>⚠️ 注意<b>元素个数</b>（图集最多 5 张 / 晒图最多 3 张）不在这里校验 ——
     * 那是业务规则，每个字段都不一样，属于各自的 DTO。
     * <b>「地址长什么样」是通用的，「能放几个」是各自的。</b>
     *
     * <h3>⚠️ 那么「长度」为什么会被校验两遍？（DTO 一遍、这里一遍）</h3>
     *
     * <p>因为这两个校验的<b>生效时机不同</b>，而且其中一处很容易静默失效：
     * <pre>
     *   DTO 上的 {@code @Size(max = 3)}     → 校验「几张」。
     *                                 由 {@code @Valid} 触发，
     *                                 绕过前端直接调接口也拦得住 ✓
     *   DTO 上的 {@code List<@Size(max=255) String>}
     *                                → 校验「单个地址多长」。
     *                                 要写在【泛型参数】上才生效 ✓
     *   本方法                        → 前缀白名单 + 长度。
     *                                 前缀这件事注解表达不了，只能写代码 ✗
     * </pre>
     *
     * <p>⚠️ 值得单独记一句：<b>{@code @Size} 写在 {@code List<String>} 上管的是
     * 「元素个数」，不是「元素长度」。</b>想管单个元素的长度必须写成
     * {@code List<@Size(max = 255) String>}（这叫「容器元素约束」，
     * Hibernate Validator 支持）。
     *
     * <p><b>写错了不会有任何报错，校验只是静默地不生效</b> ——
     * 所以这里对长度再兜一道，两道防线。
     * 长度这件事是「两个地方各守一半」中唯一一个有意的重复，
     * 而重复的理由是<b>其中一处会静默失效</b>，不是「多一道更保险」。
     *
     * @param urls 待校验的地址。<b>null 当作空集合</b>（「没传」和「传了空的」
     *             在这条规则下是同一件事：没什么要校验的）
     * @throws com.example.mall.common.BusinessException 有地址不是本服务上传的、或者超长
     */
    void requireUploadedImages(List<String> urls);
}
