package com.group5.shop.product.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.product.domain.*;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ProductQueryService {
    private final ProductMapper products;
    public ProductQueryService(ProductMapper products) { this.products=products; }
    public List<Product> findOnSale() {
        return products.selectList(Wrappers.<Product>lambdaQuery().eq(Product::getStatus,"on_sale").gt(Product::getStock,0).orderByDesc(Product::getPublishedAt,Product::getId));
    }
}
