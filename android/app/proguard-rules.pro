# =============================================================================
# My Orbit — ProGuard / R8 keep rules
# =============================================================================
# Last updated: 2026-05-04
#
# Release builds run with isMinifyEnabled = true and isShrinkResources = true
# (see android/app/build.gradle.kts). R8 strips classes that look unused at
# compile time, but several libraries we depend on resolve classes via
# reflection or JNI at runtime — those need explicit keep rules so the release
# build does not crash on first launch.
#
# Coverage in this file:
#   - Room (entities, DAOs, RoomDatabase subclasses)
#   - Hilt / Dagger (generated components, @AndroidEntryPoint, @HiltViewModel,
#     @HiltAndroidApp, @Inject, @AssistedInject)
#   - Hilt-Work + WorkManager (@HiltWorker, ListenableWorker subclasses)
#   - SQLCipher (JNI bridge into net.zetetic.database.sqlcipher.*)
#   - kotlinx-serialization (@Serializable, $serializer companions)
#   - libphonenumber (reflective metadata loading)
#   - Glance (AppWidget reflection-based instantiation)
#
# Skipped — verified not actually imported, so no rules needed:
#   - (none — every library in libs.versions.toml that needs reflection rules
#     is imported by app code at HEAD)
#
# Skipped — not needed because the library is fully obfuscation-safe:
#   - Timber (com.jakewharton.timber) — wraps android.util.Log; no reflection.
#     ProGuard config bundled in the AAR handles its own internals.
#
# Add header notes here whenever a new library is added that requires keep
# rules. Drift here = release-build crash.
# =============================================================================


# -----------------------------------------------------------------------------
# Crash-stack readability (RELEASE-02 — Play Console deobfuscation)
# -----------------------------------------------------------------------------
# These attributes preserve line numbers in Logcat stack traces and allow
# Play Console to deobfuscate uploaded crash reports against the R8 mapping
# file. Bundled library consumer rules cannot supply these — they must be
# declared here explicitly.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# -----------------------------------------------------------------------------
# Room
# -----------------------------------------------------------------------------
# Room's runtime uses reflection to wire the generated *_Impl classes into
# subclasses of RoomDatabase. The @Entity / @Dao annotation processors emit
# code that R8 might otherwise prune.
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }


# -----------------------------------------------------------------------------
# Hilt / Dagger
# -----------------------------------------------------------------------------
# Hilt generates Hilt_<ClassName> wrappers, _HiltModules classes, and
# component graphs. @Inject / @AssistedInject constructors are invoked
# reflectively by the generated factories.
-keep class dagger.hilt.** { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.AndroidEntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.HiltAndroidApp class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep,allowobfuscation,allowshrinking class **_HiltModules { *; }
-keep,allowobfuscation,allowshrinking class **_HiltModules$* { *; }
-keep,allowobfuscation,allowshrinking class **Hilt_* { *; }
-keep,allowobfuscation,allowshrinking class **_Factory { *; }
-keep,allowobfuscation,allowshrinking class **_MembersInjector { *; }
-keepclassmembers,allowobfuscation class * { @javax.inject.Inject <init>(...); }
-keepclassmembers,allowobfuscation class * { @javax.inject.Inject <fields>; }
-keepclassmembers,allowobfuscation class * { @javax.inject.Inject <methods>; }
-keepclassmembers,allowobfuscation class * { @dagger.assisted.AssistedInject <init>(...); }


# -----------------------------------------------------------------------------
# Hilt-Work + WorkManager
# -----------------------------------------------------------------------------
# @HiltWorker classes are constructed by HiltWorkerFactory via reflection over
# the AssistedInject-generated factory. WorkManager itself instantiates plain
# ListenableWorker subclasses by name when there is no custom factory.
-keep class androidx.hilt.work.** { *; }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep,allowobfuscation,allowshrinking @androidx.hilt.work.HiltWorker class *
-keepclassmembers class * extends androidx.work.ListenableWorker { <init>(...); }


# -----------------------------------------------------------------------------
# SQLCipher (net.zetetic:sqlcipher-android)
# -----------------------------------------------------------------------------
# SQLCipher is a JNI bridge. The native library calls back into Kotlin/Java
# classes by name; if R8 renames or strips them, SQLite calls crash with
# UnsatisfiedLinkError or NoSuchMethodError at first decrypt.
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
-keepclassmembers class net.zetetic.database.sqlcipher.** { *; }


# -----------------------------------------------------------------------------
# kotlinx-serialization
# -----------------------------------------------------------------------------
# kotlinx-serialization generates a $serializer companion object for every
# @Serializable class. The runtime resolves these by name + reflection.
# Annotation metadata (Signature, *Annotation*, InnerClasses) must survive
# obfuscation so the runtime can read @SerialName / @Serializable.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

# Keep the $serializer synthetic objects.
-keepclassmembers,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **.*$Companion { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable <fields>; }
-keepclassmembers class * { @kotlinx.serialization.SerialName <fields>; }


# -----------------------------------------------------------------------------
# libphonenumber (com.googlecode.libphonenumber)
# -----------------------------------------------------------------------------
# libphonenumber loads region metadata via reflection and from JAR resources
# (PhoneNumberMetadataProto_*). Stripping these breaks parsing for any locale.
-keep class com.google.i18n.phonenumbers.** { *; }
-keepclassmembers class com.google.i18n.phonenumbers.** { *; }
-keep class com.google.i18n.phonenumbers.PhoneNumberUtil { *; }
# Keep the resource files that hold the phone-number metadata.
-keep class com.google.i18n.phonenumbers.metadata.** { *; }


# -----------------------------------------------------------------------------
# Glance AppWidgets
# -----------------------------------------------------------------------------
# Glance instantiates GlanceAppWidgetReceiver subclasses by class name from the
# AndroidManifest. Without this the AppWidget host can't find the receiver.
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }


# -----------------------------------------------------------------------------
# Kotlin metadata + coroutines
# -----------------------------------------------------------------------------
# Standard Kotlin reflection / coroutines safety. These are the upstream
# recommended rules for any app that ships Kotlin coroutines + reflection.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
