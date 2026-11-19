# Bridge Android Preview

独立 Android 客户端原型，通过安全 WebView 打开你自托管的 Bridge 网页。不是官方 Herdr Android App 的修改版，也不使用 SSH、ProxyJump、Tailscale 或第三方中转。

此目录独立构建，不依赖仓库另一分支的 Go、Windows 或前端构建。服务器继续提供现有 PWA；协议参照 `bridge/windows-acceptance` 的 `4e80d943579c7213cce78dedeb87df04a428f649`。开发此客户端不会修改或部署服务器代码。

## 安装和连接

1. 仅安装本项目 Android CI 提供并标明提交号的预览 APK。它使用调试签名，但应用可调试开关和 WebView 调试均关闭；不是 Play 商店发行版；正式签名和安全更新分发尚未完成。不同 CI 运行的调试签名可能不同，后续预览 APK 不保证可覆盖安装；卸载会丢失本地配对，届时需要撤销旧设备并重新配对。Android 可能要求你单独允许该安装来源。不要关闭系统安全防护。
2. 保持 Android System WebView 更新。最低 Android 9（API 28），但最低系统版本不保证密码算法兼容。
3. 在应用中输入你自己验证过证书的 HTTPS 根地址。支持域名及带显式端口的 IP 地址。不允许路径、账号密码、查询或片段；应用没有内置服务器地址。
4. 在电脑现有 Bridge 管理命令中为这个 App 单独创建短期 Reader 邀请。在 App 内粘贴完整链接并配对。不要发送邀请给开发者，也不要把网关注册 token 当邀请。
5. 配对成功后重新打开 App，选择已有会话。Chrome 与此 App 的浏览器存储独立，因此 Chrome 已配对不代表此 App 已配对。

首次验证应使用无敏感数据的测试会话。先验证只读查看、重启恢复、断线恢复及电脑端撤销设备。Controller 是独立授权，不能把此预览版的名称或按钮当成服务端权限限制。

更换服务器前须清除本应用数据。清除只移除本地凭据，不撤销服务器上的设备，服务器撤销应在电脑端完成。后台常驻、通知、文件上传下载、摄像头扫码及原生终端目前不属于 MVP。

## 开发和验证

工具版本和完整命令见 `build.gradle`、`app/build.gradle`、Gradle wrapper 及独立 Android CI。通常在本目录执行：

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./gradlew --no-daemon connectedDebugAndroidTest
```

第二条需要 Android 模拟器或专用测试设备；不要接入用户生产设备或使用真实邀请执行自动化测试。CI 测试使用保留示例域名/IP和合成邀请，不连接生产服务器。构建输出本身不证明用户手机、实际证书或实际 Windows Core 已完成端到端验收。

## 安全边界

HTTPS 校验不可绕过；禁止明文、跨域顶层导航、弹窗及不必要的原生权限。不提供 JavaScript 原生桥。不接受外部 Intent 导入邀请。邀请校验失败时拒绝，不退回旧 bootstrap 模式。

**仍须信任所配置服务器的 JavaScript。** 此壳层不把恶意网页变安全，也不是通用网络防火墙。网页设备凭据保存在 App 的 WebView 存储中，不宣称由硬件密钥库保护。详见 [安全说明](docs/SECURITY.md)。

许可证：GNU AGPL v3 或更新版本，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
