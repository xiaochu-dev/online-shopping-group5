# 第 5 组 在线购物系统 · 迭代 2 API 契约

| 项目 | 内容 |
|------|------|
| 文档名称 | 迭代 2 API 契约（T2-04） |
| 项目名称 | 在线购物系统（挑战升级） |
| 迭代期次 | 第 2 次迭代（Sprint 2） |
| 文档版本 | v1.0 |
| 编制人 | 胡成溯（组长） |
| 编制日期 | 2026-10-10 |
| 适用范围 | 胡成溯（`auth/`）、黄程宇（`product/`）、周到（`portal/`） |
| 依据 | 《迭代 2 工作任务表》v1.0、《03-架构设计与数据库表结构》v1.0 |

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
| 文件上传 | `Content-Type: multipart/form-data`，字段名 `image` |
| 模板引擎 | Thymeleaf，模板放 `src/main/resources/templates/` |
| 静态资源 | `/css/**`、`/images/**` → `classpath:/static/`；`/uploads/**` → 项目根 `./uploads/` |

### 1.2 两类请求与两种出口

整个系统有两条平行的响应通道，**必须按 URI 前缀区分**，否则前端会拿到一段 HTML 而报 `Unexpected token <`：

| 类型 | URI 特征 | 未登录时 | 异常时 |
|------|---------|---------|--------|
| 页面请求 | 其余所有路径 | `302` 跳 `/login?redirect=原地址` | 渲染 `error/error.html`，HTTP 状态按错误码 |
| 接口请求 | 以 `/api/` 开头，或带 `Accept: application/json`，或带 `X-Requested-With: XMLHttpRequest` | `401` + JSON | `ApiResponse` JSON |

判定逻辑已封装在 `com.group5.shop.common.util.RequestUtils#isApiRequest`，**不要各写一套**。

### 1.3 认证与会话

| 项 | 约定 |
|----|------|
| 会话方式 | 服务端 Session（`HttpSession`），浏览器持有 `SHOP_SESSION` Cookie |
| Cookie 属性 | `HttpOnly=true`、`SameSite=Lax`、`Path=/` |
| 有效期 | 7 天（`server.servlet.session.timeout: 7d`） |
| 受保护范围 | `/admin`、`/admin/**`（由 `LoginInterceptor` 统一拦截，**不要在各 Controller 里重复判断**） |
| 公开范围 | `/login`、`/logout`、`/`、`/products/**`、`/intents/**`、`/api/**`（视具体接口） |
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

### 1.7 页面反馈约定（重要）

所有**表单提交**统一使用 Post/Redirect/Get，成功与失败都重定向：

| 结果 | 重定向目标 | 页面表现 |
|------|-----------|---------|
| 成功 | `?msg=<URL 编码的中文>` | 顶部绿色提示条 |
| 失败 | `?error=<URL 编码的中文>` | 顶部红色提示条 |

Java 侧：

```java
return "redirect:/admin/products?msg=" + UriUtils.encode("商品发布成功", StandardCharsets.UTF_8);
```

**为什么失败也重定向**：避免用户按 F5 重复提交表单（重复发布商品 / 重复提交意向）。
代价是失败后表单内容不保留，与迭代 1 口径一致，本迭代不做「回填表单」的增强。

### 1.8 数据口径

| 数据 | 存储口径 | 页面/接口口径 |
|------|---------|--------------|
| 金额 | `price_cents`，单位「分」，`long` | 展示用 `Product#getPriceYuan()`（如 `9.90`）；入参用 `MoneyUtils.yuanToCents(String)` |
| 时间 | 毫秒时间戳，`long` | 展示用 `TimeUtils.formatDateTime(long)` |
| 商品状态 | `on_sale` / `delisted` / `frozen` | `ProductStatus.labelOf()` → 在售 / 已下架 / 交易中 |
| 意向状态 | `pending` / `trading` / `success` / `failed` / `cancelled` | `IntentStatus.labelOf()` |
| 分类状态 | `enabled` / `disabled` | `CategoryStatus.labelOf()` |
| 图片路径 | 相对路径字符串，如 `uploads/a1b2.png`、`images/placeholder.png` | `<img th:src="@{'/' + ${p.imagePath}}">` |

