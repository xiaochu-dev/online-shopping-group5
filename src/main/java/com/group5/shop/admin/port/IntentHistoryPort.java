package com.group5.shop.admin.port;

import java.util.List;

/**
 * 买家购买历史的读取端口（T2-05，挑战需求 A.2.3）。
 *
 * <p><b>为什么是端口而不是直接查 {@code intent} 表</b>：{@code intent} 表归周到（portal 模块）所有，
 * 任务表 v4.0 的硬规则第 2 条禁止跨模块读写他人表。这里由消费方（admin）定义接口，
 * 提供方（portal）实现并注册为 Spring Bean，{@code CustomerAdminController} 通过
 * {@code ObjectProvider} 取用——实现到位就自动接上，没到位也不会启动失败。</p>
 *
 * <p>接口签名与任务表 v4.0 约定的 {@code IntentQueueService.listByBuyer(buyerId)} 对齐。</p>
 *
 * <p><b>当前状态</b>：portal 已通过 {@code IntentHistoryProvider} 实现本端口（T2-17），
 * 客户详情页「购买历史」一栏会自动显示该客户已结束的订单（success / failed）。
 * 若实现 Bean 不存在，{@code CustomerAdminController} 取到 null 并回退为空态提示，
 * 客户基本信息部分不受影响。</p>
 *
 * @author 胡成溯（组长）
 */
public interface IntentHistoryPort {

    /**
     * 查询某个买家的购买历史。
     *
     * @param buyerId 买家 id
     * @return 历史记录，按时间倒序；无记录返回空列表
     */
    List<IntentHistoryItem> listByBuyer(Long buyerId);
}
