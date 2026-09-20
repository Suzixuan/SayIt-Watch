# SayIt server/backend

> 密钥与敏感配置**只从环境变量注入**，仓库内**没有任何真实密钥**。
> 请勿把真实 key / 密码提交到 Git；本地可用 `.env`（已被仓库 `.gitignore` 忽略）。

## 配置来源

`app/config.py` 通过 `_env_str(...)` 从进程环境变量读取，字段默认值均为空字符串：

| 字段 | 环境变量（示例） | 说明 |
|---|---|---|
| `azure_api_key` | `AZURE_API_KEY` | Azure 语音密钥 |
| `openai_api_key` | `OPENAI_API_KEY` | OpenAI 密钥 |
| `groq_api_key` | `GROQ_API_KEY` | Groq 密钥 |
| `admin_password` | `SAYIT_ADMIN_PASSWORD` | 管理端密码 |

前缀与配置 profile 可通过现有 `env_prefix` / 环境变量机制调整，见 `app/config.py`。

## 本地运行

```bash
# 依赖在 requirements.txt
pip install -r requirements.txt
# 注入环境变量后启动（entrypoint.sh 已配置启动方式）
export OPENAI_API_KEY=<your-api-key>   # 示例：从安全来源获取，勿硬编码
./entrypoint.sh
```

也可用仓库内的 `Dockerfile` 构建镜像。`server/.kiro/`、`server/.pytest_cache/` 等本地产物已在 `.gitignore` 中忽略。