> 迭代 2 **只写** `on_sale` 与 `delisted`；`frozen` 与意向的 `trading/success/failed` 由迭代 3 使用，
> 表结构已预先放开，届时不需要改表。

---

## 2 已实现接口 · 账号模块（胡成溯，T2-05）

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

## 3 待实现接口 · 商品中心（黄程宇，T2-07 ~ T2-10）

> 全部位于 `com.group5.shop.product` 包，模板放 `templates/product/`，自动受登录拦截器保护。
> 全部路由**必须**按 `sellerId` 过滤，防止看到/改到别人的商品。

### 3.1 路由总表

| 编号 | 方法 | 路径 | 功能 | 成功响应 |
|:--:|:--:|------|------|---------|
| P-1 | GET | `/admin/products` | 我的商品列表 | `200` `product/my-products.html` |
| P-2 | GET | `/admin/products/new` | 发布页 | `200` `product/product-form.html` |
| P-3 | POST | `/admin/products` | 提交发布 | `303` `/admin/products?msg=商品发布成功` |
| P-4 | GET | `/admin/products/{id}/edit` | 编辑页 | `200` `product/product-form.html` |
| P-5 | POST | `/admin/products/{id}` | 保存编辑 | `303` `/admin/products?msg=商品已保存` |
| P-6 | POST | `/admin/products/{id}/delist` | 下架 | `303` `/admin/products?msg=商品已下架` |
| P-7 | POST | `/admin/products/{id}/onsale` | 重新上架 | `303` `/admin/products?msg=商品已上架` |
| P-8 | GET | `/admin/categories` | 分类管理页 | `200` `product/categories.html` |
| P-9 | POST | `/admin/categories` | 新增分类 | `303` `/admin/categories?msg=分类已新增` |
| P-10 | POST | `/admin/categories/{id}` | 修改分类 | `303` `/admin/categories?msg=分类已更新` |

### 3.2 P-1 `GET /admin/products` 我的商品列表

| 项 | 内容 |
|----|------|
| 入参 | `status`（可选：`on_sale` / `delisted`，不传为全部） |
| 查询 | `WHERE seller_id = 当前登录 id [AND status = ?] ORDER BY published_at DESC, id DESC` |
| Model | `products`（`List<Product>`）、`categoryNames`（`Map<Long,String>`）、`status`、`msg`、`error` |
| 模板 | `product/my-products.html` |

### 3.3 P-3 `POST /admin/products` 提交发布（核心）

| 入参 | 类型 | 必填 | 校验规则 | 失败提示 |
|------|------|:--:|---------|---------|
| `name` | text | 是 | 去空格后非空 | 商品名称不能为空 |
| `description` | textarea | 否 | 原样保存，允许空串 | — |
| `price` | text | 是 | 匹配 `^\d{1,9}(\.\d{1,2})?$` 且 > 0，转「分」存储 | 价格不能为空 / 价格格式非法，最多两位小数 / 价格必须大于 0 |
| `categoryId` | select | 否 | 若传值，必须存在于 `category` 且 `status='enabled'` | 所选分类不存在或已禁用 |
| `image` | file | 否 | 仅 JPG/PNG（扩展名 + 文件头魔数双校验），≤ 5MB，空文件拒绝 | 见下表 |

图片校验细则（与迭代 1 的 FR-02-6 完全一致）：

| 检查项 | 判定 | 提示 |
|--------|------|------|
| 空文件 | `size == 0` | 上传的图片文件为空，请重新选择图片 |
| 大小 | `> 5 * 1024 * 1024` | 图片大小不能超过 5MB |
| 扩展名 | 不在 `.jpg/.jpeg/.png` | 图片仅支持 JPG/PNG 格式 |
| 魔数 | JPEG `FF D8 FF` / PNG `89 50 4E 47 0D 0A 1A 0A` 与扩展名不符 | 图片内容与扩展名不符，仅支持真实的 JPG/PNG 文件 |
| 未上传 | — | 数据库写 `images/placeholder.png` |

