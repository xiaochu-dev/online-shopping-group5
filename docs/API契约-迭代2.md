# 第 5 组 在线购物系统 · 迭代 2 API 契约

| 项目 | 内容 |
|------|------|
| 文档名称 | 迭代 2 API 契约（T2-04） |
| 项目名称 | 在线购物系统（挑战升级） |
| 迭代期次 | 第 2 次迭代（Sprint 2） |
| 文档版本 | v2.1（2026-10-22按已合入代码同步） |
| 编制人 | 胡成溯（组长） |
| 编制日期 | 2026-10-10 |
| 适用范围 | 胡成溯（`auth/`）、黄程宇（`product/`）、周到（`portal/`） |
| 依据 | v4工作任务表、iter2-java合并提交671ae63及当前Controller/Service/schema |

> **本文件的作用**：三人并行开发时，唯一会产生「联调返工」的就是接口不一致。
> 本文把每个路由的 **URL / 方法 / 入参 / 出参 / 状态码 / 重定向目标 / 模板名** 全部写死。
> 编码阶段任何一方觉得契约有问题，**先改本文档再改代码**，不要口头约定。

---

## 1 通用约定

### 1.1 基础信息

| 项 | 约定 |
|----|------|
| 服务地址 | `http://localhost:8080`（`server.port` 可改） |
| 字符编码 | 全链路 UTF-8（页面、表单、SQL 脚本、日志） |
| 页面表单提交 | `Content-Type: application/x-www-form-urlencoded`，字段名区分大小写 |
| 文件上传 | `Content-Type: multipart/form-data`，字段名 `images`（多文件） |
| 模板引擎 | Thymeleaf，模板放 `src/main/resources/templates/` |
| 静态资源 | `/css/**`、`/images/**` → `classpath:/static/`；`/uploads/**` → 项目根 `./uploads/` |

### 1.2 两类请求与两种出口

拦截器与全局异常处理根据URI及请求头区分页面/接口请求；当前商品与门户Controller主要返回页面或redirect视图。请求头不会把所有成功页面自动变成JSON：

| 类型 | URI 特征 | 未登录时 | 异常时 |
|------|---------|---------|--------|
| 页面请求 | 其余所有路径 | 卖家受保护页302跳/login；买家受保护页跳/buyer/login，均可带回跳地址 | 渲染 `error/error.html`，HTTP 状态按错误码 |
| 接口请求 | 以 `/api/` 开头，或带 `Accept: application/json`，或带 `X-Requested-With: XMLHttpRequest` | `401` + JSON | `ApiResponse` JSON |

判定逻辑已封装在 `com.group5.shop.common.util.RequestUtils#isApiRequest`，**不要各写一套**。

### 1.3 认证与会话

卖家实际cookie为`SHOP_SESSION`（HttpSession），买家为独立签名`BUYER_SESSION`；不把任务表的SELLER_SESSION当作已配置名称。买家注册/登录接口详见账号模块分节。

| 项 | 约定 |
|----|------|
| 会话方式 | 服务端 Session（`HttpSession`），浏览器持有 `SHOP_SESSION` Cookie |
| Cookie 属性 | `HttpOnly=true`、`SameSite=Lax`、`Path=/` |
| 有效期 | 7 天（`server.servlet.session.timeout: 7d`） |
| 受保护范围 | `/admin`、`/admin/**`（由 `LoginInterceptor` 统一拦截，**不要在各 Controller 里重复判断**） |
| 公开范围 | 卖家/买家登录注册、商品浏览；提交意向与我的订单须买家登录 |
| Session 中的键 | `LOGIN_SELLER`（String 用户名）、`LOGIN_SELLER_ID`（Long 卖家 id） |

**取当前登录用户的正确写法**（黄程宇做「我的商品」时必须用这个，否则能改到别人的商品）：

```java
HttpSession session = request.getSession(false);
Long sellerId = (Long) session.getAttribute(SessionKeys.LOGIN_SELLER_ID);
```

