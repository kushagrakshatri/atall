# App-specific R8 rules. Compose/AndroidX ship their own consumer rules.
# kotlinx.serialization: keep generated serializers of persisted app records.
-keepclassmembers class app.atall.** { *** Companion; }
-keepclasseswithmembers class app.atall.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.atall.**$$serializer { *; }
# OkHttp (chatgpt-auth) optional platform classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
