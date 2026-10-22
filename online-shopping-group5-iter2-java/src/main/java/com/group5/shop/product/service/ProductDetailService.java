package com.group5.shop.product.service;

import com.group5.shop.product.domain.*;
import com.group5.shop.portal.contract.ProductPort;
import com.group5.shop.common.exception.*;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@Primary
public class ProductDetailService implements ProductPort {
    private final ProductMapper products;
    private final StockService stock;
    private final JdbcTemplate jdbc;
    public ProductDetailService(ProductMapper products,StockService stock,JdbcTemplate jdbc) { this.products=products; this.stock=stock; this.jdbc=jdbc; }
    public Product get(Long id) {
        Product p=products.selectById(id); if(p==null) throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在"); return p;
    }
    @Override public void freeze(Long id) { stock.freeze(id); }
    @Override public void release(Long id) { stock.release(id); }
    @Override public void deduct(Long id) { stock.deduct(id); }
    @Override public Integer stockOf(Long id) { return stock.stockOf(id); }
    @Override public List<String> imagesOf(Long id) {
        return jdbc.queryForList("SELECT file_path FROM product_image WHERE product_id=? ORDER BY is_primary DESC,sort_no,id",String.class,id);
    }
}
