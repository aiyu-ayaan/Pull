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
