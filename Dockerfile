# 迭代2 · Java 17 + Spring Boot 多阶段构建
# 构建镜像：docker build -t ghcr.io/xiaochu-dev/online-shopping-group5:iter2-v1.0 .
# 运行容器：docker run -p 8080:8080 --name shop5 -d ghcr.io/xiaochu-dev/online-shopping-group5:iter2-v1.0
#
# 说明：使用仓库自带 Maven Wrapper（.mvn 已配置 Aliyun 镜像，避免中央仓库超时），
#       编译与运行均基于 JDK 17，与 pom.xml 的 <java.version>17</java.version> 一致。

# ===== 阶段1：编译打包（JDK 17 + 仓库 Maven Wrapper）=====
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

# 先复制 Wrapper 与 pom，利用 Docker 层缓存（依赖不变时不重复下载）
COPY .mvn .mvn
COPY mvnw mvnw.cmd ./
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline || true

# 复制源码并打包（跳过测试，测试已在本地/CI 执行）
COPY src ./src
RUN ./mvnw -B clean package -DskipTests

# ===== 阶段2：运行（仅 JRE）=====
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 复制产物 jar（pom.xml 的 <finalName> 为 online-shopping-group5）
COPY --from=build /build/target/online-shopping-group5.jar app.jar

# 挂载数据目录（SQLite 数据库与上传图片，运行期数据不入镜像）
VOLUME ["/app/data", "/app/uploads"]

# 暴露 8080
EXPOSE 8080

# 启动
ENTRYPOINT ["java", "-jar", "app.jar"]