落盘规则：文件存 `./uploads/<UUID>.<ext>`，数据库 `image_path` 存 `uploads/<UUID>.<ext>`。

写入字段：`seller_id`（当前登录者）、`category_id`、`name`、`description`、`image_path`、
`price_cents`、`status='on_sale'`、`published_at=updated_at=当前毫秒`、`traded_at=NULL`、`trade_result=NULL`。

> **与迭代 1 的差异**：迭代 1 有「同一时间仅允许一件在售」的约束（FR-02-1/2），
> 迭代 2 **取消该约束**，连续发布多件都必须成功。规格书 v1.2 由周到同步修订。

### 3.4 P-5 `POST /admin/products/{id}` 保存编辑

| 项 | 内容 |
|----|------|
| 入参 | 同 P-3；`image` 为空表示**保留原图** |
| 前置校验 | 商品存在且 `seller_id == 当前登录 id`，否则 `303` → `/admin/products?error=无权操作该商品` |
| 写入 | 除 `seller_id`、`published_at`、`status`、`traded_at` 外全部更新；`updated_at = now` |

> 迭代 1 的决策 D3 是「商品发布后不可修改」，迭代 2 的 T2-10 明确要求「可进入编辑」，
> **以迭代 2 任务表为准**，视为需求变更（已记录在《数据库设计-迭代2》变更记录）。

### 3.5 P-6 / P-7 上下架

| 接口 | 前置状态 | 后置状态 | 附带字段 |
|------|---------|---------|---------|
| P-6 下架 | `on_sale` | `delisted` | `traded_at = now`、`trade_result = 'manual_delist'`、`updated_at = now` |
| P-7 上架 | `delisted` | `on_sale` | `traded_at = NULL`、`trade_result = NULL`、`updated_at = now` |

状态不符合时：`303` → `/admin/products?error=当前状态不允许该操作`。
同样必须校验 `seller_id` 归属。

### 3.6 P-8 ~ P-10 分类维护

| 接口 | 入参 | 校验 |
|------|------|------|
| P-9 新增 | `name`、`sortNo`（可选，默认 0） | 名称去空格后非空；同名已存在 → `分类名称已存在` |
| P-10 修改 | `name`、`sortNo`、`status`（`enabled`/`disabled`） | 同上；分类不存在 → `分类不存在` |

> 分类下拉框直接用 `categoryMapper.selectEnabled()`（已按 `sort_no` 升序）。
> **禁用分类不会影响已归入该分类的历史商品。**

### 3.7 商品模块可直接复用的基座能力

| 用途 | 用法 |
|------|------|
| 分页（如需） | 注入 `ProductMapper`，`selectPage(new Page<>(p, s), wrapper)`，插件已注册 |
| 价格校验 | `MoneyUtils.yuanToCents(price)`，非法输入直接抛 `BusinessException` |
| 状态常量 | `ProductStatus.ON_SALE` / `DELISTED` / `labelOf` / `cssOf` |
| 分类下拉 | `categoryMapper.selectEnabled()` |
| 提示语渲染 | `?msg=` / `?error=`，模板已内置 `<div class="banner banner-ok|banner-error">` 样式 |
| 图片占位 | `images/placeholder.png`（已随工程提供） |

---

## 4 待实现接口 · 买家门户（周到，T2-11 ~ T2-14）

> 位于 `com.group5.shop.portal` 包，模板放 `templates/portal/`，**全部公开、不加登录拦截**。

### 4.1 路由总表

