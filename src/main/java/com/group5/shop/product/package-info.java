/**
 * 商品中心模块（负责人：黄程宇，T2-07 ~ T2-10）。
 *
 * <p><b>本包当前为空占位</b>，用于固定「包目录隔离」的分工边界：
 * 本包内的文件只有黄程宇可以修改，胡成溯（common / auth）与周到（portal）只读不写。</p>
 *
 * <h2>开工前先读</h2>
 * <ol>
 *   <li>{@code docs/API契约-迭代2.md} —— 路由、表单字段、重定向约定都写死在那里，照着填即可；</li>
 *   <li>{@code src/main/resources/schema.sql} —— product / category 两张表已建好，字段不要再改；</li>
 *   <li>{@link com.group5.shop.common.entity.Product}、{@link com.group5.shop.common.entity.Category}
 *       —— 实体已就绪，直接注入对应 Mapper 使用，<b>不要自己再建一套</b>。</li>
 * </ol>
 *
 * <h2>可直接复用的基座能力</h2>
 * <ul>
 *   <li>{@link com.group5.shop.common.mapper.ProductMapper}、{@link com.group5.shop.common.mapper.CategoryMapper}
 *       —— 继承 MyBatis-Plus BaseMapper，增删改查免写 SQL；</li>
 *   <li>{@code CategoryMapper.selectEnabled()} —— 发布页分类下拉框直接用它；</li>
 *   <li>{@link com.group5.shop.common.util.MoneyUtils#yuanToCents(String)} —— 价格「元 → 分」，
 *       已含「必填、> 0、最多两位小数」三项校验，校验失败抛 BusinessException；</li>
 *   <li>{@link com.group5.shop.common.exception.BusinessException} —— 业务错误统一抛它，
 *       全局异常处理器会自动把提示语渲染到页面；</li>
 *   <li>{@link com.group5.shop.common.constant.ProductStatus} —— 状态值与中文名、徽章样式。</li>
 * </ul>
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>页面路由前缀 {@code /admin/products}、{@code /admin/categories}（自动受登录拦截器保护）；</li>
 *   <li>页面模板放 {@code templates/product/}；</li>
 *   <li>表单成功后 303 重定向到 {@code ?msg=}，失败 {@code ?error=}，中文 UTF-8 编码；</li>
 *   <li>上传的图片存到 {@code ./uploads/}，数据库 image_path 存相对路径 {@code uploads/xxx.png}。</li>
 * </ul>
 *
 * @author 黄程宇
 */
package com.group5.shop.product;
