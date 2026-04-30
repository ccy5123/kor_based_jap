package io.github.ccy5123.korjpnime.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Bridge activity that fronts the system speech-recognition dialog for the
 * IME service.  IMEs are Services, not Activities, so they can't call
 * `startActivityForResult` directly — this transparent activity is the
 * usual workaround:
 *
 *   1. IME's ⋯ menu's 음성 입력 → starts this activity.
 *   2. Activity launches `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`.
 *   3. System (Google / Samsung / OEM) shows its native voice UI.
 *   4. On result, transcript text is written to SharedPreferences under
 *      [PREFS_NAME] / [KEY_TEXT].  Activity finishes.
 *   5. IME's `onStartInputView` polls those prefs the next time the
 *      keyboard re-attaches and commits the text at the cursor.
 *
 * Theme is translucent + no-title-bar so the activity itself doesn't
 * flash any chrome — the user sees only the system voice dialog.
 *
 * Permissions: none requested by us.  The system recognizer service
 * holds RECORD_AUDIO; we just hand off the intent.
 */
class VoiceInputActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> handleResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Locale tag picked by the IME service based on its current
        // [InputLanguage] (KOR → ko-KR, ENG → en-US, JPN → ja-JP) and
        // passed in via the launching Intent extra — without this the
        // system recognizer falls back to the device's default input
        // locale, which can be jarring (English-locale phone listening
        // for English when the user is in Korean mode).
        val languageTag = intent?.getStringExtra(EXTRA_LANGUAGE_TAG)

        val recogIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "음성 입력")
            if (!languageTag.isNullOrEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                // Some recognizers honour PREFERRED_LANGUAGES instead of
                // EXTRA_LANGUAGE; setting both is the documented pattern
                // for cross-OEM compatibility.
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    languageTag,
                )
            }
        }
        try {
            launcher.launch(recogIntent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "음성 인식을 사용할 수 없어요. (시스템에 음성 엔진 미설치)",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private fun handleResult(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            val texts = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = texts?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TEXT, text)
                    .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                    .apply()
            }
        }
        finish()
    }

    companion object {
        const val PREFS_NAME = "voice_input"
        const val KEY_TEXT = "text"
        const val KEY_TIMESTAMP = "ts"
        /** BCP-47 locale tag passed from the IME service ("ko-KR" etc.). */
        const val EXTRA_LANGUAGE_TAG = "language_tag"

        /**
         * Convenience helper for the IME service: read + clear any
         * pending voice transcript.  Returns the text or null if
         * nothing was waiting (or it was older than [maxAgeMs]).
         */
        fun consumePending(context: Context, maxAgeMs: Long = 30_000L): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val text = prefs.getString(KEY_TEXT, null) ?: return null
            val ts = prefs.getLong(KEY_TIMESTAMP, 0L)
            val fresh = System.currentTimeMillis() - ts <= maxAgeMs
            prefs.edit().remove(KEY_TEXT).remove(KEY_TIMESTAMP).apply()
            return if (fresh && text.isNotEmpty()) text else null
        }
    }
}
