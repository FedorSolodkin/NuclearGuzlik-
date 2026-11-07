# ── R8 rules for VoiceMind release build ───────────────────────────────────────

# Метаданные Kotlin и generic signatures нужны Gson и reflection в data-классах.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-keep class kotlin.Metadata { *; }

# ── Наш код ────────────────────────────────────────────────────────────────────
# Обфусцируется всё, кроме того, что читается по имени:
#  • модели, которые Gson (де)сериализует по именам полей — иначе файлы на диске
#    и старые данные перестанут читаться;
#  • классы из layout XML (WaveformView) — их держит AAPT-правило автоматически.
-keep class com.example.voicemind.DailyRecord { *; }
-keep class com.example.voicemind.DailyRecord$Entry { *; }
-keep class com.example.voicemind.WhisperHelper$Segment { *; }
-keep class com.example.voicemind.WhisperHelper$Word { *; }
-keep class com.example.voicemind.Client { *; }
-keep class com.example.voicemind.VoiceProfile { *; }
-keep class com.example.voicemind.CalendarEvent { *; }
-keep class com.example.voicemind.CalendarEventCache$Cache { *; }
-keep class com.example.voicemind.SpeakerCentroidStorage$Snapshot { *; }
-keep class com.example.voicemind.SpeakerCentroidStorage$Snapshot$* { *; }

# Нативный слой вызывает обратно Kotlin по имени метода (llama_jni.cpp → onPiece).
-keep interface com.example.voicemind.LlmHelper$PieceListener { *; }

# ── Логи ───────────────────────────────────────────────────────────────────────
# В release вырезаем подробные логи целиком (с вычислением аргументов):
# в logcat не должно попадать ничего о содержимом записей и аккаунте.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── JNI ────────────────────────────────────────────────────────────────────────
# Native-методы whisper.cpp / llama.cpp привязываются по сигнатуре класса+метода.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Gson ───────────────────────────────────────────────────────────────────────
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

# ── ONNX Runtime (Silero VAD, speaker embedder) ────────────────────────────────
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── AndroidX Security (только чтение старых EncryptedSharedPreferences) ────────
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**

# ── Biometric ──────────────────────────────────────────────────────────────────
-dontwarn androidx.biometric.**