> 也可以直接 `@SessionAttribute(SessionKeys.LOGIN_SELLER_ID) Long sellerId` 作为方法参数，
> 拦截器已保证进到 Controller 时一定存在。

### 1.4 统一返回体（仅接口请求）

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "timestamp": 1760000000000
}
```

- `code == 0` 表示成功，非 0 见 1.5 错误码表；
- `data` 在失败时固定为 `null`；
- 由 `com.group5.shop.common.api.ApiResponse` 生成，用静态方法 `ApiResponse.ok(data)` / `ApiResponse.fail(code, msg)`。

### 1.5 错误码表

| code | 含义 | HTTP 状态 | 使用方 |
|------|------|:---:|--------|
| `0` | 成功 | 200 | 全部 |
| `40001` | 参数不合法 | 400 | 全部（通用校验失败） |
| `40101` | 用户名或密码错误 | 401 | 胡成溯 |
| `40102` | 未登录 / 会话过期 | 401 | 拦截器 |
| `40103` | 原密码错误 | 400 | 胡成溯 |
| `40104` | 新密码长度不足 8 位 | 400 | 胡成溯 |
| `40105` | 账号不存在 | 404 | 胡成溯 |
| `40401` | 资源不存在 | 404 | 全部 |
| `50001` | 服务器内部错误 | 500 | 全部 |

**新增错误码的占位段**（避免撞号）：黄程宇用 `410xx`，周到用 `420xx`。
新增时请同步更新本表，且**不得修改已有值**。

> HTTP 状态码以 `ErrorCode#getHttpStatus()` 为准，**不要**用「业务码 ÷ 100」反推
> （例如 `40103` 是 400，除出来会是 401）。

### 1.6 分页对象

分页接口的 `data` 统一为：

```json
{
  "records": [],
  "total": 37,
  "page": 1,
  "size": 12,
  "totalPages": 4,
  "hasPrev": false,
  "hasNext": true
}
```

Java 侧用 `com.group5.shop.common.api.PageResult`，由 MP 的 `IPage` 转换：

```java
Page<Product> raw = productMapper.selectPage(new Page<>(pageNo, size), wrapper);
PageResult<Product> result = PageResult.of(raw);
```

**分页参数约定**：`page` 从 **1** 开始；`size` 默认 12，上限 100（插件已配 `maxLimit=100`）；
页码越界返回空列表而不是跳回第一页。

### 1.7 页面反馈

商品和分类表单使用redirect视图与Flash的msg/error；门户表单使用redirect和URL消息参数。卖家登录失败直接渲染登录页。不能笼统写所有表单失败都重定向，也不能把Flash消息误写成必有URL参数。当前redirect默认302，商品页面GET返回200。

### 1.8 数据口径

| 数据 | 存储口径 | 页面/接口口径 |
|------|---------|--------------|
| 金额 | `price_cents`，单位「分」，`long` | 展示用 `Product#getPriceYuan()`（如 `9.90`）；入参用 `MoneyUtils.yuanToCents(String)` |
| 时间 | 毫秒时间戳，`long` | 展示用 `TimeUtils.formatDateTime(long)` |
| 商品状态 | `on_sale` / `frozen` / `off_shelf`；`delisted`兼容历史 | `Product#getStatusLabel()`处理off_shelf，兼容旧状态标签 |
| 意向状态 | `pending` / `trading` / `success` / `failed` / `cancelled` | `IntentStatus.labelOf()` |
| 分类状态 | `enabled` / `disabled` | `CategoryStatus.labelOf()` |
| 图片路径 | 相对路径字符串，如 `uploads/a1b2.png`、`images/placeholder.png` | `<img th:src="@{'/' + ${p.imagePath}}">` |

> 本迭代已实际使用冻结、交易结果与库存归零下架，当前状态以第3节为准。

---

## 2 已实现接口 · 卖家账号（胡成溯；客户接口另见账号分节）

### 2.1 `GET /login` 登录页

| 项 | 内容 |
|----|------|
| 权限 | 公开 |
| 入参 | `redirect`（可选，登录成功后跳回的站内地址） |
| 成功 | `200`，渲染 `auth/login.html` |
| 已登录 | `302` → `/admin` |

