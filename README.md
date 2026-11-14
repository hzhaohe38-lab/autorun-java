# 氢氦跑 autorun

校园跑助手。界面用 Vue，执行核心用 Java，打包成一个 APK 在手机本地运行——不需要服务器，不需要保持浏览器打开。

装好约 1 MB。挂在后台，到点自己签到、签退、跑步。

## 功能

- 校园账号登录 / 验证码改密
- 手动提交跑步记录，提交前可预览路线
- 俱乐部活动：列表、报名、取消报名、手动签到签退
- **自动签到**：开启后由本地引擎按活动时间自动签到、自动签退，不需要盯着
- **定时跑步**：每天在设定的时间窗内随机挑一个时刻，按设定里程范围生成校园轨迹并提交
- 跑步记录查询，配速与时长统计
- 校园地图路线（成都信息工程大学航空港、二田、龙泉驿 三条）

## 环境要求

| | 版本 | 说明 |
|---|---|---|
| JDK | 17 或 21 | 22 及以上不行，AGP 的 jlink 步骤会失败 |
| Android SDK | compileSdk 36 | 用 Android Studio 装，或单独装 command-line tools |
| Node.js | 18+ | 只有改前端时才需要 |

## 构建

```bash
git clone <你的仓库地址>
cd autorun-java

# 1. 告诉 Gradle SDK 在哪（已配 ANDROID_HOME 可跳过）
echo "sdk.dir=/你的/Android/Sdk" > app/android/local.properties

# 2. 出包
./tools/build.sh
```

产物：`app/android/app/build/outputs/apk/debug/app-debug.apk`

Windows 用 `tools\build.bat`，参数一致。

**只改了前端**时，先重建前端产物再出包（会自动 `npm install`）：

```bash
./tools/build.sh --web
```

**用 Android Studio**：直接打开 `app/android` 目录，等 Gradle 同步完点 Run。

**换一台机器构建**时，如果报 `Unsupported class file major version`，打开
`app/android/gradle.properties`，把最后一行 `org.gradle.java.home` 改成你本机 JDK 17/21 的路径。

## 在电脑上验证

不想每次都装 APK 时，让引擎直接跑在电脑上：

```bash
./tools/desktop.sh
```

然后浏览器打开 **http://127.0.0.1:3000**。这个地址提供的就是 APK 里那份前端，接口也走同一套 Java 引擎。

改前端需要热更新时，另开一个终端：

```bash
cd app && npm run dev      # 5173，代理到 3000
```

`3000` 是构建后的产物（改代码要重新 build），`5173` 是 Vite 实时编译。两者可以同时开着。

桌面版和手机版只有一个差别：**桌面靠 5 秒轮询，手机靠 AlarmManager 精确闹钟**。签名、排程、轨迹生成、落盘都是同一份代码。
运行时状态写在 `runtime/`，删掉等于重置。

## 打包

版本号规则：`氢氦跑-autorun-H<数字>`，前缀固定，数字每次打包时指定。

```bash
./tools/package.sh 2.2
```

这一条命令做四件事：改版本号 → 重建前端 → 构建 APK → 按版本号命名并复制到旧项目的 APK 目录：

```
D:\StudyProjects\byerun-web\APK\氢氦跑-autorun-H2.2.apk
```

版本号会显示在 App「我的」页面底部，来源是 `app/src/utils/config.js` 里的 `releaseInfo.version`。

只想出个调试包、不进 APK 目录：

```bash
./tools/build.sh
# 产物：app/android/app/build/outputs/apk/debug/app-debug.apk
```

## 安装到手机

```bash
adb install -r app/android/app/build/outputs/apk/debug/app-debug.apk
```

或者把 APK 拷到手机上直接点安装（需要允许"安装未知来源应用"）。

## 运行

1. 打开 App。首次启动会显示"正在启动本地服务…"，几秒后进入登录页
2. 用校园账号登录
3. **自动签到**：俱乐部页 → 打开自动签到开关。引擎会自己盯着活动，到点签到、到点签退
4. **定时跑步**：管理页 → 定时任务 → 选地图、设时间窗和里程范围 → 保存并开启
5. 通知栏出现「氢氦跑 · 定时任务运行中」，说明后台服务在跑，可以退出界面

### 让后台别被系统杀掉

不同手机设置项名字不一样，找这几个：**自启动**、**后台运行**、**电池优化白名单**、**锁定后台**。
小米/红米叫「自启动」和「省电策略」；OPPO/一加叫「允许后台运行」；华为叫「应用启动管理」。

不开也能用，但国产 ROM 会打折，定时任务可能延后。

## 命令行速查

| 做什么 | 命令 |
|---|---|
| 出包（用现成前端产物） | `./tools/build.sh` |
| 重建前端并出包 | `./tools/build.sh --web` |
| **打包正式版**（改版本号 + 进 APK 目录） | `./tools/package.sh 2.2` |
| **在电脑上跑起来** | `./tools/desktop.sh` |
| Windows 出包 | `tools\build.bat` |
| 安装到已连接设备 | `adb install -r app/android/app/build/outputs/apk/debug/app-debug.apk` |
| 只构建不安装 | `cd app/android && ./gradlew assembleDebug` |
| 清理构建缓存 | `cd app/android && ./gradlew clean` |
| 看日志 | `adb logcat -s Autorun/Service Autorun/Http Autorun/AutoSign Autorun/AutoRun` |

