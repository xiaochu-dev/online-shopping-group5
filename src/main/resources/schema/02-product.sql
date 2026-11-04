-- 商品中心，黄程宇 T2-08~T2-12。保留 delisted 仅兼容历史状态。
CREATE TABLE IF NOT EXISTS category (
 id INTEGER PRIMARY KEY AUTOINCREMENT, parent_id INTEGER REFERENCES category(id),
 name TEXT NOT NULL, sort_no INTEGER NOT NULL DEFAULT 0,
 status TEXT NOT NULL DEFAULT 'enabled' CHECK(status IN ('enabled','disabled')),
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(parent_id,name)
);
CREATE TABLE IF NOT EXISTS product (
 id INTEGER PRIMARY KEY AUTOINCREMENT, seller_id INTEGER NOT NULL REFERENCES seller(id),
 category_id INTEGER NOT NULL REFERENCES category(id), name TEXT NOT NULL CHECK(length(trim(name))>0),
 description TEXT NOT NULL DEFAULT '', image_path TEXT NOT NULL DEFAULT 'images/placeholder.png',
 price_cents INTEGER NOT NULL CHECK(price_cents>0), stock INTEGER NOT NULL DEFAULT 1 CHECK(stock>=0),
 off_reason TEXT CHECK(off_reason IN ('auto','manual')),
 status TEXT NOT NULL DEFAULT 'on_sale' CHECK(status IN ('on_sale','frozen','off_shelf','delisted')),
 published_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, traded_at INTEGER,
 trade_result TEXT CHECK(trade_result IN ('success','failed','manual_delist'))
);
CREATE TABLE IF NOT EXISTS product_image (
 id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL REFERENCES product(id),
 file_path TEXT NOT NULL, sort_no INTEGER NOT NULL, is_primary INTEGER NOT NULL DEFAULT 0 CHECK(is_primary IN (0,1)),
 UNIQUE(product_id,sort_no)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_product_image_primary ON product_image(product_id) WHERE is_primary=1;
CREATE INDEX IF NOT EXISTS idx_product_seller ON product(seller_id,status);
CREATE INDEX IF NOT EXISTS idx_product_status ON product(status,published_at);
CREATE INDEX IF NOT EXISTS idx_product_category ON product(category_id,status);
INSERT OR IGNORE INTO category(id,name,sort_no,status,created_at,updated_at) VALUES
 (1,'数码电子',1,'enabled',0,0),(2,'图书教材',2,'enabled',0,0),
 (3,'服饰鞋包',3,'enabled',0,0),(4,'生活百货',4,'enabled',0,0),(5,'运动户外',5,'enabled',0,0);

-- --------------------------------------------------------------------------
-- 二级分类种子数据（挑战需求 A.1-2）
-- --------------------------------------------------------------------------
-- 为什么必须有：商品发布要求挂到【二级叶子分类】，发布页的 <optgroup> 只渲染
-- 一级分类作为分组标题、真正可选项来自 children。若库里只有一级分类，
-- 下拉框会显示 5 个灰色分组标题却一个可选项都没有，表现为「选不了」。
-- 用 INSERT OR IGNORE + 显式 id，可重复执行；固定 id 避开 1~5（一级分类占用）。
INSERT OR IGNORE INTO category(id,parent_id,name,sort_no,status,created_at,updated_at) VALUES
 -- 数码电子(1)
 (101,1,'手机',1,'enabled',0,0),
 (102,1,'笔记本电脑',2,'enabled',0,0),
 (103,1,'耳机',3,'enabled',0,0),
 (104,1,'键盘鼠标',4,'enabled',0,0),
 -- 图书教材(2)
 (201,2,'计算机类',1,'enabled',0,0),
 (202,2,'文学小说',2,'enabled',0,0),
 (203,2,'考试辅导',3,'enabled',0,0),
 -- 服饰鞋包(3)
 (301,3,'上衣',1,'enabled',0,0),
 (302,3,'裤子',2,'enabled',0,0),
 (303,3,'鞋',3,'enabled',0,0),
 -- 生活百货(4)
 (401,4,'家居用品',1,'enabled',0,0),
 (402,4,'厨房用品',2,'enabled',0,0),
 -- 运动户外(5)
 (501,5,'健身器材',1,'enabled',0,0),
 (502,5,'户外装备',2,'enabled',0,0);
