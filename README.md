# 在线购物系统 · 第 5 组 · 迭代 2

> 课程：软件工程实践（2026-2027 学年第一学期）  
> 迭代周期：2026-10-09 ~ 2026-10-22  
> 技术栈：**Java 17 + Spring Boot 3.3.5 + MyBatis-Plus 3.5.7 + Thymeleaf + SQLite 3**

本迭代由迭代 1 的 Python/FastAPI 版本「翻译式重写」为 Java/Spring Boot，  
数据库设计、页面结构、业务规则全部沿用，并在此基础上做功能增强（多商品、分类、搜索、上下架、意向）。

---

## 1 一分钟跑起来

### 1.1 环境要求

| 项     | 版本                                      | 检查命令            |
| ----- | --------------------------------------- | --------------- |
| JDK   | **17**（必须，不支持 8/11，Spring Boot 3 最低 17） | `java -version` |
| Maven | 3.9+（也可用自带的 `mvnw`，无需安装）                | `mvn -v`        |
| 浏览器   | Chrome / Edge 任意现代浏览器                   | —               |

> 如果 `java -version` 显示的不是 17，需要设置 `JAVA_HOME` 指向 JDK 17 目录，  
> 并确保 `JAVA_HOME\bin` 在 `PATH` 中。**Maven 用的是 JAVA_HOME 而不是 PATH 里的 java**，  
> 所以两者不一致时会出现「命令行 java 是 17，mvn 编译却报 8」的怪现象。

### 1.2 启动方式（任选一种）

**方式 A：双击 `run.bat`**（Windows，最省事）

会自动用 `mvnw.cmd` 启动，首次运行会下载 Maven 与依赖（约 2~5 分钟，之后走本地缓存）。

**方式 B：命令行**

```bash
# Windows cmd / PowerShell
mvnw.cmd spring-boot:run

# Git Bash / macOS / Linux
./mvnw spring-boot:run

# 已装 Maven 的话也可以
mvn spring-boot:run
```

**方式 C：IDEA**

1. `File → Open` 选择本目录（不要选到子目录），等待 Maven 索引完成；
2. Project SDK 设为 **17**（`File → Project Structure → Project`）；
3. 运行 `com.group5.shop.ShopApplication` 的 `main` 方法。

看到下面这行就成功了：

```
Tomcat started on port 8080 (http)
Started ShopApplication in 2.6 seconds
```

### 1.3 访问

| 地址                                        | 说明         |
| ----------------------------------------- | ---------- |
| <http://localhost:8080/login>             | 卖家登录页      |
| <http://localhost:8080/admin>             | 后台首页（需登录）  |
| <http://localhost:8080/admin/diagnostics> | 基座自检页（需登录） |

**默认账号：`admin` / `admin12345`**

> ⚠️ 首次登录后请立即在「后台首页 → 修改密码」改掉。  
> 改密后重启应用**不会**把密码重置回默认值（种子数据用 `INSERT OR IGNORE`，主键已存在即跳过）。

### 1.4 停止

- 命令行 / `run.bat`：`Ctrl + C`
- IDEA：点红色停止按钮

---

## 2 目录结构

```
online-shopping-group5/
├── pom.xml                          Maven 依赖与构建配置
├── mvnw / mvnw.cmd / .mvn/          Maven Wrapper（免安装 Maven）
├── run.bat                          一键启动脚本
├── docs/
│   ├── API契约-迭代2.md              ★ T2-04 三人并行开发的接口依据
│   └── 数据库设计-迭代2.md           表结构、索引、状态机、迁移方案
├── src/main/java/com/group5/shop/
│   ├── ShopApplication.java         启动类（仅胡成溯可改）
│   ├── common/                      ★ 公共层（仅胡成溯可改，黄/周只读）
│   │   ├── api/                     统一返回体 ApiResponse / PageResult
│   │   ├── constant/                状态常量 + Session 键名
│   │   ├── entity/                  Seller / Category / Product / Intent
│   │   ├── mapper/                  四个 Mapper（继承 BaseMapper，免写 SQL）
│   │   ├── exception/               业务异常 + 错误码 + 全局异常处理
│   │   ├── config/                  MyBatis-Plus 分页 / MVC 拦截器与静态资源
│   │   └── util/                    金额换算、时间、口令码、请求判定
│   ├── auth/                        ★ 账号模块（胡成溯）
│   │   ├── controller/              登录、退出、改密、后台首页、自检页
│   │   ├── service/                 AuthService（登录校验 / 改密规则）
│   │   ├── security/                PasswordHasher（sha256 + 盐）
│   │   └── interceptor/             LoginInterceptor（未登录拦截）
│   ├── product/                     ★ 商品中心（黄程宇，T2-07~10，当前为空占位）
│   └── portal/                      ★ 买家门户（周到，T2-11~14，当前为空占位）
├── src/main/resources/
│   ├── application.yml              端口、数据源、MyBatis-Plus、Session
│   ├── schema/                      ★ T2-03 建表脚本（按模块拆 4 份，幂等，启动按 00→03 自动执行）
│   ├── static/css/style.css         全站样式（迭代 1 原样复用 + 迭代 2 补充）
│   ├── static/images/               占位图
│   └── templates/
│       ├── fragments/layout.html    公共 head / 顶栏片段
│       ├── auth/                    登录页、后台首页、自检页
│       └── error/error.html         统一错误页
└── src/test/java/com/group5/shop/
    ├── AuthFlowTest.java            15 个用例：登录 / 拦截 / 改密 / 退出
    ├── CommonLayerTest.java         6 个用例：增删改查 / 分页 / 队列 / 金额
    └── SchemaIdempotencyTest.java   2 个用例：建表脚本可重复执行
```

