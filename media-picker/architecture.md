按照 **Google 官方推荐的现代 Android 开发架构（Modern Android Architecture / MAD）**，架构应遵循**关注点分离（Separation of Concerns）**、**单一真理来源（SSOT）**、**单向数据流（UDF）**，并采用**按功能模块优先（Package-by-Feature）**与**分层清晰（Layered Separation）**相结合的原则。

针对这个**微信级相册/视频选择与大图预览库（WeChatPicker）**，以下是严格符合官方规范的**包结构设计理念**与**完整目录树规划**。

---

### 一、 核心分包设计理念

1. **`core` / `common`（核心基建层）**：
    * 包含应用/库通用的扩展函数、工具类（如时间格式化）及系统视口尺寸探测等纯逻辑组件，与其他业务解耦。
2. **`data`（数据层 - SSOT）**：
    * **`model`**：对外暴露的不可变实体（如 `MediaItem`、`AlbumFolder`），不包含任何 UI 临时属性。
    * **`repository`**：唯一的媒体数据获取源（`AlbumRepository`），隔离底层 ContentResolver/MediaStore 与系统的版本差异。
3. **`feature`（功能层 - UI & ViewModel）**：
    * 按照垂直业务划分子包：`picker`（网格选择器）和 `preview`（全屏大图/视频预览），各自闭环内聚。
    * **UI 状态管理**：严格遵循 MVI / UDF 规范，每个 Feature 包含对应的 `UiState`、`UiEffect` 和 `ViewModel`。
4. **`engine` / `glide`（底层定制引擎层）**：
    * 将我们定制的 1ms 系统级硬件缩略图直通拦截管线（GlideModule / ModelLoader / Fetcher）独立下沉，确保全局基础设施隔离。
5. **`monitor`（性能监控与调试层）**：
    * 独立的内存 / PSS / LRU 命中率 HUD 面板，具备生命周期感知能力，与核心展示逻辑解耦，未来可一键关闭或打包剥离。

---

### 二、 完整项目目录树结构

```text
com.example.wechatpicker
│
├── core/                                   // 核心基础设施与纯工具层
│   ├── extension/
│   │   ├── ContextExt.kt                   // 权限检查、屏幕指标等 Context 扩展
│   │   └── ViewExt.kt                      // 防抖点击、可见性扩展
│   └── util/
│       ├── SafeViewportCalculator.kt       // 视口安全采样边界与 RGB_565 决策计算器
│       └── TimeUtils.kt                    // 视频时长格式化工具 (formatVideoDuration)
│
├── data/                                   // 数据层（模型定义与数据仓储）
│   ├── model/
│   │   ├── MediaItem.kt                    // 媒体元数据模型（不可变数据实体）
│   │   └── AlbumFolder.kt                  // 相册目录/分组实体（bucket 模型）
│   └── repository/
│       └── AlbumRepository.kt              // 媒体库扫描仓储（含 Android 8/10+ 分区存储兼容）
│
├── engine/                                 // 图像与媒体底层引擎定制层
│   └── glide/
│       ├── FastThumbOptions.kt             // 缩略图模式开关 Option (业务通行证)
│       ├── FastThumbnailModelLoader.kt     // 自定义 ModelLoader (处理 Uri)
│       ├── FastThumbnailFetcher.kt         // 1ms 硬件直通缩略图 Fetcher (核心优化)
│       └── FastThumbnailGlideModule.kt     // 全局 AppGlideModule 注册
│
├── feature/                                // 业务功能层 (MVVM / UI / State)
│   │
│   ├── picker/                             // 【功能 1】相册列表与多选网格
│   │   ├── adapter/
│   │   │   ├── AlbumAdapter.kt             // 网格图片适配器 (DiffUtil + Payload 局部刷新)
│   │   │   └── AlbumFolderAdapter.kt       // 顶部目录折叠下拉列表适配器
│   │   ├── viewmodel/
│   │   │   ├── AlbumUiState.kt             // 相册页 UI 状态载体 (StateFlow)
│   │   │   ├── AlbumUiEffect.kt            // 一次性事件载体 (Toast, Finish, NotifyChanged)
│   │   │   └── AlbumViewModel.kt           // 相册业务 ViewModel (O(1) 状态驱动)
│   │   ├── view/
│   │   │   └── GridSpaceItemDecoration.kt  // 4 列网格精准间距装饰器
│   │   └── AlbumPickerActivity.kt          // 相册主 Activity (负责 ViewBinding 与事件收集)
│   │
│   └── preview/                            // 【功能 2】全屏大图与视频播放预览
│       ├── adapter/
│       │   └── PreviewAdapter.kt           // ViewPager2 适配器 (微缩图铺底 + 动态激活)
│       ├── holder/
│       │   └── PreviewDataHolder.kt        // 安全内存数据通道 (防 TransactionTooLargeException)
│       ├── player/
│       │   └── PreviewPlayerController.kt  // 页面级 ExoPlayer 控制器 (轻量缓冲策略 + 声明周期绑定)
│       ├── util/
│       │   └── ImageDimensionDetector.kt   // 大图/长图尺寸 Exif 单次 I/O 探测器
│       ├── viewmodel/
│       │   ├── PreviewUiState.kt           // 预览页 UI 状态
│       │   ├── PreviewUiEffect.kt          // 预览页副作用事件
│       │   └── ImagePreviewViewModel.kt    // 预览页 ViewModel (同步选中状态)
│       └── ImagePreviewActivity.kt         // 预览主 Activity (全屏沉浸式与手势驱动)
│
└── monitor/                                // 【调试/监控】性能面板组件
    ├── model/
    │   └── MemoryMetrics.kt                // 内存采样数据实体 (PSS / 显存 / Java / Native)
    └── AlbumHudTracker.kt                  // 异步协程采样 HUD 追踪器 (LifecycleObserver 托管)
```

