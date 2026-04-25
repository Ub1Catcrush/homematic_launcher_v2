# ── Simple XML Framework ────────────────────────────────────────────────────
# Simple XML uses reflection to read/write annotated fields.
# Without these rules, minification will strip annotations and break parsing.
-keep class org.simpleframework.** { *; }
-dontwarn org.simpleframework.**

# Keep all HomeMatic model classes and their fields (XML-deserialized via reflection)
-keep class com.homematic.** { *; }
-keepclassmembers class com.homematic.** {
    <init>(...);
    <fields>;
}

# ── Kotlin ───────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# ── AndroidX / Material ──────────────────────────────────────────────────────
-keep class androidx.preference.** { *; }
-dontwarn androidx.**

# ── General Android ──────────────────────────────────────────────────────────
# Keep custom View subclasses referenced from XML layouts
-keep public class com.grid.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Preserve line numbers in stack traces for debugging release builds
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
