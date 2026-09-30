package com.nexo.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nexo.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen activity that shows the captured crash JSON in detail.
 *
 * - No Compose dependency — uses the platform View system to avoid
 *   requiring activity-compose for an otherwise empty parent.
 * - "Copiar al portapapeles" and "Compartir" actions are wired to
 *   [ClipboardManager] and [Intent.ACTION_SEND] respectively.
 */
class CrashDetailActivity : AppCompatActivity() {
    companion object {
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
        private const val BODY_TEXT_SIZE = 13f

        /**
         * PR4 / task 4.3b — delay before forwarding to MainActivity so the
         * next-launch feedback prompt fires. Long enough for the user to read
         * the thread + message, short enough not to feel like a dead end.
         */
        private const val CRASH_DETAIL_FORWARD_DELAY_MS = 2500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashJson = intent.getStringExtra(CrashNotificationHelper.EXTRA_CRASH_JSON)
        if (crashJson.isNullOrBlank()) {
            finish()
            return
        }

        val parsed = runCatching { JSONObject(crashJson) }.getOrNull()
        if (parsed == null) {
            finish()
            return
        }

        val timestamp = parsed.optLong("timestamp", 0L)
        val threadName = parsed.optString("threadName", "—")
        val message = parsed.optString("message", "—")
        val stackTrace = parsed.optString("stackTrace", "")

        val timestampText =
            if (timestamp > 0L) {
                SimpleDateFormat(TIMESTAMP_FORMAT, Locale.getDefault()).format(Date(timestamp))
            } else {
                "—"
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#0D1322"))
                setPadding(32, 48, 32, 32)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

        val title =
            TextView(this).apply {
                text = getString(R.string.debug_crash_detail_title)
                setTextColor(Color.parseColor("#DDE2F8"))
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            }
        root.addView(title)

        val header =
            TextView(this).apply {
                text =
                    buildString {
                        appendLine("Timestamp: $timestampText")
                        append("Thread:    ").append(threadName)
                    }
                setTextColor(Color.parseColor("#C2C6D6"))
                textSize = BODY_TEXT_SIZE
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(0, 24, 0, 12)
            }
        root.addView(header)

        val messageLabel =
            TextView(this).apply {
                text = "Message"
                setTextColor(Color.parseColor("#FFB4AB"))
                textSize = BODY_TEXT_SIZE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 12, 0, 4)
            }
        root.addView(messageLabel)

        val messageView =
            TextView(this).apply {
                text = message
                setTextColor(Color.parseColor("#DDE2F8"))
                textSize = 14f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(0, 0, 0, 12)
            }
        root.addView(messageView)

        val stackLabel =
            TextView(this).apply {
                text = "Stack trace"
                setTextColor(Color.parseColor("#FFB4AB"))
                textSize = BODY_TEXT_SIZE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 12, 0, 4)
            }
        root.addView(stackLabel)

        val scroll =
            ScrollView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                setBackgroundColor(Color.parseColor("#161F33"))
            }

        val stackView =
            TextView(this).apply {
                text = if (stackTrace.isBlank()) "(empty)" else stackTrace
                setTextColor(Color.parseColor("#DDE2F8"))
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(16, 16, 16, 16)
                setTextIsSelectable(true)
                ellipsize = TextUtils.TruncateAt.END
            }
        scroll.addView(stackView)
        root.addView(scroll)

        val buttonRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 16, 0, 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT,
                    )
            }

        val copyButton =
            Button(this).apply {
                text = getString(R.string.debug_crash_copy)
                setOnClickListener {
                    val combined =
                        buildString {
                            appendLine("Timestamp: $timestampText")
                            appendLine("Thread:    $threadName")
                            appendLine("Message:   $message")
                            appendLine()
                            appendLine("Stack trace:")
                            append(stackTrace)
                        }
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NEXO crash", combined))
                    Toast
                        .makeText(
                            this@CrashDetailActivity,
                            getString(R.string.debug_log_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        buttonRow.addView(copyButton)

        val shareButton =
            Button(this).apply {
                text = getString(R.string.debug_crash_share)
                setOnClickListener {
                    val combined =
                        buildString {
                            appendLine("Timestamp: $timestampText")
                            appendLine("Thread:    $threadName")
                            appendLine("Message:   $message")
                            appendLine()
                            appendLine("Stack trace:")
                            append(stackTrace)
                        }
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "NEXO crash (debug)")
                            putExtra(Intent.EXTRA_TEXT, combined)
                        }
                    startActivity(Intent.createChooser(send, getString(R.string.debug_crash_share)))
                }
            }
        val shareParams =
            LinearLayout
                .LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = 16 }
        buttonRow.addView(shareButton, shareParams)

        root.addView(buttonRow)
        setContentView(root)

        // PR4 / task 4.3b — once the crash details are visible, the user has
        // an opportunity to copy/share the JSON. After a short delay we forward
        // them to MainActivity so the next-launch feedback prompt fires
        // automatically (it reads feedback_last_event_id from SharedPreferences,
        // which NexoApplication.installCrashHandler persisted before death).
        // We don't block the user — they can still back-press out at any time.
        root.postDelayed({
            runCatching {
                val intent =
                    android.content
                        .Intent(this, com.nexo.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                finish()
            }
        }, CRASH_DETAIL_FORWARD_DELAY_MS)
    }
}