### 包隔离规则（分工边界）

| 包                      | 负责人 | 谁能改  |
| ---------------------- | --- | ---- |
| `common/`              | 胡成溯 | 仅胡成溯 |
| `auth/`                | 胡成溯 | 仅胡成溯 |
| `product/`             | 黄程宇 | 仅黄程宇 |
| `portal/`              | 周到  | 仅周到  |
| `ShopApplication.java` | 胡成溯 | 仅胡成溯 |

黄、周可以 `import` 调用 `common/` 里的任何东西，但**不要修改**其中的文件。  
确实需要改动（比如加一个公共工具方法）→ 找组长改，或先在群里说一声。

---

## 3 本迭代已交付内容（T2-01 ~ T2-06）

|   编号  | 任务                        | 交付物                                                  | 验收证据                                          |
| :---: | ------------------------- | ---------------------------------------------------- | --------------------------------------------- |
| T2-01 | 工程骨架、统一返回体、全局异常           | `pom.xml`、包结构、`ApiResponse`、`GlobalExceptionHandler` | `mvn test` 全绿；访问不存在的路径返回统一错误页                 |
| T2-02 | SQLite 数据源 + MyBatis-Plus | `application.yml` 数据源、`MybatisPlusConfig`（分页插件）      | `CommonLayerTest#商品应支持完整的增删改查`、`#分页查询应返回真实总数` |
| T2-03 | 全部建表 SQL                  | `schema/00-base.sql`、`01-buyer.sql`、`02-product.sql`、`03-intent.sql`（5 张表 + 索引 + 种子数据） | `SchemaIdempotencyTest#建表脚本重复执行应无异常且种子数据不翻倍`  |
| T2-04 | API 契约文档                  | `docs/API契约-迭代2.md`                                  | 黄、周按契约并行编码                                    |
| T2-05 | 登录 + 改密 + 登录拦截            | `AuthController`、`AuthService`、`LoginInterceptor`    | `AuthFlowTest` 15 个用例                         |
| T2-06 | 公共 Entity / Mapper        | 4 个实体 + 4 个 Mapper + 状态常量                            | `CommonLayerTest` 全部用例                        |

**运行全部测试**：

```bash
mvnw.cmd test        # Windows
./mvnw test          # Git Bash / macOS
```

预期输出：`Tests run: 23, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS`

---

## 4 数据与运行期文件

| 路径               | 内容                   |     是否进版本库    |
| ---------------- | -------------------- | :-----------: |
| `./data/shop.db` | SQLite 数据库（首次启动自动生成） | ❌ 已 gitignore |
| `./uploads/`     | 后台上传的商品图片            | ❌ 已 gitignore |
| `./target/`      | 编译产物                 | ❌ 已 gitignore |

**想重置数据**：停应用 → 删除 `data/` 目录 → 重启。会自动重建表并写入种子数据。

---

## 5 常见问题

| 现象                                                         | 原因                     | 处理                                                             |
| ---------------------------------------------------------- | ---------------------- | -------------------------------------------------------------- |
| `Web server failed to start. Port 8080 was already in use` | 8080 被占用（常见于上一次没关干净）   | 改 `application.yml` 的 `server.port`，或启动时加 `--server.port=8081` |
| 启动报 `unable to open database file`                         | `data/` 目录被手动删了且无写权限   | 确认项目目录可写；`ShopApplication` 会自动建目录，权限不足时才会失败                    |
| 页面中文乱码                                                     | 文件编码不是 UTF-8           | IDEA 设置 `File → Settings → Editor → File Encodings` 全部 UTF-8   |
| 写入报 `SQLITE_BUSY`                                          | 多个进程同时打开了同一个 `shop.db` | 关掉其他正在运行的本项目实例（一个库只给一个进程用）                                     |
| `mvn` 命令找不到                                                | 未装 Maven 或 PATH 未配     | 用 `mvnw.cmd`（自带，免安装）                                           |
| 编译报 `invalid target release: 17`                           | Maven 用到了旧 JDK         | 检查 `JAVA_HOME` 是否指向 JDK 17，而不是 PATH 里的 java                    |
| 登录一直提示「用户名或密码错误」                                           | 密码已被改过                 | 删 `data/` 重置回 `admin/admin12345`                               |

---

## 6 与需求的差异说明（需要向老师报备的两处）

|  #  | 差异                   | 原因                                                                                      |
| :-: | -------------------- | --------------------------------------------------------------------------------------- |
|  1  | 增加了「退出登录」（`/logout`） | 迭代 1 的 FR-01-6 写的是「本版本不提供退出登录」。三人联调与课堂演示时频繁切换账号，没有退出入口只能关浏览器。属**增量**功能，不放宽任何原有约束。       |
|  2  | 允许卖家编辑本人商品           | 迭代 1 的决策 D3 是「发布后不可修改」，迭代 2 的 T2-10 明确要求「可进入编辑」。**以迭代 2 任务表为准**，视为需求变更，需周到同步修订规格书 v1.2。 |

---

## 7 分支与协作

| 分支             | 负责人 | 合并方式                        |
| -------------- | --- | --------------------------- |
| `main`         | —   | 只接受经组长 review 的合并           |
| `feat-base`    | 胡成溯 | 自测通过 → 提 PR → 组长自查后合并       |
| `feat-product` | 黄程宇 | 自测通过 → 提 PR → 组长 review 后合并 |
| `feat-portal`  | 周到  | 自测通过 → 提 PR → 组长 review 后合并 |

提交信息格式：`[模块] 做了什么`，例如 `[product] 新增多商品发布页与图片校验`。

**联调前必读**：`docs/API契约-迭代2.md` 第 6 节「联调检查清单」，12 项逐条打勾。
