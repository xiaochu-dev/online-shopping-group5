package com.group5.shop.product.favorite;

import com.group5.shop.cart.port.FavoritePort;
import com.group5.shop.product.domain.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FavoriteService implements FavoritePort {

    private final JdbcTemplate jdbc;

    public FavoriteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一次加入多件商品，保留已下架的商品，只统计新增收藏。 */
    @Override
    @Transactional
    public int addToFavorites(Long buyerId, List<Long> productIds) {
        long createdAt = System.currentTimeMillis();
        int added = 0;
        for (long productId : productIds) {
            added += jdbc.update("""
                    INSERT INTO favorite (buyer_id, product_id, created_at)
                    SELECT ?, id, ? FROM product
                    WHERE id = ?
                    ON CONFLICT (buyer_id, product_id) DO NOTHING
                    """, buyerId, createdAt, productId);
        }
        return added;
    }

    public boolean remove(long buyerId, long productId) {
        return jdbc.update("DELETE FROM favorite WHERE buyer_id = ? AND product_id = ?",
                buyerId, productId) == 1;
    }

    public List<Product> list(long buyerId) {
        return jdbc.query("""
                SELECT p.id, p.name, p.price_cents, p.status, p.stock, p.off_reason
                FROM favorite f JOIN product p ON p.id = f.product_id
                WHERE f.buyer_id = ?
                ORDER BY f.created_at DESC, f.product_id DESC
                """, (row, index) -> {
            Product product = new Product();
            product.setId(row.getLong("id"));
            product.setName(row.getString("name"));
            product.setPriceCents(row.getLong("price_cents"));
            product.setStatus(row.getString("status"));
            product.setStock(row.getInt("stock"));
            product.setOffReason(row.getString("off_reason"));
            return product;
        }, buyerId);
    }
}
