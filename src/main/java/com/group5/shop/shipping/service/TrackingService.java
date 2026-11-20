package com.group5.shop.shipping.service;

import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.shipping.domain.Shipment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 物流轨迹（挑战需求 C.2.4，owner：胡成溯）。
 *
 * <p><b>为什么是「按发货时间推算」而不是接真实快递接口</b>：课程环境没有顺丰/京东的
 * 开放平台账号，真实对接既拿不到密钥也无法演示。这里用发货时间 + 固定节点生成轨迹，
 * <b>接口形态与真实物流一致</b>（一组带时间的事件），将来换成真实查询只需替换本类实现，
 * 页面与控制器不用改。</p>
 *
 * <p><b>节点：揽收 → 运输中 → 派送中 → 已签收</b>，分别对应发货后 0 / 6 / 18 / 24 小时。
 * 未到时间的节点标记为「未完成」，页面显示为浅色，让买家知道下一步会发生什么。</p>
 *
 * @author 胡成溯（组长）
 */
@Service
public class TrackingService {

    private static final long HOUR_MILLIS = 3_600_000L;

    /** 单个物流节点 */
    public record TrackingEvent(long at, String title, String detail, boolean done) {

        /**
         * 时间展示。
         *
         * @return 形如 11-20 15:30
         */
        public String timeText() {
            String text = TimeUtils.formatDateTime(at);
            return text == null || text.length() < 16 ? text : text.substring(5, 16);
        }
    }

    /**
     * 生成完整轨迹（含尚未发生的节点）。
     *
     * @param shipment 发货记录
     * @param now      当前时间（毫秒），便于测试注入
     * @return 按时间正序的节点列表
     */
    public List<TrackingEvent> timeline(Shipment shipment, long now) {
        List<TrackingEvent> events = new ArrayList<>(4);
        if (shipment == null) {
            return events;
        }
        long base = shipment.getShippedAt() == null ? now : shipment.getShippedAt();
        String carrier = shipment.carrierLabel();
        String no = shipment.getTrackingNo() == null ? "" : shipment.getTrackingNo();

        events.add(new TrackingEvent(base,
                "已揽收",
                carrier + " 已收件，运单号 " + no,
                true));
        events.add(event(base + 6 * HOUR_MILLIS, now,
                "运输中",
                "快件已到达【杭州转运中心】"));
        events.add(event(base + 18 * HOUR_MILLIS, now,
                "派送中",
                "快递员正在派送，请保持电话畅通"));
        events.add(event(base + 24 * HOUR_MILLIS, now,
                "已签收",
                "快件已签收，感谢使用" + carrier));
        return events;
    }

    /**
     * 当前物流状态文案（列表页用）。
     *
     * @param shipment 发货记录
     * @param now      当前时间
     * @return 形如「派送中」
     */
    public String statusText(Shipment shipment, long now) {
        String text = "已揽收";
        for (TrackingEvent event : timeline(shipment, now)) {
            if (event.done()) {
                text = event.title();
            }
        }
        return text;
    }

    /**
     * 最近一条已发生的节点（详情页顶部高亮）。
     *
     * @param shipment 发货记录
     * @param now      当前时间
     * @return 最近节点；无记录返回 null
     */
    public TrackingEvent latest(Shipment shipment, long now) {
        TrackingEvent latest = null;
        for (TrackingEvent event : timeline(shipment, now)) {
            if (event.done()) {
                latest = event;
            }
        }
        return latest;
    }

    private TrackingEvent event(long at, long now, String title, String detail) {
        return new TrackingEvent(at, title, detail, at <= now);
    }
}
