# Release shrinking rules for X Player.
# Room, Media3, Coil, WorkManager and Compose ship their own consumer rules.

# kotlinx.serialization: keep generated serializers of backup models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.xplayer.app.data.backup.** {
    *** Companion;
}
-keepclasseswithmembers class com.xplayer.app.data.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}
