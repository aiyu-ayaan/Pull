# Navigation routes are @Serializable objects/classes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.aiyu.pull.ui.navigation.** { *; }
# Route arguments (SettingsPage) and Json-saved state (AddRequest, CommandTemplate) are looked up by serial name.
-keep @kotlinx.serialization.Serializable class com.aiyu.pull.** { *; }
-keepnames enum com.aiyu.pull.** { *; }

# libtorrent4j: SWIG JNI looks Java classes, fields and methods up by name.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# youtubedl-android: Jackson maps yt-dlp's JSON onto these classes by reflection.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
# Unpacking the bundled Python/FFmpeg/aria2c: Commons Compress registers zip extra-field classes reflectively.
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.io.**
