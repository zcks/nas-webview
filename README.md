# NAS WebView

一款极简的 Android WebView 客户端，用来在手机上访问 NAS 的 Web 界面：

- 内网：`http://192.168.31.15:8758`
- 外网：`https://nas.lifang.asia:8758`

APK 由 **GitHub Actions** 自动构建，不需要在自己电脑上装任何 Android 开发环境。

## 功能

- **双地址一键切换**：顶栏「内网 / 外网」按钮，当前使用的地址高亮
- **自动探测**：启动时（或点「自动」）探测内网地址，可达走内网，不可达自动走外网
- **明文 HTTP 放行**：只放行 `192.168.31.15` 的 HTTP，其余仍按安全策略处理；HTTPS 使用正规证书校验，不做绕过
- **WebView 能力**：JavaScript、本地存储、Cookie、网页文件上传、文件下载（系统下载器）、`tel:` / `mailto:` 等外链跳转
- **网页内摄像头 / 麦克风**：语音、视频功能需要时会弹系统授权
- **移动体验**：下拉刷新、加载进度条、返回键=网页后退（双击退出）、屏幕常亮开关（默认开）
- **加载失败页**：一键「重试」或「切换到另一地址」

## 快速开始（全程手机 + 网页即可完成）

1. **建仓库**：在 GitHub 新建一个仓库（如 `nas-webview`），把本项目全部文件推送上去：
   ```bash
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/nas-webview.git
   git push -u origin main
   ```
2. **自动构建**：推送后 GitHub Actions 自动构建（约 2~3 分钟）。
3. **拿 APK**，两种方式任选：
   - 仓库 **Actions → Build APK → 最新一次运行 → Artifacts → nas-webview-apk**（登录 GitHub 才能下载，保留 90 天）
   - 推荐：**打一个 tag**，会自动创建 Release 并附上 APK，手机浏览器直接下载：
     ```bash
     git tag v1.0.0
     git push origin v1.0.0
     ```
     然后打开仓库 **Releases** 页面下载 `nas-webview.apk`。
4. **手机安装**：下载后打开安装，如提示「不允许安装未知应用」，在设置里允许当前浏览器/文件管理器安装即可。**无需 root。**

## 关于签名（重要，但很简单）

Android 要求 APK 必须签名才能安装，签名分两种，**两种都能直接装到手机**：

| 方式 | 说明 | 适合 |
| --- | --- | --- |
| debug 签名（默认） | 不用配置任何东西，workflow 直接产出可安装 APK | 自用、快速迭代 |
| 正式签名（可选） | 配置 Secrets 后产出正式签名 APK，签名稳定，以后升级不用卸载重装 | 长期使用 |

> 「正规签名」和手机 root 没有任何关系。root 是破解手机系统权限；APK 签名只是给安装包盖个章，证明以后升级的还是同一个 app。

### 配置正式签名（可选，一次性操作）

1. 电脑上安装 Java（或用 Android Studio 自带的 Terminal），生成 keystore：
   ```bash
   keytool -genkeypair -v -keystore nas-webview.keystore \
     -alias naswebview -keyalg RSA -keysize 2048 -validity 10000
   ```
   按提示输入密码和信息，**记住密码，妥善保存生成的 `nas-webview.keystore` 文件**。
2. 把 keystore 转成 base64 一段文本：
   ```bash
   base64 -w 0 nas-webview.keystore   # macOS 用: base64 -i nas-webview.keystore
   ```
3. 在 GitHub 仓库 **Settings → Secrets and variables → Actions** 新建 4 个 Secret：

   | Secret 名 | 内容 |
   | --- | --- |
   | `KEYSTORE_BASE64` | 上一步 base64 输出的整段文本 |
   | `KEYSTORE_PASSWORD` | keystore 密码 |
   | `KEY_ALIAS` | `naswebview`（第 1 步的 alias） |
   | `KEY_PASSWORD` | key 密码（与 keystore 密码相同即可） |

4. 重新运行 workflow，产出的就是正式签名 APK。**keystore 文件本身不要上传到仓库**（`.gitignore` 已排除），自己备份好。

## 本地开发（可选）

用 Android Studio 打开本目录即可运行 / 调试。命令行构建需要 JDK 17 + Android SDK：

```bash
# 首次可生成 gradle wrapper（本地需要先装 Gradle 8.7+）
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
```

## 自定义地址

地址硬编码在 [`app/src/main/java/asia/lifang/naswebview/MainActivity.kt`](app/src/main/java/asia/lifang/naswebview/MainActivity.kt) 顶部的常量里：

```kotlin
const val URL_LAN = "http://192.168.31.15:8758"
const val URL_WAN = "https://nas.lifang.asia:8758"
```

改完提交推送即可，Actions 会自动打出新 APK。

## 常见问题

- **外网地址打不开**  
  注意：`nas.lifang.asia` 目前**只有 IPv6 地址（仅 AAAA 记录，无 A 记录）**。手机所在网络不支持 IPv6 时（部分 Wi-Fi / 运营商）将无法访问。建议在阿里云 DNS 补一条 IPv4 的 A 记录，或确认 8758 端口在 IPv6 上对公网开放。
- **内网地址打不开 / 提示明文流量被阻止**  
  明文 HTTP 只对 `192.168.31.15` 放行（见 `app/src/main/res/xml/network_security_config.xml`）。换了内网 IP 的话记得同步修改该文件。
- **外网地址证书报错**  
  本 app 严格校验证书（不做任何「忽略证书错误」的不安全处理）。请确认 `nas.lifang.asia` 的证书是正规 CA 签发、域名匹配、未过期，且服务端确实使用了该证书。
- **页面样式错乱 / 图片不显示（混合内容）**  
  app 默认禁止 HTTPS 页面加载 HTTP 子资源。如确有需要，把 `MainActivity.kt` 里的 `mixedContentMode` 改回 `WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE`。
- **升级 app 提示安装失败**  
  debug 签名和正式签名不能互相覆盖升级，先卸载旧版再装。所以建议尽早配置正式签名。
- **想改图标 / 应用名**  
  图标：`app/src/main/res/drawable/ic_launcher.xml`；名称：`app/src/main/res/values/strings.xml` 里的 `app_name`。
