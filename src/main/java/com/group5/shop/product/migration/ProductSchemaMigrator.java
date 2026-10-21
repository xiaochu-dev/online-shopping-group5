package com.group5.shop.product.migration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** 仅升级旧 Java 商品表。先做 SQLite 一致性备份，保留商品及分类，不清空账号或订单。 */
@Component
@Order(10)
public class ProductSchemaMigrator implements ApplicationRunner {
    private final DataSource source;
    public ProductSchemaMigrator(DataSource source) { this.source=source; }
    @Override public void run(ApplicationArguments args) throws Exception {
        try(Connection c=source.getConnection()) {
            if(columns(c,"product").containsAll(Set.of("stock","off_reason")) && columns(c,"category").contains("parent_id")) {
                try(Statement s=c.createStatement()) { s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_category_root_name ON category(name) WHERE parent_id IS NULL"); }
                return;
            }
            migrate(c);
        }
    }
    public void migrate(Connection c) throws Exception {
        Set<String> pc=columns(c,"product"),cc=columns(c,"category");
        String url=c.getMetaData().getURL();
        if(!url.contains(":memory:")) {
            String file=url.substring("jdbc:sqlite:".length());
            String backup=Path.of(file+".before-product-v4-"+System.currentTimeMillis()+".db").toAbsolutePath().toString();
            try(Statement s=c.createStatement()) { s.execute("VACUUM INTO '"+backup.replace("'","''")+"'"); }
        }
        boolean original=c.getAutoCommit();
        try(Statement s=c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF"); c.setAutoCommit(false);
            try {
                String schema=new ClassPathResource("schema/02-product.sql").getContentAsString(StandardCharsets.UTF_8);
                for(String table:List.of("category","product")) {
                    String start="CREATE TABLE IF NOT EXISTS "+table+" (";
                    int from=schema.indexOf(start),end=schema.indexOf(");",from);
                    s.execute(schema.substring(from,end+2).replace(start,"CREATE TABLE "+table+"_upgrade ("));
                }
                s.execute("INSERT INTO category_upgrade(id,parent_id,name,sort_no,status,created_at,updated_at) SELECT id,"+
                    (cc.contains("parent_id")?"parent_id":"NULL")+",name,sort_no,status,created_at,updated_at FROM category");
                // 给旧商品未分类或原一级分类建立明确的迁移叶子，原分类和商品编号保留。
                s.execute("INSERT INTO category_upgrade(name,sort_no,status,created_at,updated_at) VALUES('历史未分类',999,'enabled',0,0)");
                long fallback;
                try(ResultSet r=s.executeQuery("SELECT last_insert_rowid()")) { r.next(); fallback=r.getLong(1); }
                s.execute("INSERT INTO category_upgrade(parent_id,name,sort_no,status,created_at,updated_at) "+
                    "SELECT id,'迁移商品',999,'enabled',0,0 FROM category_upgrade WHERE parent_id IS NULL");
                String category="COALESCE((SELECT CASE WHEN cat.parent_id IS NULL THEN "+
                    "(SELECT id FROM category_upgrade child WHERE child.parent_id=cat.id AND child.name='迁移商品') ELSE cat.id END "+
                    "FROM category_upgrade cat WHERE cat.id=p.category_id),"+
                    "(SELECT id FROM category_upgrade WHERE parent_id="+fallback+" AND name='迁移商品'))";
                s.execute("INSERT INTO product_upgrade(id,seller_id,category_id,name,description,image_path,price_cents,stock,off_reason,status,published_at,updated_at,traded_at,trade_result) "+
                    "SELECT id,seller_id,"+category+",name,description,image_path,price_cents,"+
                    (pc.contains("stock")?"stock":"1")+","+(pc.contains("off_reason")?"off_reason":"CASE WHEN status='delisted' THEN 'manual' ELSE NULL END")+
                    ",status,published_at,updated_at,traded_at,trade_result FROM product p");
                s.execute("DROP TABLE product"); s.execute("DROP TABLE category");
                s.execute("ALTER TABLE category_upgrade RENAME TO category"); s.execute("ALTER TABLE product_upgrade RENAME TO product");
                s.execute("CREATE UNIQUE INDEX uq_category_root_name ON category(name) WHERE parent_id IS NULL");
                s.execute("CREATE INDEX idx_product_seller ON product(seller_id,status)");
                s.execute("CREATE INDEX idx_product_status ON product(status,published_at)");
                s.execute("CREATE INDEX idx_product_category ON product(category_id,status)");
                try(ResultSet check=s.executeQuery("PRAGMA foreign_key_check")) { if(check.next()) throw new SQLException("升级后外键检查失败，已回滚"); }
                c.commit();
            } catch(Exception e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(original); s.execute("PRAGMA foreign_keys=ON"); }
        }
    }
    private Set<String> columns(Connection c,String table) throws SQLException {
        Set<String> names=new HashSet<>(); try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) { while(r.next()) names.add(r.getString("name")); } return names;
    }
}
