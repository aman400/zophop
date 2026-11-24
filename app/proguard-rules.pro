# ==============================================================================
# ZopHop ProGuard / R8 Rules
# ==============================================================================

# ------------------------------------------------------------------------------
# Release Log Stripping
# ------------------------------------------------------------------------------
# Strip android.util.Log calls and their arguments in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
    public static int println(...);
}

# Strip standard console prints
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

-assumenosideeffects class kotlin.io.ConsoleKt {
    public static void print(...);
    public static void println(...);
}

# ------------------------------------------------------------------------------
# Plugin Architecture Keep Rules
# ------------------------------------------------------------------------------
# BuiltInPluginRegistry loads modules listed in META-INF/services via Class.forName(...)
# and accesses their singleton INSTANCE or default constructor.
-keep class * implements dev.zophop.plugin.api.NodePluginModule {
    public static final ** INSTANCE;
    public <init>();
}

# Keep dynamic node executors instantiated by plugins
-keep class * implements dev.zophop.plugin.api.NodeExecutor {
    public <init>(...);
}

# Preserve NodePluginManifest, PluginContext, and dynamic plugin API models
-keep class dev.zophop.plugin.api.** { *; }

# ------------------------------------------------------------------------------
# Data Models and Entities
# ------------------------------------------------------------------------------
# Keep Room entities and data models stored in DB / JSON
-keep class dev.zophop.data.** { *; }

# ------------------------------------------------------------------------------
# Third-Party Library Suppressions
# ------------------------------------------------------------------------------
-dontwarn androidx.room.paging.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
