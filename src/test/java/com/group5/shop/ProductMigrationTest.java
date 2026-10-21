package com.group5.shop;

import com.group5.shop.product.migration.ProductSchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class ProductMigrationTest {
    @TempDir Path dir;
    @Test void legacyProductsAreBackedUpAndPreserved() throws Exception {
        Path db=dir.resolve("legacy.db");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            Statement s=c.createStatement();
            s.execute("CREATE TABLE seller(id INTEGER PRIMARY KEY)");s.execute("INSERT INTO seller VALUES(1)");
            ScriptUtils.executeSqlScript(c,new ClassPathResource("product/legacy-product.sql"));
            s.execute("INSERT INTO product(id,seller_id,category_id,name,description,price_cents,published_at,updated_at) VALUES(7,1,1,'旧键盘','原商品描述',990,1,1),(8,1,NULL,'未分类商品','',1000,2,2)");
            s.execute("CREATE TABLE intent(id INTEGER PRIMARY KEY,product_id INTEGER REFERENCES product(id))");
            s.execute("INSERT INTO intent VALUES(99,7)");
            s.close();
            new ProductSchemaMigrator(null).migrate(c);
            s=c.createStatement();
            try(ResultSet r=s.executeQuery("SELECT count(*) FROM product")) {r.next();assertThat(r.getInt(1)).isEqualTo(2);}
            try(ResultSet r=s.executeQuery("SELECT p.id,p.stock,c.parent_id FROM product p JOIN category c ON c.id=p.category_id ORDER BY p.id")) {
                assertThat(r.next()).isTrue();assertThat(r.getInt("id")).isEqualTo(7);assertThat(r.getInt("stock")).isEqualTo(1);assertThat(r.getObject("parent_id")).isNotNull();
                assertThat(r.next()).isTrue();assertThat(r.getInt("id")).isEqualTo(8);assertThat(r.getObject("parent_id")).isNotNull();
            }
            try(ResultSet r=s.executeQuery("SELECT product_id FROM intent WHERE id=99")) {r.next();assertThat(r.getInt(1)).isEqualTo(7);}
            try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) {assertThat(r.next()).isFalse();}
            try(var files=Files.list(dir)) {assertThat(files.filter(f->f.getFileName().toString().contains("before-product-v4")).count()).isEqualTo(1);}
        }
    }
    @Test void failedMigrationKeepsOriginalRows() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:");Statement s=c.createStatement()) {
            s.execute("CREATE TABLE seller(id INTEGER PRIMARY KEY)");s.execute("INSERT INTO seller VALUES(1)");
            ScriptUtils.executeSqlScript(c,new ClassPathResource("product/legacy-product.sql"));
            s.execute("INSERT INTO product(id,seller_id,name,description,price_cents,published_at,updated_at) VALUES(7,1,' ','旧空名数据',990,1,1)");
            assertThatThrownBy(()->new ProductSchemaMigrator(null).migrate(c)).isInstanceOf(SQLException.class);
            try(ResultSet r=s.executeQuery("SELECT description FROM product WHERE id=7")) {r.next();assertThat(r.getString(1)).isEqualTo("旧空名数据");}
            try(ResultSet r=s.executeQuery("SELECT count(*) FROM pragma_table_info('product') WHERE name='stock'")) {r.next();assertThat(r.getInt(1)).isZero();}
        }
    }
}
