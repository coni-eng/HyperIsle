# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================
# HyperIsle Release Build Optimizations
# ============================================

# Strip debug and verbose log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Keep Room database entities and DAOs
-keep class com.coni.hyperisle.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Keep notification service
-keep class com.coni.hyperisle.service.NotificationReaderService { *; }

# Keep broadcast receivers
-keep class com.coni.hyperisle.receiver.** { *; }

# Keep models for serialization
-keep class com.coni.hyperisle.models.** { *; }

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep Compose stability
-keep class androidx.compose.** { *; }

# Keep WorkManager workers
-keep class com.coni.hyperisle.worker.** { *; }