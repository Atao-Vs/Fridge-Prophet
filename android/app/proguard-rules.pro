# ---- Kotlinx Serialization ----
# 保留 @Serializable 类的序列化器，否则 release 包反序列化会崩
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fridgeprophet.app.**$$serializer { *; }
-keepclassmembers class com.fridgeprophet.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.fridgeprophet.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Retrofit ----
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Hilt / Dagger ----
-dontwarn dagger.hilt.**

# ---- 数据模型（反射/序列化会用到）----
-keep class com.fridgeprophet.app.data.remote.dto.** { *; }

# ---- 保留行号，方便线上崩溃定位 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
