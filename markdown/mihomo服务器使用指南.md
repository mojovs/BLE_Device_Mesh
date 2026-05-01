# Mihomo 服务器使用指南

## 基本信息

| 项目 | 值 |
|------|-----|
| API 端口 | 9090 |
| 代理端口 | 7897（原 7890，后改 7897） |
| 配置文件 | `~/.config/mihomo/config.yaml` |
| 管理方式 | screen + REST API |
| 模式 | rule（规则模式） |

## 启动/重启

```bash
# 启动新会话
cd ~/.config/mihomo && screen -dmS mihomo mihomo

# 需要 root 时（TUN 模式）
sudo $(which mihomo) -d ~/.config/mihomo

# 重启（先关再开）
screen -X -S mihomo quit
cd ~/.config/mihomo && screen -dmS mihomo mihomo
```

## 查看和管理 Screen 会话

```bash
screen -ls                          # 列出所有会话
screen -r mihomo                    # 连接到 mihomo 会话
screen -dr mihomo                   # 强制连接（先 detach 再 attach）
screen -X -S mihomo quit            # 关闭 mihomo 会话
```

Screen 内操作：
- `Ctrl+A` → `D` — 安全离开（detach），后台继续运行
- `Ctrl+C` — 停止当前进程（screen 内）

## 切换代理模式

```bash
# 查看当前模式
curl --noproxy "*" -s http://127.0.0.1:9090/configs | jq '.mode'

# 切换模式
curl --noproxy "*" -X PATCH -H "Content-Type: application/json" \
  -d '{"mode":"global"}' \
  http://127.0.0.1:9090/configs
```

可选模式：`rule`（规则）、`global`（全局）、`direct`（直连）

## 节点管理

```bash
# 查看有哪些组
curl --noproxy "*" -s http://127.0.0.1:9090/proxies | jq 'keys'

# 查看组内节点
curl --noproxy "*" -s http://127.0.0.1:9090/proxies/糯米 | jq '.all'

# 查看当前选中节点
curl --noproxy "*" -s http://127.0.0.1:9090/proxies/糯米 | jq '.now'

# 切换节点
curl --noproxy "*" -X PUT -H "Content-Type: application/json" \
  -d '{"name":"As·美国·ALL-CAcs2-gpt/nf"}' \
  http://127.0.0.1:9090/proxies/糯米
```

注意：切换节点时不持久化，重启后恢复配置文件的默认顺序。

**关于 `--noproxy "*"`**：如果设置了 `http_proxy` 环境变量，请求 API 时必须加上此参数，否则 API 请求会被错误地转发到代理端口。

## 测试代理是否生效

```bash
# 走代理（看节点出口 IP）
curl -x http://127.0.0.1:7897 -s https://ipinfo.io

# 只看城市/国家
curl -x http://127.0.0.1:7897 -s https://ipinfo.io/city
curl -x http://127.0.0.1:7897 -s https://ipinfo.io/country

# 直连（对比，不走代理）
curl --noproxy "*" -s https://ipinfo.io/ip
```

## 配置 TUN 模式（全局代理）

在 `~/.config/mihomo/config.yaml` 末尾添加：

```yaml
tun:
  enable: true
  stack: system
  dns-hijack:
    - any:53
```

需要 root 权限重启：

```bash
sudo $(which mihomo) -d ~/.config/mihomo
```

启用后所有流量自动走代理，不需要设置 `http_proxy` 环境变量。

## 设置环境变量全局代理（命令行程序）

```bash
# 临时生效
export http_proxy=http://127.0.0.1:7897
export https_proxy=http://127.0.0.1:7897
export no_proxy=localhost,127.0.0.1,10.0.0.0/8,192.168.0.0/16

# 持久化（加到 ~/.bashrc）
cat >> ~/.bashrc <<'EOF'
export http_proxy=http://127.0.0.1:7897
export https_proxy=http://127.0.0.1:7897
export no_proxy=localhost,127.0.0.1,10.0.0.0/8,192.168.0.0/16
EOF
source ~/.bashrc

# 取消
unset http_proxy https_proxy
```

## 修改代理端口

编辑配置文件，改第一行 `mixed-port`，或 API 临时修改：

```bash
curl --noproxy "*" -X PATCH -H "Content-Type: application/json" \
  -d '{"mixed-port":7897}' \
  http://127.0.0.1:9090/configs
```

## 配置别名（可选）

```bash
cat >> ~/.bashrc <<'EOF'
alias mihomo-api='curl --noproxy "*" -s http://127.0.0.1:9090'
alias mihomo-switch="mihomo-api -X PUT -H 'Content-Type: application/json' -d"
alias proxy-on='export http_proxy=http://127.0.0.1:7897; export https_proxy=http://127.0.0.1:7897'
alias proxy-off='unset http_proxy https_proxy'
EOF
source ~/.bashrc
```

用法：
```bash
mihomo-api /proxies/糯米 | jq '.now'           # 看当前节点
mihomo-switch '{"name":"Asc·香港·ak"}' /proxies/糯米  # 切换节点
proxy-on                                         # 开启代理
proxy-off                                        # 关闭代理
```
