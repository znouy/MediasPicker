# 仿微信相册选择器 MediaPicker

一款仿微信、知乎、京东交互体验的 Android 媒体选择与预览组件。基于 Google 现代 Android 架构（MAD） 与 UDF 单向数据流构建，
攻克了相册大图流滑动黑块块、内存抖动与 OOM 等经典性能顽疾，实现大图极速滑动秒出。

---

## 功能特性

- **媒体选择**：支持单选、多选（带数量上限）、图片与视频混排、相册目录切换。
- **头像/图片裁剪**：支持 1:1 头像裁剪与缩放回弹动效，自动处理 Exif 旋转角与透明通道白底铺垫。
- **三种预览模式**：
    - **相册挑选大图预览（Picker 模式）**：在相册内点击单张大图进入，支持在预览页直接勾选/反选、同步角标序号，并可直接点击“完成”提交。
    - **表单已选预览（Form 模式）**：用于发帖/表单等已选列表回看，右上角带删除按钮，返回更新后的剩余 Uri 列表。
    - **纯只读预览（Read 模式）**：仅用于图片查看，支持缩放与双击查看原图。
- **滑动性能与防 OOM**：
    - 拦截 Glide 默认文件流解码，通过 `FastThumbnailModelLoader` 接入系统级 `ContentResolver.loadThumbnail` 接口，缩短单图解码耗时。
    - 列表图严格限制采样尺寸，使用 `RGB_565` 降低内存占用，配合 `RecyclerViewPreloader` 提前预加载。
    - 普通大图限制在视口安全分辨率；长图/全景图自动分流走 `SubsamplingScaleImageView` 瓦片切片渲染。
- **本地视频预览**：ExoPlayer 缓冲限制在 2MB 以内，避免流媒体播放器预读吃满堆内存；起播前按封面比例预锁容器，防止黑边跳动。
- **内置调试 HUD**：提供类似 Android Studio Profiler 的悬浮窗，实时查看 PSS、Graphics 显存、Native 堆、Java 堆及 LRU 命中率；提供一键批量生成测试大图功能，便于压测。

---

## 快速接入

### 1. 相册选择（单选 / 多选 / 裁剪）

```kotlin
// 1. 注册选择契约
private val pickerLauncher = MediaPicker.register(this) { uris ->
    // 返回选中的 Uri 列表
}

// 2. 发起多选（如发动态、最多选 9 张）
MediaPicker.pickMulti(pickerLauncher, maxCount = 9)
    .showDebugHud(BuildConfig.DEBUG) // 是否开启性能监控浮窗
    .launch()

// 3. 发起单选 + 1:1 裁剪（如头像选择）
MediaPicker.pickSingle(pickerLauncher)
    .crop(aspectX = 1, aspectY = 1)
    .launch()
```

---

### 2. 媒体预览（三种模式）

#### 场景 A：相册挑图大图预览（Picker 模式）
在相册网格中点击某张图进入全屏预览。支持翻页、右上角同步数字角标勾选、并在预览页直接点“完成”退出相册。

```kotlin
// 1. 注册挑图预览契约
private val pickerPreviewLauncher = MediaPreview.registerPicker(this) { result ->
    // result.isConfirm: 用户是否在预览页直接点了“完成”
    // result.updatedSelectedIds: 在预览页中增删后，最终选中的图片 ID 集合
    if (result.isConfirm) {
        // 用户直接确认提交
    } else {
        // 同步勾选状态回相册网格
        viewModel.syncSelectedIds(result.updatedSelectedIds)
    }
}

// 2. 打开预览
MediaPreview.previewPicker(
    context = this,
    launcher = pickerPreviewLauncher,
    mediaList = adapter.currentList // 整个相册的数据源
)
    .initPosition(clickedPosition)   // 起始定位下标
    .selectedIds(currentSelectedIds) // 当前已选中的图片 ID 集合
    .maxLimit(9)                     // 最大可选数量
    .launch()
```

#### 场景 B：表单回看与删除（Form 模式）
用于已选图片列表回看，右上角显示删除图标。

```kotlin
// 1. 注册表单删除契约
private val formPreviewLauncher = MediaPreview.registerForm(this) { remainingUris ->
    // remainingUris: 删除后剩余的 Uri 列表
    adapter.submitList(remainingUris)
}

// 2. 打开预览
MediaPreview.previewForm(
    context = this,
    launcher = formPreviewLauncher,
    uris = selectedUris
)
    .initPosition(clickedIndex)
    .launch()
```

#### 场景 C：纯只读大图预览（Read 模式）
用于普通图片展示，无选择、无删除操作。

```kotlin
MediaPreview.previewRead(
    context = this,
    uris = imageUris
)
    .initPosition(clickedIndex)
    .launch()
```

---

## 核心实现说明

1. **缩略图加速**：
   通过 `LibraryGlideModule` 注册 `FastThumbnailModelLoader`，在加载 `content://media/external/` 协议时，Android 10+ 优先调用 `ContentResolver.loadThumbnail(uri, size, signal)`，Android 9 及以下走系统 `MediaStore.Images.Thumbnails` 表，避免 Glide 默认读取十几兆原图文件流解码。
2. **大图切片分流**：
   进入大图预览时，先通过 `inJustDecodeBounds` 读取尺寸和 Exif。长宽比大于 2.6:1 或单边大于 4096 的图片接入 `SubsamplingScaleImageView` 瓦片解码；其余图片走 Glide 并限制采样大小不超过视口。
3. **轻量播放缓冲**：
   本地视频预览使用单例 `PreviewPlayerController`，定制 `DefaultLoadControl` 将最小缓冲设为 1s、最大缓冲设为 2s，脱离视口立即调用 `clearVideoTextureView` 释放 GraphicBuffer 显存。
4. **内存采样逻辑**：
   HUD 面板直接在当前进程调用 `Debug.getMemoryInfo(memInfo)`，读取内核 PSS 及 `summary.graphics` 显存数据，避免调用 `ActivityManager.getProcessMemoryInfo` 产生的跨进程 IPC 损耗与系统级节流。