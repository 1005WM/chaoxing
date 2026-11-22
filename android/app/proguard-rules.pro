# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 Pybsama.

# Keep line numbers and source filenames for readable crash diagnostics.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve annotations, generic signatures, and enclosing/inner class metadata.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# kotlinx.serialization & ChaoxingCore models / persistence snapshots.
-keep,includedescriptorclasses class com.caochaoxing.core.model.** { *; }
-keep,includedescriptorclasses class com.caochaoxing.core.store.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.AnnotationsKt

# WebView @JavascriptInterface bridges (e.g., LeafletJsBridge).
-keepattributes JavascriptInterface
-keepclasseswithmembers,includedescriptorclasses class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.caochaoxing.android.**.*Bridge* { *; }

# OkHttp / Okio optional JVM platform & TLS provider references.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
