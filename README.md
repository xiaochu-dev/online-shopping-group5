# 在线购物系统 · 第5组 · 迭代2

软件工程实践（I），2026-2027学年第一学期。迭代周期2026-10-09～10-22。

**代码获取：当前仓库默认分支 `main` 仍为迭代1 Python版本。迭代2 Java代码和已合入的商品中心在 `iter2-java`。**

- [打开Java分支](https://github.com/xiaochu-dev/online-shopping-group5/tree/iter2-java)
- [直接下载Java分支ZIP](https://github.com/xiaochu-dev/online-shopping-group5/archive/refs/heads/iter2-java.zip)（网页下载前也可先切换分支再点 Code → Download ZIP）

```powershell
git clone -b iter2-java https://github.com/xiaochu-dev/online-shopping-group5.git
cd online-shopping-group5
```


## 当前功能与分工

| 负责人 | 任务 | 已集成内容 |
| --- | --- | --- |
| 胡成溯 | T2-01～T2-07，13点 | 平台基座、卖家登录与改密、买家账号、客户管理、公共契约 |
| 黄程宇 | T2-08～T2-12，14点 | 两级分类、多商品发布、库存与上下架、最多9图、富文本描述 |
| 周到 | T2-13～T2-18，15点 | 分类筛选与搜索、详情、登录下单、我的订单、队列与交易确认 |

商品中心已随[PR #1](https://github.com/xiaochu-dev/online-shopping-group5/pull/1)合入`iter2-java`，对应代码提交`671ae63`。发布后只能调整库存与上下架，当前没有商品编辑或删除功能。下单和确认交易不扣库存，标记交易成功才原子扣1；库存归零自动下架并结束剩余排队意向。新意向绑定买家账号，不生成口令码。

## 启动与访问

目标环境JDK17、Maven3.9+；`pom.xml`声明Spring Boot3.3.5、MyBatis-Plus3.5.7、SQLite JDBC3.46.1.3、Thymeleaf与Jsoup1.20.1。先核对`java -version`和`mvn -v`实际使用的JDK。

Windows项目根目录执行：

```powershell
.\mvnw.cmd spring-boot:run
```

或双击`run.bat`，已有Maven可用`mvn spring-boot:run`，IDEA可运行`com.group5.shop.ShopApplication`。macOS/Linux使用`./mvnw spring-boot:run`。中文路径下若Wrapper或启动插件报找不到主类，使用已安装Maven和[部署手册](https://github.com/xiaochu-dev/online-shopping-group5/wiki/部署手册-迭代2)中的classpath启动方法。

| 地址 | 功能 |
| --- | --- |
| `http://localhost:8080/` | 公开商品列表、分类筛选与搜索 |
| `http://localhost:8080/buyer/register`、`/buyer/login` | 买家注册与登录 |
| `http://localhost:8080/my/orders` | 当前买家的已结束订单 |
| `http://localhost:8080/login` | 卖家登录，种子账号admin / admin12345 |
| `http://localhost:8080/admin/products`、`/admin/categories` | 卖家商品与分类管理 |
| `http://localhost:8080/admin/intents`、`/admin/customers` | 交易队列与客户管理 |
| `http://localhost:8080/admin/diagnostics` | 数据源及页面实际列出的表结构 |

退出服务使用Ctrl+C。应用配置没有限制监听地址；需要只允许本机访问时显式配置`server.address=127.0.0.1`，参数方法见部署手册。

## 目录与数据

| 路径 | 内容 |
| --- | --- |
| `src/main/java/com/group5/shop/auth/`、`buyer/`、`admin/` | 卖家、买家与客户管理 |
| `src/main/java/com/group5/shop/product/` | 分类、发布、图片、富文本与库存服务 |
| `src/main/java/com/group5/shop/portal/` | 门户、意向、历史、交易和跨模块适配 |
| `src/main/java/com/group5/shop/common/` | 公共返回体、异常、配置、工具等；按团队约定冻结 |
| `src/main/resources/schema/` | 00-base、01-buyer、02-product、03-intent四份初始化SQL |
| `src/main/resources/templates/` | 各模块Thymeleaf模板 |
| `data/shop.db`、`uploads/` | 默认运行数据库与上传文件，不纳入Git |
| `target/` | 编译输出、测试报告，不纳入Git |

数据库共六张表：seller、buyer、category、product、product_image、intent。Entity/Mapper已归各模块domain包。跨模块通过约定接口调用；门户部分旧查询仍直接引用商品Entity/Mapper，具体边界见架构设计。

启动会初始化新库。升级旧Java库时，商品迁移器先备份再事务升级；旧匿名订单迁移存在重建清空的路径，必须先备份核对，不能笼统承诺整库无损。不要用删除数据目录解决登录或升级问题，原始演示数据与上传图片应保留。

## 验证与文档

```powershell
.\mvnw.cmd test
```

已记录的完整回归执行于2026-10-21 23:19：83项，失败0、错误0、跳过0。环境为Windows、JDK25、Maven3.9.11，按Java17目标编译；JDK25测试使用Byte Buddy实验兼容开关。团队Java17环境尚待复跑，本次文档同步没有重跑业务测试。

| 测试类 | 用例数 |
| --- | ---: |
| `AuthFlowTest` | 15 |
| `BuyerAuthTest` | 18 |
| `CommonLayerTest` | 6 |
| `MyOrdersFlowTest` | 3 |
| `PortalFlowTest` | 14 |
| `ProductCenterTest` | 14 |
| `ProductMigrationTest` | 2 |
| `SchemaIdempotencyTest` | 2 |
| `SellerTradeFlowTest` | 9 |
| 合计 | **83** |


- [总API契约](docs/API契约-迭代2.md)
- [架构设计](docs/架构设计-迭代2.md)与[六表数据库设计](docs/数据库设计-迭代2.md)
- [账号与客户契约](docs/契约-账号与客户-迭代2.md)、[商品中心契约](docs/契约-商品中心-迭代2.md)
- [商品中心测试记录](docs/商品中心测试记录-20261021.md)
- [构建手册](https://github.com/xiaochu-dev/online-shopping-group5/wiki/构建过程手册-迭代2)、[部署手册](https://github.com/xiaochu-dev/online-shopping-group5/wiki/部署手册-迭代2)、[团队Wiki](https://github.com/xiaochu-dev/online-shopping-group5/wiki)

团队Java17联调、外链视频实际播放、评审录屏与教师真实反馈尚待补充。本文同步实现事实；原始需求、PM澄清及尚未确认的差异分别保留，不把代码实现当作需求批准。