| 编号 | 方法 | 路径 | 功能 | 成功响应 |
|:--:|:--:|------|------|---------|
| B-1 | GET | `/` | 商品列表（分页 + 分类 + 搜索） | `200` `portal/product-list.html` |
| B-2 | GET | `/products/{id}` | 商品详情 | `200` `portal/product-detail.html` |
| B-3 | POST | `/products/{id}/intents` | 提交购买意向 | `200` 渲染详情页，model 带 `tokenCode` |
| B-4 | POST | `/intents/cancel` | 凭口令码撤销 | `303` `/` + `msg`/`error` |

### 4.2 B-1 `GET /` 商品列表

| 入参 | 默认 | 说明 |
|------|------|------|
| `page` | 1 | 从 1 开始，越界返回空列表 |
| `size` | 12 | 上限 100 |
| `categoryId` | 无 | 按分类筛选，不传为全部 |
| `keyword` | 无 | 按 `name LIKE %kw% OR description LIKE %kw%` 模糊匹配 |

查询条件（**只展示在售**）：

```sql
WHERE status = 'on_sale'
  [AND category_id = ?]
  [AND (name LIKE ? OR description LIKE ?)]
ORDER BY published_at DESC, id DESC
```

Model：`pageResult`（`PageResult<Product>`）、`categories`（`List<Category>`）、`categoryId`、`keyword`、`msg`、`error`。
模板需处理：空结果 → 「没有找到符合条件的商品」；无筛选结果 → 「暂无商品在售」。

> 模糊匹配请用 MP 的 `.like()`，它会自动拼 `%kw%`；**不要**手拼 SQL 字符串（注入风险）。

### 4.3 B-2 `GET /products/{id}` 商品详情

| 项 | 内容 |
|----|------|
| 前置 | 商品存在且 `status = 'on_sale'` |
| 不存在/已下架 | 抛 `BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架")` → 自动渲染错误页 |
| Model | `product`、`error`、`tokenCode`（首次进入为 null） |

### 4.4 B-3 `POST /products/{id}/intents` 提交意向（核心）

| 入参 | 必填 | 校验 |
|------|:--:|------|
| `buyerName` | 是 | 去空格后非空 |
| `phone` | 是 | 去空格后非空；**不校验格式、不按号码去重** |

校验与写入：

| 顺序 | 检查 | 失败表现 |
|:--:|------|---------|
| 1 | 商品存在且 `status = 'on_sale'` | 渲染详情页，`error = 商品不存在或已下架` |
| 2 | 姓名、电话非空 | 渲染详情页，`error = 姓名与联系电话均为必填项` |
| 3 | 生成口令码写入 `intent` | — |

写入字段：`product_id`、`buyer_name`、`phone`、`token_code = TimeUtils.randomTokenCode()`、
`status = 'pending'`、`created_at = now`。

**成功响应**：`200` 直接渲染 `portal/product-detail.html`（不是重定向），model 中 `tokenCode=<32 位码>`。
页面必须在显眼位置一次性展示口令码并提示「请自行保存，丢失后无法撤销」。

> 为什么这里不用 303 重定向：口令码只在这一次响应中展示，重定向会把它变成 URL 参数
> （出现在浏览器历史、服务器日志里，等于公开）。迭代 1 的 FR-03-8「当场一次性显示」就是这个口径。

**同号码重复提交**：本接口不查重、不合并，每次调用都插入独立一行。
第 3 条遗留问题 C-3 由此清偿——三次提交得到三个互不干扰的口令码。

### 4.5 B-4 `POST /intents/cancel` 撤销意向

| 入参 | 必填 | 说明 |
|------|:--:|------|
| `tokenCode` | 是 | 32 位十六进制口令码 |

| 情况 | 响应 |
|------|------|
| 口令码存在且状态为 `pending` | 更新 `status='cancelled'`，`303` → `/?msg=口令码 xxx 对应的意向已撤销` |
| 口令码不存在 | `303` → `/?error=口令码无效，或该意向不可撤销（仅排队中的意向可撤销）` |
| 口令码存在但已非 `pending` | 同上（提示语一致，不泄露「这个码存在但状态不对」） |

