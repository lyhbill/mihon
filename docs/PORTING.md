# Shihon → Mihon v0.21.2 移植说明

本文档说明本仓库（分支 `port/smartos`）将 [Shihon](https://github.com/conezcc/Shihon) 的阅读器增强功能移植到
Mihon v0.21.2 的来源、范围、适配决策、构建方式与已知限制。

> 目标：只移植**功能**，不移植 Shihon 的品牌、图标、官方签名与发布渠道。
> `applicationId` 保持 `app.mihon`，应用名保持 Mihon。

---

## 1. 移植来源与目标基线

| 项 | 值 |
| --- | --- |
| 功能来源 | `conezcc/Shihon` |
| 来源许可证 | Apache License 2.0 |
| 来源基线 commit | `3acd7eac`（基于 Mihon `main` 的 2026-09-04 快照） |
| 目标上游 | `mihonapp/mihon`，tag **`v0.21.2`** |
| 本仓库分支 | `port/smartos`（base commit `9612d95`，即 `Release v0.21.2`） |
| 本仓库形态 | 浅克隆（`git log` 只有 1 个提交），所有编译验证均靠 CI |

移植方式：以 Shihon 的 99 个变更文件为参照（只读参照目录 `I:\harness\port\shihon-ref`，路径与上游一致），
逐个文件按 v0.21.2 的 API 现状重写或适配，而不是整包替换或 merge。上游已有的模块边界、包名、
依赖注入容器保持不变，只在其上增加功能。

---

## 2. 功能清单

### ① 水波纹翻页（SmartOS 4 以上）

- 入口：阅读器设置 → 翻页效果（`SmartOsPageTurnEffect` 与 `ReaderPreferences.WaterRippleSpeed`）。
- 原理：SmartOS（iReader 电纸书系统）提供隐藏的 `android.eink.EPDCDevice#nativePostCommand(String)`，
  通过**反射**下发 `next-effect-type <code>` 命令，由系统合成翻页水波纹。
- 启用判定（全部满足才启用，否则静默降级为普通翻页）：
  1. `Build.MANUFACTURER` 或 `Build.BRAND` 为 `iReader`（不区分大小写）；
  2. 系统属性 `ro.build.versionCode` 的主版本号 `>= 4`；
  3. 反射能拿到 `nativePostCommand` 方法。
- 方向编码：按 `displayRotation` + 阅读方向（RTL/LTR）+ 前进/后退映射为 1–4，再与速度档的
  `commandFlag` 按位或；反射失败的路径只记录 `logcat` 错误，不崩溃。
- 非 SmartOS 设备：`isSupported == false`，功能不生效且无副作用。

### ② 图像调整（亮度 / 对比度 / Gamma）

- `ImageProcessing` 提供常量与换算（与 Shihon 完全一致，CI 有单测覆盖）：
  - 亮度：`-10..10`，默认 0，映射为 `/40f`；
  - 对比度：`-10..10`，默认 0，映射为 `1f + v/20f`；
  - Gamma：`50..200`，默认 100，映射为 `/100f`；
  - `fallbackGammaContrast(v) = 2f * (1f - 0.5f^gamma(v))`（不支持真 Gamma 的绘制路径的退化实现）；
  - 文字加强：`0..20`，默认 0，映射为 `/10f`（配合 ③ 使用）。
- 由阅读器图像视图在绘制阶段应用，不改动源图文件。

### ③ 本机 PP-OCRv5 文字检测 + 笔画加深

- 推理：`onnxruntime-android` 在**本机**跑 PP-OCRv5 mobile detector，**只用检测概率图**（即“可能是文字的区域”）。
  Shihon 不识别、不提取、不传输、不存储页面文字内容。
- 遮罩生成（`TextEnhancementMaskProcessor`）：
  - 把页面解码采样到 `MODEL_MAX_SIDE` 以内的 32 倍数，按蓝/绿/红通道分别归一化后输入 ONNX；
  - 输出概率经 `smoothstep` 置信度映射，再与**源图暗度**和**局部对比度**（积分图算局部均值）相乘，
    得到 `ALPHA_8` 软遮罩；局部对比度门控避免把灰底/网点误加深成墨块。
  - 线程：`intra_op = INFERENCE_THREADS`，`inter_op = 1`，关闭 CPU arena（回归中 arena 常驻超 100MB）。
- 缓存：`SHA-256(页面字节) + 算法版本 + crop 标记` 作为 key，gzip 压缩写 cache，
  章节构建时提升为 persistent（`noBackupFilesDir`）缓存；有容量上限并做 LRU 修剪。
- 渲染：`InkSubsamplingImageView` / `ReaderPageImageView` 接收遮罩，按 `textEnhancement` 强度把墨迹加深；
  webtoon 路径同样支持。
- 模型：`app/src/main/assets/text_enhancement/ppocrv5_mobile_det.onnx`（4.8 MB，随包分发）。

### ④ 章节构建队列

- `data/preprocessing`：`PreprocessingManager`（串行队列、暂停/取消）、`PreprocessingJob`、`PreprocessingStore`
  （持久化任务状态）与 `PreprocessingTask` 模型。
- `PreprocessingManager.queueAutomatically(manga, chapter)`：下载完成/打开章节时按策略自动排队预生成遮罩产物。
- `ChapterPreprocessingArtifacts`：把「整章的遮罩产物」落盘复用，避免重复推理。
- UI：`PreprocessingQueueScreen`（队列总览、进度、取消）、`ChapterPreprocessingIndicator`（章节列表角标）。
- 依赖注入：`AppGraph` 上新增 `val preprocessingManager: PreprocessingManager`。

### ⑤ 分页阅读增强

- **横图预览**：宽高比异常的页面在分页模式下的预览/自适应处理。
- **分区阅读**：按画面区域分段展示（配合翻页方向）。
- **按缩放裁切**：`PageCropProfile` / `PageAspectRatio` 按页面宽高比记录裁切参数
  （`scaleByViewWidth`、`centerX/Y`、`top/bottom/left/right`），可序列化为 `ratio,...,` 分号串持久化。
- **页面留白**：`top/bottom/left/right` 四边百分比（`PageCropState`）在阅读器中作为留白/裁切量。
- **禁用滑动翻页**：`PagePan` / `PageSelectionDirection` 控制可拖动方向与翻页触发，避免误触翻页。

---

## 3. 与上游（v0.21.2）的差异点与适配决策

| 项目 | Shihon（来源） | 本仓库（v0.21.2 适配） | 决策理由 |
| --- | --- | --- | --- |
| Webtoon 图像视图 | `WebtoonSubsamplingImageView` | **`WebtoonStripImageView`** | v0.21.2 已删除 `WebtoonSubsamplingImageView`，webtoon 改用 `ImageDecoder.StripsImage` 的条带解码路径；paged 路径仍用 `SubsamplingScaleImageView`。因此 webtoon 侧的遮罩/文字加强/裁切必须挂在 `WebtoonStripImageView` 上。 |
| 依赖注入 | Metro DI | **Metro DI（不变）** | 上游已是 Metro，Shihon 同样基于 Metro，无需换容器。 |
| `appGraph` 全局访问器 | 使用 `mihon.app.di.appGraph` | **继续使用 `appGraph`** | v0.21.2 **仍保留**该访问器：`app/src/main/java/mihon/app/di/AppGraphUtils.kt` 中 `val Context.appGraph get() = metroGraph<AppGraph>()`。为减少无意义偏差，Shihon 中直接使用 `appGraph` 的代码（如 `PreprocessingJob`）保持原样，未改为构造注入。 |
| `AppGraph` 接口 | 含 `preprocessingManager` | 在 v0.21.2 的 `AppGraph` 接口上 **加一行** `val preprocessingManager: PreprocessingManager` | v0.21.2 的 `AppGraph` 仍是带 `val` 的 interface，加属性即可；具体绑定由 Metro 的 graph 声明提供。 |
| `ImageUtil.getImageDimensions` | 上游工具函数 | **自行补回**到 `core/common` 的 `ImageUtil` | v0.21.2 中该辅助函数已不存在，但移植代码（横图预览/宽高比判定/裁切）依赖它。 |
| 新增依赖 | `onnxruntime-android` | **`com.microsoft.onnxruntime:onnxruntime-android:1.27.0`** | 本机推理必需；版本与 Shihon 对齐（`gradle/libs.versions.toml` 新增 `onnxruntime = "1.27.0"`，`app/build.gradle.kts` 加 `implementation(libs.onnxruntime.android)`）。 |
| 模型资产 | PP-OCRv5 mobile det | `app/src/main/assets/text_enhancement/ppocrv5_mobile_det.onnx`（**4.8 MB**，随包） | 离线、无网络、无隐私外发的代价是 APK 体积增加约 4.8 MB。 |
| WebGPU 阅读器 | — | **不移植** | v0.21.2 没有 WebGPU viewer（那是 v0.21.2 之后 main 才有的提交），没有可挂载的宿主。 |
| 品牌/签名 | Shihon 自有品牌与签名 | **不移植** | 只移植功能；`applicationId = app.mihon`，应用名、图标、关于页均保持 Mihon。 |

### 共享 API 契约（移植中不可随意改签名）

下列签名是各模块之间的约定，改动会导致其它模块编译失败：

- `eu.kanade.tachiyomi.ui.reader.setting.ImageProcessing`：常量 + `textEnhancement()` / `contrast()` / `brightness()` / `gamma()` / `fallbackGammaContrast()`
- `InkSubsamplingImageView`：`setTextEnhancementMask(Bitmap?, Int)` / `setTextEnhancementStrength(Int)` / `clearTextEnhancementMask()`
- `ReaderPageImageView`：`setTextEnhancementMask(...)` / `setTextEnhancementStrength(...)` / `clearTextEnhancementMask()` / `capturePageCropProfile()` / `resetPageCrop()` / `currentPageCropState()` / `panForPageTurn(...)`
- `PreprocessingManager.queueAutomatically(manga, chapter)`
- `ChapterPreprocessingArtifacts`

---

## 4. 构建方式

### 4.1 CI：GitHub Actions（本仓库唯一的编译验证手段）

新增工作流：**`.github/workflows/port-build.yml`**（不改动上游 `build.yml` / `release.yml` / `update_website.yml`）。

- 触发：`push`（任意分支）、`pull_request`、`workflow_dispatch`。
- `runs-on: ubuntu-26.04`（与上游 `build.yml` 一致），`timeout-minutes: 120`，
  `concurrency` 取消同分支/同 PR 的旧任务。
- 步骤：
  1. `actions/checkout@v4`，`fetch-depth: 1`（浅克隆仓库可用）；
  2. `actions/setup-java@v4` — Temurin **JDK 21**；
  3. `android-actions/setup-android@v3` + 显式 `sdkmanager --licenses`，安装
     `platforms;android-37.2`、`platform-tools`、`ndk;29.0.14206865`，并尝试 `build-tools;37.0.0`；
  4. `gradle/actions/setup-gradle@v4`（带缓存）；
  5. `./gradlew --no-daemon assembleRelease -Pdist=ci`（先编译，最快暴露错误）；
  6. `./gradlew --no-daemon testDebugUnitTest`（`continue-on-error: true`，不阻塞出包）；
  7. `./gradlew --no-daemon verifySqlDelightMigration`（`continue-on-error: true`）；
  8. artifact：`mihon-smartos-apk`（`app/build/outputs/apk/release/*.apk`，含 ABI splits 与 universal）
     与 `mihon-smartos-unit-test-report`（`app/build/reports/tests/testDebugUnitTest`）。

**签名**：`app/build.gradle.kts` 中 release 变体固定 `signingConfig = debug.signingConfig`；只有环境变量
`MIHON_GITHUB_RELEASE=true` 时才读取 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 等 secrets。
本流水线**不设置** `MIHON_GITHUB_RELEASE`，也不提供 `keystore.properties`，因此输出的是
「debug 签名的 release 包」：无需任何 secrets 即可安装，但签名与官方包不同。

`-Pdist=ci` 的含义（`gradle/build-logic/.../BuildConfig.kt`）：`includeTelemetry = true`
（仓库已跟踪 `app/google-services.json`，因此无 secret 也能过 google-services/crashlytics 插件）、
`enableUpdater = true`、`uploadCrashlyticsMapping = false`。

### 4.2 本地构建所需环境

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| JDK | **21** | 与 `.github/.java-version` 一致；`java = "17"` 只是 source/target 兼容级别 |
| compileSdk | **37.2** | `platforms;android-37.2`（minor SDK，需 AGP 9.x） |
| targetSdk / minSdk | 36 / 26 | 来自 `gradle/mihon.versions.toml` |
| NDK | **29.0.14206865** | build-logic 对每个 android 模块设置 `ndkVersion`；当前 app 无 native 源码 |
| Gradle | **9.8.1**（wrapper） | 直接用 `./gradlew`，无需本地安装 |
| AGP | **9.4.1** | `gradle/libs.versions.toml` |
| build-tools | 由 AGP 选择默认版本 | 仓库未固定 `buildToolsVersion` |

本地步骤：

```bash
# 1) 安装上述 SDK 组件并接受许可
yes | sdkmanager --licenses
sdkmanager --install "platforms;android-37.2" "platform-tools" "ndk;29.0.14206865"

# 2) 构建可安装的 release 包（debug 签名，无需 secrets）
./gradlew assembleRelease -Pdist=ci

# 3) 单测 / 迁移校验
./gradlew testDebugUnitTest
./gradlew verifySqlDelightMigration
```

产物：`app/build/outputs/apk/release/`（`app-universal-release.apk` 及 `app-<abi>-release.apk`）。

---

## 5. 已知限制

1. **只移植功能**：不移植 Shihon 的品牌、名称、图标、关于页、更新渠道与官方签名。
2. **`applicationId` 保持 `app.mihon`**，与本机已安装的官方 Mihon 同包名。
3. **签名不同**：自编译包使用 debug 签名（或本地 `keystore.properties`），与官方发布包签名不一致，
   **不能覆盖安装**官方版本；如需升级/共存请先卸载官方包（会丢失本地数据，请先备份）。
4. **水波纹翻页仅 SmartOS 4+ / iReader 设备**：其它设备上该选项不生效（静默降级），
   且依赖系统隐藏 API `android.eink.EPDCDevice#nativePostCommand`，SmartOS 未来版本若不兼容会静默失效。
5. **OCR 只做检测**：`ppocrv5_mobile_det` 仅输出文字区域概率图，用于加深笔画；
   **不识别文字内容**，无文字提取/翻译/搜索能力。
6. **APK 体积**：模型 4.8 MB 随包，无法按需下载（离线可用性优先）。
7. **首次打开章节的延迟**：遮罩需要推理；章节构建队列可在后台预生成以缓解。
8. **CI 无法验证运行期行为**：本机无 JDK/Android SDK，CI 只做编译 + 单测 + 出包；
   SmartOS 水波纹、ONNX 推理效果、E-Ink 刷新表现需要真机验证。

---

## 6. 署名与许可证（Apache-2.0）

本仓库及其移植代码沿用上游的 **Apache License 2.0**，需同时满足以下 NOTICE 要求：

- **Mihon**：保留仓库根目录 `LICENSE` 与上游版权声明；不修改、不移除任何上游版权/许可头。
- **Shihon**：移植代码源自 `conezcc/Shihon`（Apache-2.0）。在 README / 关于页 / 发布说明中署名，
  并保留 Shihon 原文件中的 Apache-2.0 文件头与 NOTICE。
- **PP-OCRv5 模型**：`app/src/main/assets/text_enhancement/NOTICE.txt` 必须随包保留，内容为：
  - `PP-OCRv5_mobile_det`，Copyright (c) PaddlePaddle Authors，Apache License 2.0；
  - 来源：`https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det`；
  - 模型 SHA-256：`A431985659DC921974177A95ADCFBB90FD9E51989A5E04D70D0B75F597B6E61D`；
  - 说明：仅使用检测概率图，不识别/提取/传输/存储页面文字。
- **ONNX Runtime**：`com.microsoft.onnxruntime:onnxruntime-android`（MIT），许可随 AAR 打包，
  请不要在 `packaging.resources.excludes` 中排除相关许可文件。

---

## 7. CI 已知不确定点（首次跑流水线时优先看这里）

1. **`platforms;android-37.2` 的可用性**：37.2 是 minor SDK 版本（AGP 9.4.1 支持
   `compileSdk { version = release(37) { minorApiLevel = 2 } }`）。该包在稳定通道未必可见，
   工作流会先试稳定通道、再试 `--channel=3`（canary）；两者都失败时只告警，由 AGP 自动下载兜底。
2. **`build-tools;37.0.0` 很可能不存在**：仓库未固定 `buildToolsVersion`，因此该包安装失败不影响构建，
   AGP 会自选默认 build-tools（`sdkmanager` 也没有 `build-tools;latest` 这种别名）。
3. **`ndk;29.0.14206865` 体积大**：下载约 1 GB，主要拉长前置时间；app 当前无 native 源码，
   若确认无用可从工作流移除（但 `build-logic` 仍声明 `ndkVersion`，保留更稳妥）。
4. **浅克隆与 `COMMIT_COUNT`**：`fetch-depth: 1` 下 `git rev-list --count HEAD` 返回 `1`，
   `BuildConfig.COMMIT_COUNT` 与 unstable 变体（debug/nightly）的 versionName 后缀会是 `-1`；
   release 的 `versionCode=38` / `versionName=0.21.2` 不受影响。若需要真实提交数，把 `fetch-depth` 改为 `0`。
5. **Gradle 缓存**：`gradle/actions/setup-gradle@v4` 未使用加密密钥（上游 v6 用 `GRADLE_ENCRYPTION_KEY` secret），
   fork 可直接运行；若要跨 fork 共享缓存需自行提供密钥。
6. **首次构建耗时**：冷缓存 + 全新 SDK 可能接近 120 分钟上限；若超时，先把 `ndk` 安装步骤去掉再试。
