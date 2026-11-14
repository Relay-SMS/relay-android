# SMS Tech — R8/ProGuard rules
# Tightened per audit F19 (avoid keep-all-classes shotgun rules) + F20 (strip Timber payloads).

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ----- Kotlin -----------------------------------------------------------------------------------
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { *; }

# ----- kotlinx.serialization --------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static **$* INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.filestech.sms.**$$serializer { *; }
-keepclassmembers class com.filestech.sms.** {
    *** Companion;
}
-keepclasseswithmembers class com.filestech.sms.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ----- Coroutines -------------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.flow.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ----- Hilt / Dagger ----------------------------------------------------------------------------
# Only keep what the framework needs at runtime (entry-point classes + @HiltViewModel ctors).
-keep class dagger.hilt.android.internal.managers.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keep class androidx.hilt.work.HiltWorkerFactory { *; }
-keepclasseswithmembers class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ----- Androidx ViewModel ----------------------------------------------------------------------
# Names only — implementation members can be obfuscated. (Was `-keep class *` shotgun.)
-keepnames class * extends androidx.lifecycle.ViewModel

# ----- Room ------------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ----- Compose ---------------------------------------------------------------------------------
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ----- Coil / SVG (Twemoji) ---------------------------------------------------------------------
# Round 13 fix (2026-11-11) — the emoji/sticker grids load bundled Twemoji SVGs
# through Coil's SvgDecoder (AndroidSVG). The release build minifies (R8), and
# without these keeps the decoder/parser is stripped, so the grids render empty
# (invisible cells) in release while debug (no minification) works fine.
-keep class coil.decode.SvgDecoder { *; }
-keep class coil.decode.SvgDecoder$Factory { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# ----- Coil / GIF (Klipy) -----------------------------------------------------------------------
# Round 16 — same R8 trap as the SVG decoder above: the GIF search grid and the
# thread image bubbles decode GIFs through Coil's GifDecoder, which R8 strips
# without these keeps (animated GIFs would go back to a static first frame in
# release while debug works fine).
-keep class coil.decode.GifDecoder { *; }
-keep class coil.decode.GifDecoder$Factory { *; }

# ----- Ktor (phone↔desktop sync) --------------------------------------------------------------
# Round 21 CI fix — the release build failed at :app:minifyReleaseWithR8:
# Ktor's IntellijIdeaDebugDetector references java.lang.management classes
# (ManagementFactory, RuntimeMXBean) that exist only on the desktop JVM, not
# on Android, and R8 treats the missing classes as an error. The detector is
# a JVM-only "is a debugger attached?" check that never runs on the phone —
# silencing the two missing classes (exactly what R8's own generated
# missing_rules.txt suggests) is safe. Debug builds never hit this: minify
# is off there.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ----- Timber ----------------------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**

# ----- SQLCipher -------------------------------------------------------------------------------
# Native methods reflected through JNI need to stay reachable.
-keepclasseswithmembernames class net.zetetic.database.sqlcipher.** {
    native <methods>;
}
-keep,includedescriptorclasses class net.zetetic.database.sqlcipher.SQLiteConnection { *; }
-keep,includedescriptorclasses class net.zetetic.database.sqlcipher.SQLiteDatabase { *; }
-keep class net.zetetic.database.sqlcipher.SQLiteDatabaseHook { *; }

# ----- DataStore (protobuf-free path) ----------------------------------------------------------
-keep class androidx.datastore.preferences.protobuf.** { *; }

# ----- SMS / Telephony provider columns referenced via reflection -----------------------------
-keepclassmembers class android.provider.Telephony$** { *; }

# ----- Embedded AOSP MMS PDU classes (v1.3.10 — renamed from com.google.android.mms.*) -------
# `app/src/main/java/com/filestech/sms/pdu/*.java` embeds the AOSP MMS PDU encoder/decoder
# (SendReq, PduComposer, PduBody, PduPart, EncodedStringValue, PduHeaders, CharacterSets,
# MmsException, InvalidHeaderValueException…). Originally lived under `com.google.android.mms.*`
# but Android 10+ enforces a **hidden API blacklist** on that namespace — at runtime the
# system ClassLoader prefers the framework-bundled (hidden) class over our embedded copy and
# denies access ("Accessing hidden method PduParser.<init>([B)V (blacklist, linking, denied)"
# observed on Samsung Galaxy S9 Android 10, 2026-05-18). Result: SMS Tech can't parse any
# incoming MMS PDU, MMS reception silently broken on Android 10+. Rename to
# `com.filestech.sms.pdu.*` forces the ClassLoader to always pick our embedded copy.
#
# Our code drives these classes EXCLUSIVELY via reflection in `MmsBuilder.attachRecipientsCompat`
# + `MmsBuilder.appendPart` to cross OEM signature divergences (Samsung One UI removed `addTo()`
# from its bundled SendReq, AOSP doesn't expose `addPart(PduPart)` on certain versions, etc.).
# Without this keep, R8 sees no direct call site for `setTo` / `addTo` / `addPart` / `getPartsNum`
# and strips them — `Class.getMethod("setTo", …)` then throws NoSuchMethodException and the
# whole MMS dispatch silently fails (regression observed v1.3.6 → v1.3.7, fixed v1.3.8).
-keep class com.filestech.sms.pdu.** { *; }

# ----- App-level reflective entry points (manifest-declared receivers / services / Activity) --
-keepnames class com.filestech.sms.MainApplication
-keepnames class com.filestech.sms.MainActivity
-keepclasseswithmembers class com.filestech.sms.system.receiver.* {
    <init>();
    void onReceive(android.content.Context, android.content.Intent);
}
-keepclasseswithmembers class com.filestech.sms.system.service.* {
    <init>();
}

# ----- Strip Timber + android.util.Log in release (audit F20 + v1.3.10 SEC-02) ----------------
# Release builds set BuildConfig.LOG_ENABLED=false and plant NoOpReleaseTree (which drops INFO and
# below). On top of that, we make every Timber log call a no-op at the bytecode level so that no
# argument expression is ever evaluated. This stops accidental PII / PIN material from leaking
# into logcat via the toString of a captured local even if a developer slipped a Timber.w(secret).
#
# **v1.3.10 (SEC-02)** : the embedded AOSP MMS PDU classes under `com.filestech.sms.pdu.*` use
# `android.util.Log.*` directly (PduComposer / EncodedStringValue). Without the rule below those
# calls survive R8 and end up in logcat in release builds — leaking incoming PDU file URIs and
# similar internal cache paths on any developer-mode device. Strip them with the same
# `-assumenosideeffects` mechanism.
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
    public *** wtf(...);
}
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
