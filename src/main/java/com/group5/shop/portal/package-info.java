/**
 * 买家门户与交易模块（负责人：周到，T2-11 ~ T2-14）。
 *
 * <p><b>本包当前为空占位</b>，用于固定「包目录隔离」的分工边界：
 * 本包内的文件只有周到可以修改，胡成溯（common / auth）与黄程宇（product）只读不写。</p>
 *
 * <h2>开工前先读</h2>
 * <ol>
 *   <li>{@code docs/API契约-迭代2.md} —— 列表 / 搜索 / 上下架 / 意向四条链路的路由与参数约定；</li>
 *   <li>{@code src/main/resources/schema.sql} —— product / intent 两张表已建好；</li>
 *   <li>{@link com.group5.shop.common.entity.Product}、{@link com.group5.shop.common.entity.Intent}
 *       —— 实体已就绪，直接用 Mapper。</li>
 * </ol>
 *
 * <h2>可直接复用的基座能力</h2>
 * <ul>
 *   <li>{@link com.group5.shop.common.mapper.ProductMapper#selectPage}
 *       —— 分页插件已在 {@code common/config/MybatisPlusConfig} 注册（方言已指定 SQLite，
 *       返回 {@code Page<Product>} 的 total 是真实总数，不是内存截断）；</li>
 *   <li>{@link com.group5.shop.common.mapper.IntentMapper#selectQueue(Long)} —— 队列按
 *       {@code created_at, id} 正序，位次不落库；</li>
 *   <li>{@link com.group5.shop.common.mapper.IntentMapper#selectByTokenCode(String)} —— 口令码撤销；</li>
 *   <li>{@link com.group5.shop.common.util.TimeUtils#randomTokenCode()} —— 32 位十六进制口令码；</li>
 *   <li>{@link com.group5.shop.common.api.PageResult#of} —— 把 MP 的 Page 转成页面用的分页对象；</li>
 *   <li>{@link com.group5.shop.common.constant.ProductStatus}、{@link com.group5.shop.common.constant.IntentStatus}。</li>
 * </ul>
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>买家侧路由<b>公开</b>（不加登录拦截），页面模板放 {@code templates/portal/}；</li>
 *   <li>仅展示 {@code status = 'on_sale'} 的商品；下架商品不接收新意向；</li>
 *   <li>意向「提交即入队」，写 intent 时 status 固定 {@code pending}，token_code 用上面那个工具生成。</li>
 * </ul>
 *
 * @author 周到
 */
package com.group5.shop.portal;