> 队列位次不落库，撤销后其余意向自动前移，**不需要**批量更新 position 字段。

### 4.6 门户模块可直接复用的基座能力

| 用途 | 用法 |
|------|------|
| 队列查询 | `intentMapper.selectQueue(productId)`，已按 `created_at ASC, id ASC` 排好 |
| 口令码反查 | `intentMapper.selectByTokenCode(code)` |
| 生成口令码 | `TimeUtils.randomTokenCode()`（32 位十六进制） |
| 分页 | `productMapper.selectPage(...)` + `PageResult.of(...)` |
| 状态常量 | `ProductStatus`、`IntentStatus`（含 `cancellable(status)` 判断） |
| 价格展示 | `Product#getPriceYuan()` |

---

## 5 可选 JSON 接口（非验收必需）

以下接口仅当页面需要 ajax 局部刷新时才实现，**不影响 T2-07 ~ T2-14 的验收**。
时间不足时可以直接跳过，先保证页面链路跑通。

| 方法 | 路径 | 入参 | 返回 |
|:--:|------|------|------|
| GET | `/api/products` | `page`、`size`、`categoryId`、`keyword` | `ApiResponse<PageResult<Product>>` |
| GET | `/api/products/{id}` | — | `ApiResponse<Product>` |
| POST | `/api/products` | JSON：`name`、`description`、`price`（元，字符串）、`categoryId` | `ApiResponse<Product>` |
| POST | `/api/intents` | JSON：`productId`、`buyerName`、`phone` | `ApiResponse<{ "tokenCode": "..." }>` |
| POST | `/api/intents/cancel` | JSON：`tokenCode` | `ApiResponse<Void>` |

约定：成功 `200` + `code=0`；业务失败 `400`（或 `401`/`404`）+ 对应 `code`；
未登录 `401` + `code=40102`。**不要**在 `/api/**` 里返回重定向。

---

## 6 联调检查清单

联调日（10-20）按此表逐条过，每项打勾才算通过。

| # | 检查项 | 验证方式 |
|:--:|--------|---------|
| 1 | 三模块都能在同一个 `mvn spring-boot:run` 下启动 | 拉 `main` 分支，本地跑通 |
| 2 | 未登录访问 `/admin/products` 被拦到登录页 | 浏览器无痕窗口直接访问 |
| 3 | 登录后三个模块的页面都能打开，无 500 | 逐个点 |
| 4 | 黄程宇发布商品后，周到的列表页立即能看到 | 发布 → 首页刷新 |
| 5 | 分类下拉框能选到种子分类，新增分类后立即可选 | 分类管理 → 发布页 |
| 6 | 价格 9.90 在列表页显示为 9.90（不是 9.9 或 990） | 发布后看列表 |
| 7 | 下架商品在买家列表消失，重新上架后恢复 | 上下架 → 刷新首页 |
| 8 | 关键词能搜到名称与描述里的字 | 搜「显示器」「1080P」 |
| 9 | 同一手机号连续提交 3 次，拿到 3 个不同口令码 | 提交 3 次对比 |
| 10 | 撤销其中一个口令码，其余两个仍有效 | 撤销 → 再撤销另一个 |
| 11 | 未登录用户看不到任何后台数据 | 无痕窗口访问 `/admin` 与 `/admin/products` |
| 12 | 两个账号互不可见对方商品（若后续支持多账号） | `seller_id` 过滤生效 |

---

## 7 变更记录

| 版本 | 日期 | 变更内容 | 修订人 |
|------|------|---------|--------|
| v1.0 | 2026-10-10 | 首版：通用约定、账号模块 6 个接口、商品模块 10 个接口、门户模块 4 个接口、可选 JSON 接口、联调清单 | 胡成溯 |

> 变更规则：任何一方修改路由或字段名，必须先更新本表并在小组群公告，
> 对方确认后再改代码。**口头约定不算数。**
