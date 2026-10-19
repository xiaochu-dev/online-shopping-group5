# 在线购物系统（第 5 组）· MVP 基线版本 Docker 镜像
# 基础镜像：Python 3.12 slim（与开发/测试基线 3.12 一致）
FROM python:3.12-slim

LABEL org.opencontainers.image.title="online-shopping-group5" \
      org.opencontainers.image.description="在线购物系统 MVP（单商品售卖闭环）· 第5组" \
      org.opencontainers.image.source="https://github.com/xiaochu-dev/online-shopping-group5" \
      org.opencontainers.image.version="baseline-v1.0" \
      org.opencontainers.image.licenses="MIT"

# PYTHONUNBUFFERED：日志实时输出，便于 docker logs 观察
# DB_PATH：把 SQLite 数据文件放到挂载卷 /app/data 下，容器重建不丢数据
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DB_PATH=/app/data/shop.db

WORKDIR /app

# 先只拷依赖清单，命中 Docker 层缓存：代码改动不会触发重新装包
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# 再拷应用代码（.dockerignore 已排除 .git / 虚拟环境 / 构建产物 / 数据库文件）
COPY . .

# 上传图片目录与数据目录；/app/data 声明为卷，容器删除后数据仍在
RUN mkdir -p /app/data /app/static/uploads
VOLUME ["/app/data"]

EXPOSE 8000

# 健康检查：/product 是买家页（公开），能返回 200 说明应用与数据库均就绪
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/product', timeout=3).status == 200 else 1)"

# main.py 内部调用 init_db() 后以 uvicorn 监听 0.0.0.0:8000
CMD ["python", "main.py"]
