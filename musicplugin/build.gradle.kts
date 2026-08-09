plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.musicplugin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.musicplugin"
        minSdk = 29
        targetSdk = 34
        versionCode = 36
        versionName = "1.2.9"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../app/release.keystore")
            storePassword = "filemanager123"
            keyAlias = "filemanager"
            keyPassword = "filemanager123"
        }
    }

    flavorDimensions += "platform"
    productFlavors {
        create("phone") {
            dimension = "platform"
        }
        create("tv") {
            dimension = "platform"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            // v1.2.2：启用 R8 代码混淆 + 资源压缩，加固防反编译
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    // MediaSession 和通知栏
    implementation("androidx.media:media:1.7.0")
    // 投屏（安卓原生 MediaRouter）
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    // 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON 解析
    implementation("org.json:json:20240303")
    // TV 版 Leanback 支持（仅 tv flavor）
    "tvImplementation"("androidx.leanback:leanback:1.0.0")
}
