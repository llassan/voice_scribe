# Keep JNI-facing whisper classes (methods resolved reflectively from native side)
-keep class com.whispercpp.whisper.** { *; }

# Room generated *_Impl classes are found by reflection (Class.forName + newInstance),
# so R8 full mode — the default since AGP 8 — strips their no-arg constructors and the
# lookup fails at runtime. The Google Mobile Ads SDK drags in androidx.work 2.7.0 →
# androidx.room 2.2.5, which is old enough that it ships no full-mode-safe rules of its
# own, so WorkManager's startup initializer crashed the app on launch in 1.6.0. Nothing
# in this app uses Room directly; this rule exists purely for that transitive database.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