---

### 三、 对应的资源文件结构（`res`）

为了与上述现代架构保持规范一致，资源文件的命名按照 **`模块_模块内类型_名称`** 的方式组织，极大减少大型项目中的资源名冲突：

```text
res/
├── drawable/
│   ├── bg_select_badge_selected.xml        // 选中状态蓝色圆形角标
│   ├── bg_select_badge_unselected.xml      // 未选中灰色描边圆圈
│   └── ic_video_play.xml                   // 视频正中心半透明播放按钮
│
└── layout/
    ├── activity_album_picker.xml           // 相册网格主页（包含顶栏、RecyclerView、底部操作栏）
    ├── activity_image_preview.xml          // 全屏大图预览（包含 ViewPager2、沉浸式顶底栏）
    ├── item_album_photo.xml                // 网格单张图片 Item（ImageView + 角标 + 视频时长）
    ├── item_album_folder.xml               // 目录下拉弹窗单个相册条目
    ├── item_preview_page.xml               // ViewPager2 单页（PhotoView + SubsamplingView + 动态容器）
    └── layout_memory_hud.xml               // 顶部性能监测浮层面板（PSS、显存、命中率）
```

---

### 四、 为什么这套目录划分完全符合 Google 现代规范？

1. **强内聚、低耦合**：
    * 删掉或重构 `preview`（大图预览），完全不会影响 `picker`（相册列表）的代码。
    * 删掉 `monitor`（HUD 监控），核心图片选择业务完全零报错，具备独立打包/剔除能力。
2. **数据流单一可信（SSOT）**：
    * 数据源只由 `data/repository/AlbumRepository` 提供，ViewModel 负责消费并转换成不可变 StateFlow，UI 仅负责 `collect` 渲染，绝无逆向操作。
3. **消除模型污染**：
    * `data/model/MediaItem` 保持为纯净的物理实体，没有将 `isSelected` 这类易变的 UI 业务状态硬编码进实体类，从根源上保障了多列表、多目录间的并发安全。
4. **底层资产隔离**：
    * 将系统硬件直通的 Glide 拦截模块集中在 `engine/glide` 中，上层业务无论如何调整，底层图片性能基建保持绝对稳定。