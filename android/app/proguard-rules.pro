# Deals are parsed by hand from JSON / Firestore maps, so no model classes need keeping.
# Firebase, Coil and AndroidX ship their own consumer rules.

# WorkManager's Room database is created by reflection; R8 otherwise drops its constructor
# ("NoSuchMethodException: WorkDatabase_Impl.<init>" at startup).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Firebase finds its components by reflection.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
