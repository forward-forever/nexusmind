# NexusMind Local Infrastructure

本目录用于启动 NexusMind 本地开发基础设施。当前包含 MySQL 8.4.11 和 Milvus 2.6.22 Standalone；Milvus 使用官方配套的 etcd 与 MinIO。

## 首次配置

```bash
cp .env.example .env
```

`.env` 仅用于本地开发且不会提交。请按需修改其中的开发密码。

## 常用命令

在 `deploy` 目录执行：

```bash
# 启动
docker compose up -d

# 查看状态
docker compose ps

# 查看指定服务日志，例如 milvus-standalone
docker compose logs -f <service>

# 停止并删除容器，保留数据卷
docker compose down

# 停止并删除容器及数据卷
docker compose down -v
```

> **警告：** `docker compose down -v` 会永久删除本地 MySQL 数据库和全部 Milvus 数据（包括 etcd、MinIO 与 Milvus 数据卷）。

## 默认端口

- MySQL: `3306`
- Milvus gRPC: `19530`
- Milvus Health/Web: `9091`

MySQL 业务 Schema 由应用 Flyway migration 管理。Milvus Collection 与索引由应用在首次索引 Document 时惰性创建，不通过 Docker Compose 预创建。