### 2.2 `POST /login` 提交登录

| 项 | 内容 |
|----|------|
| 权限 | 公开 |
| 入参 | `username`（必填）、`password`（必填）、`redirect`（可选） |
| 成功 | `302` → `redirect` 参数值；缺省 `/admin`；同时 `Set-Cookie: SHOP_SESSION=...` |
| 失败 | `200`，重新渲染 `auth/login.html`，model 中 `error="用户名或密码错误"` |
| 安全策略 | 账号不存在与密码错误返回**同一句提示**（防用户名枚举）；登录成功后轮换 SessionId（防会话固定） |

```bash
curl -c cookie.txt -X POST http://localhost:8080/login \
     -d "username=admin&password=admin12345"
```

### 2.3 `GET /logout` 退出登录

| 项 | 内容 |
|----|------|
| 权限 | 公开 |
| 行为 | `session.invalidate()` |
| 响应 | `302` → `/login` |

> 迭代 1 的 FR-01-6 口径是「本版本不提供退出登录」。迭代 2 补上，
> 原因：三人联调与课堂演示时频繁切换账号，没有退出入口只能关浏览器，效率太低。
> 该功能为**增量**，不影响 FR-01 其余条款的验收。

### 2.4 `POST /admin/password` 修改密码

| 项 | 内容 |
|----|------|
| 权限 | 需登录（`/admin/**`） |
| 入参 | `oldPassword`、`newPassword`、`confirmPassword` |
| 成功 | `302` → `/admin?msg=密码修改成功，下次登录请使用新密码` |
| 失败 | `302` → `/admin?error=<具体原因>` |

校验顺序与提示语：

| 顺序 | 规则 | 失败提示 |
|:--:|------|---------|
| 1 | 两次新密码一致（Controller 层） | 两次输入的新密码不一致 |
| 2 | 原密码正确 | 原密码错误 |
| 3 | 新密码长度 ≥ 8 | 新密码长度不得少于 8 位 |
| 4 | 新密码 ≠ 原密码 | 新密码不能与原密码相同 |

> 用户名一律取自 Session，**绝不接受表单传入**，否则改一下隐藏域就能改别人的密码。

### 2.5 `GET /admin` 后台首页

| 项 | 内容 |
|----|------|
| 权限 | 需登录 |
| 入参 | `msg`、`error`（可选，用于显示反馈） |
| 成功 | `200`，渲染 `auth/admin-home.html` |
| Model | `username`、`sellerCreatedAt`、`msg`、`error`、`categoryCount`、`productTotal`、`productOnSale`、`productDelisted`、`intentPending`、`intentTotal` |

### 2.6 `GET /admin/diagnostics` 基座自检页

| 项 | 内容 |
|----|------|
| 权限 | 需登录 |
| 用途 | 现场证明「表建对了、数据源通了」，T2-02 / T2-03 / T2-06 的验收证据 |
| Model | `datasourceUrl`、`dbFileExists`、`javaVersion`、`osName`、`tables`（表名 + 行数 + 列结构）、`categories` |

---

## 3 已实现接口 · 商品中心（黄程宇，T2-08～T2-12）

Controller位于`product/controller/`，卖家后台路由继承`LoginInterceptor`。商品查询和操作使用Session中的卖家编号，不接受客户端指定sellerId。表单使用redirect视图和Flash消息，当前默认302；页面GET成功200。

| 方法 | 路径 | 入参 | 响应/模型 |
| --- | --- | --- | --- |
| GET | /admin/products | status选填 | product/list；products、selectedStatus |
| GET | /admin/products/new | 无 | product/publish；tree |
| GET | /admin/products/publish | 无 | 同发布页 |
| POST | /admin/products | name、price、categoryId、stock、description、images、imageOrder、primaryIndex | 成功回/admin/products；业务失败回/admin/products/new |
| POST | /admin/products/{id}/stock | stock | 调整库存后回/admin/products |
| POST | /admin/products/{id}/off-shelf | 无 | 手动下架后回/admin/products |
| POST | /admin/products/{id}/relist | 无 | 重新上架后回/admin/products |
| GET | /admin/categories | 无 | product/categories；categories |
| POST | /admin/categories | name、parentId选填、sortNo默认0 | 新增分类后回/admin/categories |
| POST | /admin/categories/{id}/status | enabled布尔值 | 启停后回/admin/categories |
| POST | /admin/categories/{id}/delete | 无 | 仅删除无子类、无商品引用的分类 |

