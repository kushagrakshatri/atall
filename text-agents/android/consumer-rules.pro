# The listener is referenced from the manifest only.
-keep class app.atall.agents.text.android.TextAgentListenerService { *; }
# kotlinx.serialization: keep generated serializers of the persisted records.
-keepclassmembers class app.atall.agents.text.** { *** Companion; }
-keepclasseswithmembers class app.atall.agents.text.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.atall.agents.text.**$$serializer { *; }
