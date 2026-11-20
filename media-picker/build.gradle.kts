plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.example.mediapicker"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        // 资源前缀防冲突：防止本模块的 layout/drawable 命名与宿主主 App 发生同名覆盖覆盖冲突
        resourcePrefix = "mediapicker_"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 作为 Library，通常关闭混淆，让最终打包 APK 的宿主主 App 统一决定是否混淆
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug { }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true // 开启 ViewBinding
        buildConfig = true  // 开启 BuildConfig
    }
}


dependencies {
    // AndroidX 核心库
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    // 核心扩展：包含丰富的 Kotlin 语法糖与基础扩展函数
    implementation(libs.androidx.core.ktx)

    // UI 组件
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    // 材质设计组件库：提供 MaterialButton、ShapeAppearance 等标准视觉规范
    implementation(libs.material)
    // 约束布局：支撑复杂、扁平化网格与操作栏的高性能排版
    implementation(libs.androidx.constraintlayout)

    // Glide
    implementation(libs.glide)
    ksp(libs.glide.ksp)
    // 预加载器
    implementation(libs.glide.recyclerview) {
        isTransitive = false
    }

    //ExoPlayer (轻量核心) 现代官方推荐播放器内核
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // 大图切片与预览
    //SubsamplingScaleImageView：超长图/巨型原图的切片瓦片加载器（基于 BitmapRegionDecoder）
    implementation(libs.subsampling.scale.image.view.androidx)
    implementation(libs.photoview)

    // Kotlin 协程
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    // 生命周期感知：支持 lifecycleScope、repeatOnLifecycle 等安全数据监听
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)//viewModels

    // Hilt 依赖注入框架
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)


    // 单元测试与仪器测试
    testImplementation(libs.junit)
    androidTestImplementation (libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

}
