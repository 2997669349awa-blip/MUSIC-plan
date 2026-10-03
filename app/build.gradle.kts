plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.filemanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.filemanager"
        minSdk = 29          // Android 10
        targetSdk = 34
        versionCode = 13
        versionName = "1.6.6"
    }

    // 固定签名配置，确保每次构建签名一致，避免升级时签名冲突
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "filemanager123"
            keyAlias = "filemanager"
            keyPassword = "filemanager123"
        }
    }

    buildTypes {
        debug {
            // debug也使用固定签名，确保覆盖安装不会签名冲突
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
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
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.cardview:cardview:1.0.0")
    // Shizuku API - 官方要求依赖：api + provider（provider 包含 ShizukuProvider 实现）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // aidl 包提供 IShizukuService 接口，用于通过 binder 调用 newProcess 执行特权命令
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