### 3.1 发布与图片字段

`name`去空格后须为1～120字符；`price`为大于0、最多两位小数的元金额，存为整数分；`categoryId`必填且必须是启用的二级分类，父级也须启用；`stock`为非负整数；`description`可空、最多100000字符，保存前过滤。

`images`为multipart多文件字段，最多9张；单张上限5×1024×1024字节，JPG/JPEG/PNG扩展名与文件头须匹配，并完成解码，像素数最多4000万。未选文件使用占位图；具名空文件被拒。全部校验成功才写盘，数据库事务失败清理本次新文件。

`imageOrder`为0起始的原文件下标完整排列；`primaryIndex`为原文件下标。发布前可排序、选择主图；发布后没有改图接口。`product_image`保存file_path、sort_no、is_primary；image_path同步封面。图集返回封面优先，再按sort_no排序。Servlet单文件接收上限6MB、请求50MB，业务层仍按单张5MiB限制。

### 3.2 分类与状态

只允许两级；同父级同名被拒，不同父级可同名。停用一级后，其子类从买家分类树隐藏。有子类或商品引用的分类不能删除，只能停用。当前没有分类改名/重排接口。

| 动作 | 库存变化 | 状态和队列 |
| --- | --- | --- |
| 发布商品 | 指定非负整数 | 大于0为on_sale；0为off_shelf/auto |
| 提交意向 | 不扣 | 要求登录、在售且有库存；进入pending |
| 确认队首交易 | 不扣 | 意向trading，商品frozen |
| 标记成功 | 原子扣1 | 意向success；库存归零则off_shelf/auto并将pending置failed，否则release恢复在售 |
| 标记失败 | 不扣 | 意向failed或重回pending队尾；商品通过release恢复，手动下架保持下架 |
| 手动下架 | 不扣 | on_sale或frozen均可变为off_shelf/manual |
| 调整库存 | 改为输入值 | 为0时off_shelf/auto并结束pending；有交易时禁止直接调为0 |
| 补货后重新上架 | 不扣 | 要求库存>0；无交易恢复on_sale，有交易恢复frozen |

`release()`只处理frozen，不能覆盖已下架状态。`delisted`仅兼容历史商品，不是当前发布/下架入口写入的主状态。商品级`traded_at/trade_result`为兼容字段，本次交易结果与时间记录在intent。

### 3.3 功能边界与跨模块出口

没有商品编辑页、改名/改价/改图或删除商品接口；只支持库存调整与上下架。旧稿的`/{id}/edit`、`POST /admin/products/{id}`、`/delist`、`/onsale`、`POST /admin/categories/{id}`均不是当前路由。

CategoryQueryService提供分类树；ProductQueryService提供在售查询；ProductDetailService提供详情并实现门户的ProductPort（freeze/release/deduct/stockOf/imagesOf）；StockService负责库存。IntentQueuePort由portal中的ProductQueueAdapter实现，转调队列owner能力，商品模块不写intent表。原商品探针及无限库存降级已移除，卖家上下文适配仍保留。

富文本允许段落、加粗、列表、HTTP/HTTPS图片与链接；iframe仅允许HTTPS的player.bilibili.com/player.html、www.youtube.com/embed/、www.youtube-nocookie.com/embed/，精确核对域名并设置sandbox。script、事件属性和其他iframe被过滤。实际播放证据仍待补。

详细校验与迁移见[商品中心契约](契约-商品中心-迭代2.md)。

## 4 已实现接口 · 买家门户与交易（周到，T2-13～T2-18）

