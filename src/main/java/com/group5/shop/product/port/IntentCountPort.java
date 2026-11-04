package com.group5.shop.product.port;

import java.util.Map;

/**
 * 商品待处理意向数的读取端口（T3-02，迭代 3 商品列表补强）。
 *
 * <p><b>为什么是端口而不是直接查 {@code intent} 表</b>：{@code intent} 表归周到（portal 模块）所有，
 * 任务表 v4.0 的硬规则第 2 条禁止跨模块读写他人表。这里由消费方（product）定义接口，
 * 提供方（portal）实现并注册为 Spring Bean，{@code ProductController} 通过
 * {@code ObjectProvider} 取用——实现到位就自动接上，没到位也不会启动失败。</p>
 *
 * <p>与 {@code admin.port.IntentHistoryPort} 是同一套约定（消费方定义、提供方实现），
 * 只是这份端口服务于商品列表页，用于在「我的商品」里直接看到每个商品有多少待处理意向，
 * 而不必再单独设一个「交易与意向」导航项。</p>
 *
 * <p><b>当前状态</b>：portal 已通过 {@code IntentCountProvider} 实现本端口，
 * 商品列表页的「待处理意向」一栏会自动显示；若实现 Bean 不存在，控制器取到 null 并回退为 0。</p>
 *
 * @author 胡成溯（组长）
 */
public interface IntentCountPort {

    /**
     * 统计每个商品的<b>未结束</b>意向数（排队中 + 交易中）。
     *
     * <p>返回的是全库聚合结果，调用方按自己的商品 id 取用即可，
     * 避免「每个商品查一次」的 N+1 查询。</p>
     *
     * @return 商品 id → 待处理条数；无数据返回空 Map
     */
    Map<Long, Integer> countActiveGroupByProduct();
}
