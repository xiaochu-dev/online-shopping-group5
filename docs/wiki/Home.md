# 在线购物系统（第 5 组）项目 Wiki

> 《软件工程实践（Ⅰ）》2026-2027-1 学期 ｜ 项目主题：在线购物系统（挑战升级）
>   
> 组长：胡成溯 ｜ 组员：黄程宇、周到（产品经理）｜ 开发方式：Scrum，两周一个迭代，共 7 次评审


## 文档导航

### 需求基线

- [[需求规格说明书-第5组]] —— MVP 功能需求（FR-01~FR-08）与验收清单
- [[需求澄清结论-第5组]] —— 2026-10-04 助教访谈结论（开发与验收依据）
- [[Product-Backlog-第5组]] —— 27 条特性、优先级与故事点

### 挑战需求 A（迭代 2 升级）

- [[挑战需求A-澄清问答记录]] —— **现行唯一权威口径**：A.1 六条 + A.2 六条 + 补充四问的全部问答，含实现裁定（R1~R7）与表结构改动总表

### 设计与部署

- [[架构设计与数据库表结构-迭代2]]、[[数据库设计-迭代2]] —— 已按当前六表、正式接口和库存状态机同步

- [[设计与部署]] —— 架构设计、构建手册、部署手册、口令码规则；按迭代 1 基线版（Python）与迭代 2（Java）分列

#### 迭代 2 · 账号与客户（胡成溯）

- [[账号与客户模块-迭代2]] —— 买家账号（注册/登录/会话）、客户管理、两套登录态隔离、跨模块出口
- [契约-账号与客户-迭代2](https://github.com/xiaochu-dev/online-shopping-group5/blob/iter2-java/docs/契约-账号与客户-迭代2.md) —— 8 条路由的参数与模型属性、错误提示口径、8 项联调清单

#### 迭代 2 · 商品中心（黄程宇）

- [[商品中心模块-迭代2]] —— T2-08～T2-12：两级分类、多商品发布、库存与上下架、9图、富文本；[PR #1](https://github.com/xiaochu-dev/online-shopping-group5/pull/1)已合入iter2-java
- [[商品中心测试记录-迭代2]] —— 全量83项通过，含16项新增测试与真实页面验证

#### 迭代 2 · 买家门户与交易（周到）

- [[买家门户与交易模块-迭代2]] —— 商品浏览（两级筛选/搜索/详情）、下单与排队、我的订单、卖家交易确认、`intent` 表结构；含路由表、状态机、A 口径落地对照与提示语口径


## 评审报告

- [[阶段性评审]] —— 历次阶段性评审报告汇总（第 1 次、第 2 次）
- [[第1次测试记录]] —— 交叉测试 14 条验收清单全通过（2026-10-07）

## 成果验收

- [[阶段性成果验收]] —— 成果验收**总入口**（课程要求的固定首页，各次验收记录都挂在这里，提交给助教的就是这个地址）

## 快速入口

**代码获取：当前仓库默认分支 `main` 仍为迭代1 Python版本。迭代2 Java代码和已合入的商品中心在 `iter2-java`。**

- [打开Java分支](https://github.com/xiaochu-dev/online-shopping-group5/tree/iter2-java)
- [直接下载Java分支ZIP](https://github.com/xiaochu-dev/online-shopping-group5/archive/refs/heads/iter2-java.zip)（网页下载前也可先切换分支再点 Code → Download ZIP）

```powershell
git clone -b iter2-java https://github.com/xiaochu-dev/online-shopping-group5.git
cd online-shopping-group5
```


### 迭代 2（Java / Spring Boot，iter2-java分支）

- 买家门户入口：`http://localhost:8080/buyer/register`（注册）/ `http://localhost:8080/buyer/login`（登录）
- 卖家登录：`http://localhost:8080/login`（初始账号 admin / admin12345，首登后请改密）
- 卖家后台：`http://localhost:8080/admin`
- 基座自检：`http://localhost:8080/admin/diagnostics`（查看数据源及页面实际列出的表结构与行数）
- 启动方式：双击仓库根目录 `run.bat`，或执行 `mvnw.cmd spring-boot:run`

### 迭代 1（Python / FastAPI 原型，保留对照）

- 买家页面：`http://127.0.0.1:8000/product`
- 卖家后台：`http://127.0.0.1:8000/admin`

- 当前Java源代码：[iter2-java](https://github.com/xiaochu-dev/online-shopping-group5/tree/iter2-java)；默认main保留Python版。

## 个人汇报

- [胡成溯：第2次个人汇报](https://github.com/xiaochu-dev/online-shopping-group5/wiki/胡成溯-第2次个人汇报) —— 账号与客户模块（T2-01~T2-07，13 点）
- [周到：第2次个人汇报](https://github.com/xiaochu-dev/online-shopping-group5/wiki/周到-第2次个人汇报) —— 买家门户与交易推进（T2-13~T2-18，15 点）
- [黄程宇：第1次个人汇报](https://github.com/xiaochu-dev/online-shopping-group5/wiki/黄程宇-第1次个人汇报) —— 商品发布、图片校验、买家提交
