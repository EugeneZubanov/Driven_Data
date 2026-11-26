package com.quietnight.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.quietnight.app.data.Session
import com.quietnight.app.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the widget shows; plain values so it can be tested without a launcher. */
data class WidgetContent(val score: String, val title: String, val detail: String) {
    companion object {
        fun of(sessions: List<Session>): WidgetContent {
            val last = sessions.filter { !it.demo && it.countsForTrends }.maxByOrNull { it.startMs }
                ?: return WidgetContent("–", "Quiet Night", "No nights yet. Tap to record.")
            val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(last.endMs))
            return WidgetContent(
                last.score.toString(),
                "Last night · $day",
                "Snoring ${formatDuration(last.snoringSec)} of ${formatDuration(last.durationSec)}",
            )
        }
    }
}

/** Home-screen widget with last night's score. Refreshed when nights change and once a day by the launcher. */
class LastNightWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as? QuietNightApp ?: return
        // Until nights have loaded, keep what the widget already shows; the app's collector refreshes it after.
        if (!app.repository.loaded.value) return
        runCatching { render(context, manager, ids, app.repository.sessions.value) }
    }

    companion object {
        /** Pushes fresh content to every placed widget; cheap no-op when none are placed. */
        fun refresh(context: Context, sessions: List<Session>) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = try {
                manager.getAppWidgetIds(ComponentName(context, LastNightWidget::class.java))
            } catch (_: RuntimeException) {
                return // widget service unavailable (e.g. some test or restricted environments)
            }
            if (ids.isNotEmpty()) render(context, manager, ids, sessions)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, sessions: List<Session>) {
            val content = WidgetContent.of(sessions)
            val open = PendingIntent.getActivity(
                context, 3,
                android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_last_night).apply {
                setTextViewText(R.id.widget_score, content.score)
                setTextViewText(R.id.widget_title, content.title)
                setTextViewText(R.id.widget_detail, content.detail)
                setContentDescription(R.id.widget_root, "${content.title}. Score ${content.score}. ${content.detail}")
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
