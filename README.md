# NAS Agent

手机上的 **NAS AI Agent 客户端**：用来访问家里 NAS 上部署的 AI Agent 会话界面（DeepSeek Harness Web）。

- 内网：`http://192.168.31.15:8758`
- 外网：`https://nas.lifang.asia:8758`

APK 由 **GitHub Actions** 自动构建，不需要在自己电脑上装任何 Android 开发环境。

## 功能（v1.3）

- **启动即全屏 Web**：按默认线路自动进入 Agent 会话界面，屏幕零常驻覆盖元素
- **QQ 式侧滑菜单**：屏幕左缘右滑（或点左侧中部细条）打开侧边栏
- **侧边栏**：当前线路+延迟、内网/外网/自动切换、控制台/浏览/设置、刷新/浏览器打开/复制地址/屏幕常亮
- **控制台**（侧边栏内页面）：两条线路状态横幅、实时延迟、快捷操作
- **设置**（侧边栏内页面）：默认启动线路、自动探测、屏幕常亮、地址自定义、关于
- **自动探测**：实测两条线路延迟（毫秒），自动走可达线路
- **明文 HTTP 放行**：只放行 `192.168.31.15` 与 `nas.lifang.asia` 两个自有地址的 HTTP；其他域名一律只允许 HTTPS，且严格校验证书
- **WebView 能力**：JavaScript、本地存储、Cookie（退出自动落盘）、文件上传、下载、长按菜单（复制链接/保存图片/下载）、`tel:` / `mailto:` / `intent://` 外链跳转、摄像头/麦克风授权
- **原生滚动**：网页自己管滚动（壳层绝不套下拉刷新），刷新在侧边栏；隐藏系统滚动条不遮网页
- **移动体验**：加载进度条、返回键（关抽屉 → 网页后退 → 双击退出，Web 优先不误跳控制台）、深色主题

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
     然后打开仓库 **Releases** 页下载 `nas-webview.apk`。
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

v1.1 起可直接在 app 的「设置 → 访问地址」里修改并保存（无需重新打包）。默认地址定义在 [`MainActivity.kt`](app/src/main/java/asia/lifang/naswebview/MainActivity.kt) 的 `DEFAULT_URL_LAN` / `DEFAULT_URL_WAN` 常量里，改默认值需要重新打包：

```kotlin
const val DEFAULT_URL_LAN = "http://192.168.31.15:8758"
const val DEFAULT_URL_WAN = "https://nas.lifang.asia:8758"
```

## 常见问题

- **找不到侧边栏入口**  
  从屏幕**最左缘向右滑**即可呼出（QQ 侧滑同款手势）；屏幕左侧中部有一根细竖条，点它也行。
- **外网地址打不开**  
  注意：`nas.lifang.asia` 只有 IPv6 地址（仅 AAAA 记录）。手机网络不支持 IPv6 时无法访问；且 IPv6 前缀动态、需 DDNS 保持解析更新。测外网时建议关 VPN、用流量测试。
- **地址在 app 里打不开、浏览器却正常**  
  app 有明文 HTTP 白名单（`app/src/main/res/xml/network_security_config.xml`），白名单外的 `http://` 会被系统安全策略拦截（ERR_CLEARTEXT_NOT_PERMITTED）。目前白名单：`192.168.31.15`、`nas.lifang.asia`；用其他地址走 http 需同步改此文件重新打包。
- **外网地址证书报错**  
  本 app 严格校验证书（不做任何「忽略证书错误」的不安全处理）。请确认 `nas.lifang.asia` 的证书是正规 CA 签发、域名匹配、未过期，且服务端确实使用了该证书。
- **页面样式错乱 / 图片不显示（混合内容）**  
  app 默认禁止 HTTPS 页面加载 HTTP 子资源。如确有需要，把 `MainActivity.kt` 里的 `mixedContentMode` 改回 `WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE`。
- **升级 app 提示安装失败**  
  debug 签名和正式签名不能互相覆盖升级，先卸载旧版再装。所以建议尽早配置正式签名。
- **想换图标**  
  替换 `app/src/main/res/mipmap-nodpi/ic_launcher.png`（启动图标）和 `app/src/main/res/drawable/ic_avatar.png`（应用内头像）两张图即可。
