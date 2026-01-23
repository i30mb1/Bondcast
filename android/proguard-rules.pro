# Дополнительные оптимизации
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Удаление логов в release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Удаление kotlin debug информации
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
}
