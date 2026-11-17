# Herdr Remote Bridge：用户联调指南

目标：Android 手机通过你自己的 Ubuntu VPS，使用 HTTPS/WSS 连接当前 Windows 用户下已运行的 Herdr Core。不使用 Tailscale，不占用手机 VPN，不开放电脑公网端口。

## 0. 当前完成到哪里

先前提交 `49519d3` 已通过 [Windows 原生验收 CI](https://github.com/weikeyi/herdr-remote-bridge/actions/runs/44072107686)。这证明 CI Windows 环境中的已列测试通过，**不代表你的电脑、Core、1Panel/OpenResty 或手机已连通**。真实 Core、VPS 路由和移动网联调仍需下面的验证。

完整 Windows 包由同一分支的构建流程生成。下载时选择 **Actions 中该分支、目标提交对应的成功构建**，不要从仅有初始化 README 的 `main` 分支拿文件，也不要混用旧 exe、新脚本或上游旧 `web/`。Actions 附件有保留期限；过期后需要新的成功构建，不能把旧下载链接当成永久发行地址。

包名约定：`herdr-remote-bridge_<version>_windows_amd64_<12位revision>.zip`。包内应有：

- `herdr-mobile-relay.exe`
- 匹配本次构建的 `web/`
- `windows/`，含启动、管理、凭证、更新脚本及示例配置
- `build-manifest.json`、`SHA256SUMS`、`LICENSE`
- 本指南 `README.zh-CN.md`

核对下载来源、构建提交及随构建提供的 SHA-256。压缩包内的校验清单可检查文件完整性，但单靠同一未知来源的清单不能证明来源可信。

## 1. 先准备这些信息，暂时不要发任何秘密

| 需要确认 | 用途 |
| --- | --- |
| Windows 10/11 x64；当前普通用户就是运行 Core 的用户 | 本地 Named Pipe、DPAPI 和登录任务都绑定该用户 |
| 完整安装包的稳定解压目录 | 不放在临时解压目录、网络共享或符号链接中；安装后不要移动 |
| 已运行 Core/App 的实际版本、当前会话的确切 Named Pipe | 填 `corePipe`；不要根据用户名猜路径 |
| VPS 上 HTTPS 站点域名、有效证书、DNS 指向 | 手机 PWA 与 WSS 入口；以下采用同域名方案 |
| 1Panel/OpenResty 在宿主机还是容器内，以及谁管理站点配置 | 决定反向代理如何到达内部 Gateway |
| 可用的内部 Gateway 监听端口/容器网络及站点静态目录 | 不与现有服务冲突，不额外占用公网 80/443 |
| 已在 VPS 配置的 Gateway 注册令牌 | 只在 Windows 本地遮蔽输入框输入同一个值，不发到聊天 |

这版审计的参考组合为 Core `0.1.18`、App `0.1.13`。记录你的实际版本；不同版本的兼容性需实测，**不要为完成本指南自动升级、重启或替换 Core/App**。从现有 Core/App 的配置、诊断或日志确认准确 pipe 和会话。若仍不知道，先停在此处确认，不试填示例 pipe，也不要运行另一个 Core 实例来“发现”它。

不要发送服务器私钥、账户密码、API key、注册令牌、`.dpapi` 文件或配对链接。排障只需脱敏错误、版本、状态和测试现象。

## 2. Windows：先做不联网、不安装的自检

打开 **普通、非管理员 Windows PowerShell 5.1**。不要使用管理员、SYSTEM 或其他服务账户，不开启 PowerShell transcript。保持执行策略不变；若组织策略阻止脚本，请走审核/签名流程，不用 `ExecutionPolicy Bypass`。

下面各段在同一个普通 PowerShell 窗口中依次执行；出现失败就停，不继续后续安装。

```powershell
$bundle = (Resolve-Path -LiteralPath (Read-Host '完整 ZIP 的解压根目录')).Path
$relay = Join-Path $bundle 'herdr-mobile-relay.exe'
$scripts = Join-Path $bundle 'windows'
$ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
Get-Content -LiteralPath (Join-Path $bundle 'build-manifest.json')
Get-Content -LiteralPath (Join-Path $bundle 'SHA256SUMS')
Get-FileHash -LiteralPath $relay -Algorithm SHA256
# 人工核对以上哈希与可信构建清单中的 exe 项；不一致则停止。
& $relay version --json
if ($LASTEXITCODE -ne 0) { throw '版本检查失败' }
& $relay bridge self-test
if ($LASTEXITCODE -ne 0) { throw '原生自检失败，暂不安装' }
```

`version --json` 应标明 Windows target、支持 `hc`，revision 与该包清单一致。版本输出只证明兼容标识，不证明下载来源。

自检预期 `supported: true`、`ok: true`，`checks` 中各项为 `true`。它使用并清理临时目录，验证 DPAPI 加解密、拒绝覆盖及本用户 Named Pipe；不连接 Core/VPS、不注册任务、不读取真实凭证。自检失败时保留其**不含秘密的 JSON 和错误信息**即可，不要用管理员权限重试来掩盖普通用户问题。

## 3. VPS：保留现有 1Panel/OpenResty 的 80/443

你的 80/443 已由现有反向代理使用。**不要运行默认的独立 Caddy Compose 来抢占这些端口，不要停掉现有站点。** 请使用本次配套的“既有反向代理”模板，完成该模板的检查后再继续第 4 节。

配套文件位于**与安装包 revision 相同的源代码** `relay/bridge/`，Windows 安装包本身不等于服务器完整源码。参考 [既有反代模板说明](https://github.com/weikeyi/herdr-remote-bridge/blob/bridge/windows-acceptance/relay/bridge/README.md)，在线查看时也应切换到包清单中的同一 commit，不能仅凭分支最新内容混配。

在 Ubuntu 的工作目录中，用下面的 Bash 命令取源码。输入包内 `build-manifest.json` 记录的**完整 40 位 commit SHA**，不是包名里的 12 位简写，也不是固定照抄上文旧 CI 的短 SHA。此操作只下载/选择源码并创建私密配置副本，不启动服务器。

```bash
(
  set -eu
  read -r -p '包清单中的完整 40 位 commit SHA: ' source_revision
  [[ "$source_revision" =~ ^[0-9a-fA-F]{40}$ ]] || { echo '必须输入完整 SHA'; exit 1; }
  git clone --branch bridge/windows-acceptance --single-branch \
    https://github.com/weikeyi/herdr-remote-bridge.git herdr-remote-bridge
  cd herdr-remote-bridge
  git checkout --detach "$source_revision"
  test "$(git rev-parse HEAD)" = "${source_revision,,}"
  cd relay/bridge
  umask 077
  test ! -e .env || { echo '已有 .env，不覆盖'; exit 1; }
  cp .env.example .env
  echo '匹配源码及私密配置副本准备完成，尚未启动服务。'
)
```

若目标目录已存在，或该 commit 无法取到，先核对而不是覆盖或改用 `main`。只有以上命令成功后，进入 `herdr-remote-bridge/relay/bridge`，后面的 Compose 命令都在此目录执行。

先备份该专用站点的现有配置，确认没有正在运行的旧 Bridge Compose 项目；不要同时启动两套。准备私密 `.env`，保留模板固定的 Caddy 镜像，填写本地生成的随机注册令牌。可在不录制、不开日志的服务器本地终端使用 OpenSSL 的 `rand -hex 32` 生成 32 字节随机令牌，直接保存在私密 `.env`，不要发到聊天。本指南不提供会把秘密打印进自动化日志的一键生成命令。文件应仅服务器管理者可读（例如权限 `600`），不要提交 Git、打印 `docker compose config` 的展开结果或发送文件。令牌在服务器容器环境中对 root/Docker 管理者可见，不是服务器端加密存储。将匹配安装包的 `web/` 安全复制到 VPS 的实际目录，校验清单后，在 `.env` 设置：

```text
HERDR_PHONE_WEB_ROOT=/填写实际匹配包的绝对目录/web
```

这个路径是占位说明，不可原样使用。确认目录内有 `index.html` 和 `version.json`；不能让缺失的挂载路径变成空目录。

### 选择一种部署模式，不要同时执行

**A. OpenResty 运行在宿主机网络**（包括确认为 host networking 的容器）：使用 `compose.openresty-host.yaml`。先确认 Docker Engine 至少 28.0.0、`127.0.0.1:18080` 未被占用，再从匹配源代码的 `relay/bridge` 目录执行：

```sh
docker compose -f compose.openresty-host.yaml config --quiet
# 仅在配置检查通过、确定本机拓扑且准备部署时：
docker compose -f compose.openresty-host.yaml up --build -d
```

它只绑定宿主机回环 `127.0.0.1:18080`。站点上游为 `http://127.0.0.1:18080`，不是公网端口。普通 Docker bridge 网络里的 OpenResty **不能**用这个地址访问宿主机。

**B. OpenResty 运行在 Docker bridge 网络**：使用 `compose.openresty-network.yaml`。先由管理者在 1Panel 的持久配置中安排一个**仅 OpenResty 与本项目 Caddy 使用**的专用网络；在 `.env` 中将 `HERDR_OPENRESTY_NETWORK` 设为该网络的实际名字。不要只做一次临时 `docker network connect` 后假定容器重建也会保留，不把 Gateway 加入入口网络。

```sh
docker compose -f compose.openresty-network.yaml config --quiet
# 仅在配置检查通过、网络已正确准备且准备部署时：
docker compose -f compose.openresty-network.yaml up --build -d
```

这个模式不映射宿主机端口，上游为 `http://herdr-bridge-http:8080`。OpenResty 必须确实加入那个专用网络并能解析该别名。

**两种模式每次只能传一个 `-f`。绝不与 `compose.yaml` 合并**，否则可能把默认公网端口配置也合并进来。内部 HTTP 只用于同一受信任 VPS，不适合跨公网传输。

把 `openresty-location.conf.example` 用于 Bridge **专用域名已有的 HTTPS `server`**。替换已有 `location /`，不要再添加第二个同名 location；B 模式把其中 `proxy_pass` 改为上面的容器别名。证书、80/443 与 HTTP→HTTPS 跳转仍由 1Panel 负责。保持 WebSocket Upgrade、原始 Origin、无缓存、转发头清洗与禁用该 location 的访问日志；不要改整个 OpenResty 的其他站点。

先启动内部服务，再用**当前实际 OpenResty 实例**的 `nginx -t` 校验完整配置，通过后才用其既有管理方式重载。本指南不猜可执行文件路径或容器名。容器上游地址变化后需重新校验/重载；不要固定临时容器 IP。若隔离网络在你的 Docker 版本上不通，停止排查，不解除隔离或把端口改绑 `0.0.0.0`。

### 服务器侧检查

- HTTPS 能加载本次 PWA；响应保持无缓存策略，`/probe` 返回 404。
- `https://实际域名/healthz` 应返回 Gateway JSON，`stun_port` 为 0。`relays` 在电脑注册后增加，`clients` 在手机连接后增加；计数只辅助判断，不能代替 fresh 终端输出。
- 无有效注册令牌的 Relay 应被拒绝，不通过关闭认证来排障。
- 从 VPS 外部确认 18080、8080、8443 和 UDP 3478 不对公网开放。
- 若有 CDN/其他前置代理，不擅自信任所有 real-IP/转发头；先核对现有受信任代理配置。

手机站点必须部署本次构建的匹配 PWA，而不是上游旧 bundle。TLS 证书必须有效；不绕过浏览器证书警告。Gateway 的注册令牌只在 VPS 私密配置及 Windows DPAPI 凭证中存在，不进入 PWA 文件或页面。

服务器这一节是操作计划，**本指南不声称已在你的 VPS 上部署**。实际域名、内部网络、可用端口及 1Panel 生成配置的位置必须以你的环境为准。

## 4. Windows：生成本用户 DPAPI 凭证

先确保第 3 节的注册令牌已在 VPS 配置好。下面创建专用目录；不要在里面放其他文件。

```powershell
$root = Join-Path $env:LOCALAPPDATA 'HerdrRemoteBridge'
New-Item -ItemType Directory -Path $root -Force | Out-Null
& $ps -NoProfile -File "$scripts\Protect-BridgeSecret.ps1" `
  -RelayPath $relay -OutputPath "$root\relay.dpapi" -Kind RelayKey
if ($LASTEXITCODE -ne 0) { throw 'Relay 凭证创建失败，或已有文件；不要覆盖' }
& $ps -NoProfile -File "$scripts\Protect-BridgeSecret.ps1" `
  -RelayPath $relay -OutputPath "$root\gateway.dpapi" -Kind GatewayRegistration
if ($LASTEXITCODE -ne 0) { throw 'Gateway 凭证创建失败，或已有文件；不要覆盖' }
```

Relay key 在本地随机生成；Gateway 注册令牌在遮蔽输入框中输入。保护过程中会短暂存在于进程内存，并通过子进程标准输入交给加密程序；不是“内存中从无明文”。不要把令牌写进命令、环境变量、JSON 或聊天。这些凭证绑定当前 Windows 用户；复制到另一台电脑或另一账户不是有效迁移。脚本拒绝覆盖已有文件。若你已经配置过，先核对现有安装，不删除重建以“重试”。

## 5. 写入不含秘密的配置

下面由 PowerShell 生成 JSON，自动正确处理 Named Pipe 中的反斜杠。输入**实际**域名和已经确认的 pipe。域名只填主机名，不含 `https://`、路径、查询或凭证。

```powershell
$config = Join-Path $root 'bridge.json'
if (Test-Path -LiteralPath $config) { throw '已有配置，请先核对，不能自动覆盖' }
$hostName = Read-Host '已配置 HTTPS/WSS 的真实域名（仅主机名）'
$corePipe = Read-Host '已运行 Core 当前会话的确切本地 Named Pipe'
$settings = [ordered]@{
  schemaVersion = 1
  relayPath = $relay
  gatewayUrl = "wss://$hostName"
  phoneAppUrl = "https://$hostName"
  corePipe = $corePipe
  dataDir = $root
  registrationCredentialPath = "$root\gateway.dpapi"
  relayKeyCredentialPath = "$root\relay.dpapi"
}
[IO.File]::WriteAllText($config, ($settings | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
```

`dataDir` 必须是当前用户的 `%LOCALAPPDATA%\HerdrRemoteBridge`，不能改成公共目录或 junction。`web/` 保持在 exe 旁，完整保留安装包结构。两个 URL 仅允许安全 origin，不支持在 `/bridge` 等子路径下运行。

## 6. 安装、启动，然后做“两种 Status”

```powershell
& $ps -NoProfile -File "$scripts\Manage-Bridge.ps1" -Action Install -ConfigPath $config
if ($LASTEXITCODE -ne 0) { throw '任务安装失败，停止检查' }
& $ps -NoProfile -File "$scripts\Manage-Bridge.ps1" -Action Start -ConfigPath $config
if ($LASTEXITCODE -ne 0) { throw '任务启动请求失败' }
& $ps -NoProfile -File "$scripts\Manage-Bridge.ps1" -Action Status -ConfigPath $config
& $ps -NoProfile -File "$scripts\Invoke-Bridge.ps1" -Action Status -ConfigPath $config
```

- `Manage-Bridge ... Status`：仅证明任务计划程序状态。`Running` 不是 Core/VPS 连通证明。
- `Invoke-Bridge ... Status`：通过当前用户受保护 Named Pipe 查询运行中的 Bridge。应有 `ready: true`、`backend: "hc"`、`relay_only: true`，以及匹配的 version/revision、process_id。**该 ready 也不是端到端健康证明**，还必须完成第 7 节的新输出检查，并结合 Gateway `/healthz` 的 `relays`/`clients` 计数。

首次启动可能需要等几秒再查本地 Status。若任务运行但本地 Status 失败，停止排查，不反复安装、不手工杀同名进程。安装只新增本用户的有限权限登录任务，不自动启动；Start 才请求启动。该任务不是系统服务，用户注销后不运行，电脑睡眠或断网也会中断连接。Core/App 的原进程和会话应保持不变。

## 7. 手机：先 Reader，再显式授权 Controller

先用 Reader 验证路径，不立即开放写入：

```powershell
& $ps -NoProfile -File "$scripts\Invoke-Bridge.ps1" -Action Pair -ConfigPath $config -Name Phone
```

本地 JSON 的 `url` 是短时、一次性邀请。不要重定向、截屏、transcript 或公开分享；只把完整链接安全地转到自己的手机，在最终要使用的浏览器/PWA 中打开。链接会包含 `path_policy=relay-only`；不要删改片段。每次新建邀请会替换之前尚未使用的邀请，不能批量生成后再逐个使用。

手机切换到**移动数据，关闭 Wi-Fi**，确认打开的是你的 HTTPS 域名且证书正常。可按浏览器提供的“添加到主屏幕/安装”入口使用 PWA。先完成以下检查：

1. 能看到预期电脑、已有 Core 会话和终端，不是历史缓存里的另一个目标。
2. 在电脑已有的测试会话中产生一条带当前时间的无敏感内容。手机显示这条**新输出**才算读取链路通过。
3. Reader 的输入、审批、终端尺寸控制等写操作不可用。不要用真实付款、生产命令或秘密做测试。
4. 列出设备，确认角色是 Reader：

```powershell
& $ps -NoProfile -File "$scripts\Invoke-Bridge.ps1" -Action Devices -ConfigPath $config
```

只有你明确决定让手机控制电脑，才运行下面的命令，并在手机完成新 Controller 设备的配对：

```powershell
& $ps -NoProfile -File "$scripts\Invoke-Bridge.ps1" -Action Pair -ConfigPath $config -Name Phone-Control -Role controller
```

这是**新邀请**，不是自动把已有 Reader 提权。若同一浏览器已有 Reader 凭证，先保留它，使用另一受你控制的浏览器/PWA 配对上下文验证 Controller，避免误以为本次邀请已替换原设备。通过 Devices 核对实际角色，再处理不再使用的设备。

在无破坏性的测试会话里，只发送一次带唯一标记的提示或输入，核对电脑仅收到一次。`Resize Session` 默认关闭；它会影响电脑的共享终端尺寸，非必要不打开。

## 8. 断网、重连与“不重放”验收

先在没有待处理写入时关闭手机网络，确认界面明确断线；再恢复移动数据，在电脑产生第二条新的时间标记，手机应恢复接收且没有重复订阅现象。

如需验证“发送时断网”，只在安全测试会话里使用唯一无害标记。**只点击一次发送，不点击重试**。恢复连接后，先在电脑核对该标记到底收到几次；若手机显示结果未知，不能推断“没发到”。同进程内有去重保护，但进程重启或记录淘汰后不保证 exactly-once。任何不确定结果都必须先人工核实，不能盲目补发。

关闭手机网络只证明该手机离线，不能单独证明没有 P2P 回退。完整网络验收还应在确认不影响其他服务的维护窗口内，仅中断 Bridge 自己的 Gateway，观察手机断线、恢复后重新获取新输出；不要停止整个 OpenResty 或现有网站。不得通过启用 Tailscale、WebRTC/STUN 或开放电脑公网端口来“修通”测试。

## 9. 停止、卸载与回滚

```powershell
& $ps -NoProfile -File "$scripts\Manage-Bridge.ps1" -Action Stop -ConfigPath $config
& $ps -NoProfile -File "$scripts\Manage-Bridge.ps1" -Action Status -ConfigPath $config
& $ps -NoProfile -File "$scripts\Invoke-Bridge.ps1" -Action Status -ConfigPath $config
```

停止后任务不应继续 Running，本地 Bridge Status 应不再响应。还需在任务管理器中按**之前记录的 process_id 和实际 exe 路径**检查 Relay 已退出；只看管道不响应还不够。确认 Core/App 仍运行。如果 Relay 子进程仍在，不继续更新或重新启动，不按进程名批量结束。

要删除登录任务：

```powershell
& $ps -NoProfile -File "$scripts\Manage-Bridge.ps1" -Action Uninstall -ConfigPath $config
```

卸载保留配置、DPAPI 凭证、文件和 Core/App，不撤销已配对设备，也不恢复专用数据目录收紧的 ACL。不要为了清理而删除整个用户目录。撤销某个设备应在 Bridge 仍运行时，使用 Devices 返回的实际 ID 执行：

```powershell
$deviceId = Read-Host '要撤销的设备 ID（来自 Devices）'
& $ps -NoProfile -File "$scripts\Invoke-Bridge.ps1" -Action Revoke -ConfigPath $config -DeviceId $deviceId
```

最后一个 Controller 不能直接撤销；需要先配对替代 Controller。撤销与卸载是不同操作。

本次首次联调不需要更新。以后若只更换兼容 exe，可使用 `Update-Bridge.ps1 -ConfigPath $config -CandidatePath <已验证exe路径> -ExpectedSHA256 <可信清单哈希>`；脚本不会下载候选文件。回滚用同一命令，指定保留的旧 exe 及旧清单哈希。它**不更新 PWA/web 或脚本**，因此不能用来跨越需要成套替换的版本。出现 `rollback could not be verified` 时保留备份并停止重试，不声称已回滚成功，也不补发任何结果未知的输入。

## 10. 可反馈的验收记录

只提供以下非秘密信息即可：包版本/revision；Windows/Core/App 实际版本；self-test JSON；两种 Status 的脱敏结果；手机浏览器及移动网；新输出是否到达；Reader 是否只读；Controller 测试标记次数；断网恢复结果；停止后 Core 是否仍在。

不要附带邀请 URL、token、密钥、`.dpapi`、终端正文或完整环境变量。此记录能区分“包能运行”“管理通道正常”“Core 可读”“公网中转正常”和“写入行为安全”，不能用某一个绿灯代替整条链路验收。
