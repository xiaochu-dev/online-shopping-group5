package com.group5.shop.portal.domain;

import com.group5.shop.portal.domain.Intent;

/**
 * 卖家意向队列的一行（T2-18）。
 *
 * <p>{@code position} 不落库，由查询顺序算出来：队首是 1。
 * 这样一条意向结束或被作废，后面的人自动前移一位，不需要批量 UPDATE。</p>
 *
 * @param position 队列位次，从 1 开始
 * @param intent   意向单
 * @author 周到
 */
public record QueueRow(int position, Intent intent) {

    /** 是否队首：只有队首能「确认交易」 */
    public boolean head() {
        return position == 1;
    }

    /** 是否正在交易中（已确认，等结果） */
    public boolean trading() {
        return "trading".equals(intent.getStatus());
    }
}
