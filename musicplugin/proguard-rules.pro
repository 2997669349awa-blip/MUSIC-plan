# MUSIC plan v1.2.2 加固规则

# 保留 Kotlin 元数据（反射用）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class kotlin.Metadata { *; }

# 保留数据模型（JSON 解析反射用）
-keep class com.example.musicplugin.Song { *; }
-keep class com.example.musicplugin.Song$* { *; }

# 保留 Service / Activity 入口（AndroidManifest 声明的组件系统会自动保留，保险起见再加）
-keep class com.example.musicplugin.MainActivity { *; }
-keep class com.example.musicplugin.MusicService { *; }
-keep class com.example.musicplugin.MusicService$* { *; }
-keep class com.example.musicplugin.MusicService$MusicBinder { *; }
-keep class com.example.musicplugin.MusicService$MusicCallback { *; }
-keep class com.example.musicplugin.SignatureVerifier { *; }
-keep class com.example.musicplugin.EncryptedMusicDecoder { *; }

# 保留 Binder / Callback 接口（跨进程/反射调用）
-keep class com.example.musicplugin.*$* { *; }
-keep interface com.example.musicplugin.MusicService$MusicCallback { *; }

# 保留 ViewBinding 生成的类
-keep class com.example.musicplugin.databinding.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# org.json
-keep class org.json.** { *; }

# 保留 Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 保留 R 文件
-keep class **.R$* { *; }

# 移除日志（防调试信息泄露）
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
