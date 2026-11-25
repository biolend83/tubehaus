# kotlinx.serialization: keep the generated serializers of the library classes
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.tubehaus.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.tubehaus.data.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# V8 (thv8.cc) calls these back by name: call, callAsync, settled
-keep class app.tubehaus.android.V8Runtime { *; }
