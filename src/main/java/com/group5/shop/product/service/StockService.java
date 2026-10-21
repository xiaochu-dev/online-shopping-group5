package com.group5.shop.product.service;

import com.group5.shop.product.contract.IntentQueuePort;
import com.group5.shop.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {
    private final JdbcTemplate jdbc;
    private final IntentQueuePort queue;
    public StockService(JdbcTemplate jdbc, IntentQueuePort queue) { this.jdbc=jdbc; this.queue=queue; }
    @Transactional
    public void deduct(Long id) {
        int changed=jdbc.update("UPDATE product SET stock=stock-1, updated_at=?, " +
            "status=CASE WHEN stock=1 THEN 'off_shelf' ELSE status END, " +
            "off_reason=CASE WHEN stock=1 THEN 'auto' ELSE off_reason END " +
            "WHERE id=? AND stock>0 AND (status IN ('on_sale','frozen') OR (status='off_shelf' AND off_reason='manual'))",System.currentTimeMillis(),id);
        if(changed!=1) throw bad("商品库存不足，无法扣减");
        if(stockOf(id)==0) queue.failByProduct(id,"商品库存已售罄，本次排队自动结束");
    }
    public Integer stockOf(Long id) {
        var rows=jdbc.queryForList("SELECT stock FROM product WHERE id=?",Integer.class,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    @Transactional
    public void freeze(Long id) {
        if(jdbc.update("UPDATE product SET status='frozen',updated_at=? WHERE id=? AND status='on_sale' AND stock>0",System.currentTimeMillis(),id)!=1) throw bad("商品已下架、售罄或正在交易");
    }
    @Transactional
    public void release(Long id) {
        jdbc.update("UPDATE product SET status=CASE WHEN stock=0 THEN 'off_shelf' ELSE 'on_sale' END, " +
            "off_reason=CASE WHEN stock=0 THEN 'auto' ELSE NULL END,updated_at=? WHERE id=? AND status='frozen'",System.currentTimeMillis(),id);
    }
    @Transactional
    public void changeStock(Long seller,Long id,int stock) {
        if(stock<0) throw bad("库存不能为负数");
        if(stock==0 && queue.hasTrading(id)) throw bad("交易尚未结束，不能将库存调为0");
        if(jdbc.update("UPDATE product SET stock=?,updated_at=?,"+
            "status=CASE WHEN ?=0 THEN 'off_shelf' ELSE status END,"+
            "off_reason=CASE WHEN ?=0 THEN 'auto' ELSE off_reason END " +
            "WHERE id=? AND seller_id=? AND (status!='frozen' OR ?>0)",stock,System.currentTimeMillis(),stock,stock,id,seller,stock)!=1)
            throw bad("商品不存在、无权操作，或交易中不能把库存调为0");
        if(stock==0) queue.failByProduct(id,"商品库存已售罄，本次排队自动结束");
    }
    @Transactional
    public void offShelf(Long seller,Long id) {
        if(jdbc.update("UPDATE product SET status='off_shelf',off_reason='manual',updated_at=? WHERE id=? AND seller_id=?",System.currentTimeMillis(),id,seller)!=1) throw bad("商品不存在或无权操作");
    }
    @Transactional
    public void relist(Long seller,Long id) {
        String status=queue.hasTrading(id)?"frozen":"on_sale";
        if(jdbc.update("UPDATE product SET status=?,off_reason=NULL,updated_at=? WHERE id=? AND seller_id=? AND stock>0 AND status IN ('off_shelf','delisted')",status,System.currentTimeMillis(),id,seller)!=1) throw bad("重新上架需要有库存且当前已下架");
    }
    private BusinessException bad(String text) { return new BusinessException(ErrorCode.PARAM_INVALID,text); }
}