## 目录

```
app/                        前端 + Android 壳
├── src/                    Vue 源码
├── public/                 静态资源
├── index.html
├── package.json
└── android/                Android 工程（用 Android Studio 打开这个）
    ├── app/src/main/assets/web/     前端构建产物（随 APK 打包）
    ├── app/src/main/assets/maps/    三条校园路线
    ├── app/src/main/java/com/qinghaipao/autorun/
    │   ├── MainActivity.java        WebView 外壳 + 启动就绪轮询
    │   ├── AutorunService.java      前台服务：起本地 HTTP、跑引擎、上闹钟
    │   ├── WakeReceiver.java        闹钟到点唤醒
    │   ├── BootReceiver.java        开机 / 应用更新后自动重启
    │   ├── AndroidAssets.java       把 assets 喂给引擎的适配器
    │   └── core/                    ← 引擎，不含任何 Android 类
    │       ├── HttpServer.java      本地 HTTP 服务（手写 ServerSocket）
    │       ├── Router.java          路由分发 + 上游请求转发
    │       ├── Signer.java          请求签名
    │       ├── ApiClient.java       上游 HTTP 客户端
    │       ├── SignEngine.java      签到/签退调度
    │       ├── RunEngine.java       定时跑步调度
    │       ├── TrackGenerator.java  轨迹生成
    │       ├── RunMath.java         距离/时长/配速计算
    │       ├── MapRepo.java         地图数据
    │       ├── Store.java           状态落盘
    │       ├── Mulberry32.java      每日计划用的种子随机
    │       ├── Json.java            紧凑 JSON 序列化
    │       └── L.java               日志门面（Android 接 logcat，桌面接 stdout）
    ├── build.gradle         版本常量（minSdk / compileSdk / targetSdk）
    └── settings.gradle

tools/                      脚本
├── build.sh / build.bat     出包
├── package.sh               打包正式版（改版本号 + 命名 + 归位）
├── desktop.sh               在电脑上跑引擎
├── desktop/                 桌面入口（DesktopMain / FileAssets）
└── gen-*.mjs / *Diff.java   轨迹、签名、种子的一致性校验

libs/json.jar               桌面端用的 org.json（APK 里用系统自带那份）
```

## 工作方式

前端是普通 Vue 应用，请求全走相对路径。APK 里跑着一个本地 HTTP 服务（端口 3000），WebView 加载 `http://127.0.0.1:3000`，
所以前端的 `/api/auto-sign/status` 这类请求直接落到 Java 引擎上；`/auth`、`/clubactivity`、`/unirun`
则转发到校园平台。

两个引擎在后台服务里运行：

- **签到引擎**：每轮先查平台权威状态（`getSignInTf`），确认还没签才发请求。任务、日志、监听名单全部落盘，
  进程被杀重启后接着跑
- **跑步引擎**：每天的计划由 `hash(学号:日期)` 算出，同一个种子永远得到同一个时刻和里程，
  所以重启不会重掷、不会跑两次。计划时刻用精确闹钟触发

## 三个不能改的地方

**`targetSdk` 必须是 28。** Android 12+ 只对 targetSdk ≥ 31 强制 `SCHEDULE_EXACT_ALARM` 权限，
Android 14+ 只对 targetSdk ≥ 34 强制前台服务类型。停在 28，精确闹钟和开机自启都不用额外授权。
代价是上不了 Google Play，只能侧载。

**签名算法一个字节都不能动。** 请求体签名是对序列化后的 JSON 文本做的 MD5，字段顺序变了签名就错。
所以 `RunEngine` 里构造请求体用 `LinkedHashMap` 而不是 `JSONObject`（后者 key 顺序是散乱的）。

**手动跑步那条链路不要碰。** `app/src/components/SubmitRun.vue`、`composables/useRunSubmission.js`、
`utils/track.js`、`utils/run.js` 是已验证可用的提交路径，任何自动化都应该复制算法另写，而不是重构它们。

## 校验工具

`tools/` 里有一套和原始 JS 实现的对拍脚本，两端喂同一串种子随机数，逐点比对：

```bash
# 轨迹：3 条地图 × 3 种距离 × 2 种时长，输出应与 Java 侧逐点一致
node tools/gen-expected.mjs app/android/app/src/main/assets/maps/cuit_hkg.json 3000 20 20261114

# 签名
node tools/gen-sign.mjs
```

## 地图

| mapId | 路线 |
|---|---|
| `cuit_hkg` | 成都信息工程大学 航空港校区（主环，约 2.1 km，默认） |
| `cuit_hkg2` | 成都信息工程大学 航空港校区 二田（约 368 m） |
| `cuit_lqy` | 成都信息工程大学 龙泉驿校区（约 445 m） |

## 声明

本项目仅供学习研究使用，不得用于任何商业或非法用途。成绩是否有效由校园平台判定，使用风险自负。