浏览公开，下单和我的订单必须登录；卖家交易操作使用已有后台鉴权。

| 方法 | 路径 | 入参/模型与响应 |
| --- | --- | --- |
| GET | / | page默认1、size默认12上限100、categoryId、keyword；模型pageResult/categoryTree/categoryNames/categoryId/keyword/msg/error/buyerName，portal/product-list |
| GET | /products/{id} | 只接受on_sale/frozen；模型product/view/queueSize/loggedIn/buyerName/msg/error，portal/product-detail；下架/不存在返回404 |
| POST | /products/{id}/intents | tradeAddress选填，缺省取买家默认地点，两者都空拒绝；姓名电话取登录资料快照；redirect回详情显示消息 |
| GET | /my/orders | 买家已结束订单，page/size；模型pageResult/buyerName，portal/my-orders |
| GET | /admin/intents | 卖家商品及未结束意向数；portal/seller-products |
| GET | /admin/intents/{productId} | 当前商品队列；portal/seller-intents |
| POST | /admin/intents/{intentId}/confirm | 只确认队首，商品冻结；redirect回队列 |
| POST | /admin/intents/{intentId}/success | 交易成功时才扣1；redirect回队列 |
| POST | /admin/intents/{intentId}/fail | requeue布尔值、reason选填；失败作废或重排队尾；redirect回队列 |
| POST | /intents/cancel | tokenCode；仅旧码兼容，买家页面无入口，redirect回列表 |

一级筛选包含其二级分类；关键词匹配名称和描述。下单不扣库存、不生成口令码，同买家同商品最多1条pending/trading。失败/成功结束后可再次购买。冻结时商品详情可查看，按钮不可下单；已下架不进入列表和详情。我的订单只显示success/failed，按finished_at倒序分页，排队/交易中不进入历史。

页面POST返回redirect视图，当前默认302；不能把注释中的303当作已配置状态。客户账号接口和客户管理见[账号与客户契约](契约-账号与客户-迭代2.md)。

## 5 JSON响应能力与实现边界

common提供ApiResponse、PageResult和异常返回能力；当前产品/门户以页面路由为主。旧稿列出的`/api/products`、`/api/intents`等JSON产品接口没有Controller实现，不作为已交付接口或本次承诺功能。

## 6 联调检查清单

从iter2-java获取代码。以下为检查清单，不是本次重新执行记录；已有83项测试与浏览器证据见商品中心测试记录。

| 检查 | 通过依据 |
| --- | --- |
| 卖家登录、分类树、发布三个商品 | 只能选启用的二级类；首页可找到商品 |
| 9图排序/主图/格式边界 | 商品图集正确，第10张、损坏图和超限被拒 |
| 未登录浏览与下单拦截 | 浏览公开，下单跳买家登录 |
| 不同买家排队，同买家重复提交 | 下单不扣库存，重复未结束意向被拒 |
| 确认交易→成功/失败 | 整件冻结，成功扣1，失败不扣，可作废或重排 |
| 最后一件成交 | 自动下架，pending批量失败，订单结果可查 |
| 补货、手动下架与重新上架 | 补货不自动上架，已有交易不被重新上架绕过 |
| 富文本与视频 | 安全过滤正确；外站视频真实播放仍待验证 |
| Java17环境与旧库升级 | 团队环境联调、订单迁移范围另行核对 |

## 7 变更记录

| 版本 | 日期 | 变更内容 | 修订人 |
|------|------|---------|--------|
| v1.0 | 2026-10-10 | 首版：通用约定、账号模块 6 个接口、商品模块 10 个接口、门户模块 4 个接口、可选 JSON 接口、联调清单 | 胡成溯 |

| v2.1 | 2026-10-22 | 按671ae63同步两级分类、库存、多图、富文本、实际路由和门户联动；旧方案不再作为当前契约 | 本次团队文档维护 |

> 变更规则：任何一方修改路由或字段名，必须先更新本表并在小组群公告，
> 对方确认后再改代码。**口头约定不算数。**
