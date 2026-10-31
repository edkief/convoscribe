package com.convoscribe.ui.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import com.convoscribe.R
import com.convoscribe.data.ActionItemEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/**
 * Sending an action item out of the app (issue #31): as text to any
 * app ([share]) or as an all-day calendar event on its due date
 * ([addToCalendar]). [sessionTitle] and [sessionCreatedAt] say where
 * it came from.
 */
object ActionItemIntents {

    /** The item as text: what, owner, due date and the session it came from, a line each. */
    fun text(context: Context, item: ActionItemEntity, sessionTitle: String, sessionCreatedAt: Long): String =
        buildList {
            add(item.text)
            item.owner?.let { add(context.getString(R.string.action_item_share_owner, it)) }
            dueLabel(item.dueDate)?.let { add(context.getString(R.string.action_item_share_due, it)) }
            add(context.getString(R.string.action_item_share_from, sessionTitle, dateLabel(sessionCreatedAt)))
        }.joinToString("\n")

    /** `ACTION_SEND` of [text]. */
    fun shareIntent(context: Context, item: ActionItemEntity, sessionTitle: String, sessionCreatedAt: Long): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, item.text)
            .putExtra(Intent.EXTRA_TEXT, text(context, item, sessionTitle, sessionCreatedAt))

    /**
     * `ACTION_INSERT` of a calendar event titled with the item, the
     * rest of [text] as its description, and an all-day event on the
     * due date when there is one; the calendar app asks for a date
     * otherwise.
     */
    fun calendarIntent(context: Context, item: ActionItemEntity, sessionTitle: String, sessionCreatedAt: Long): Intent {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, item.text)
            .putExtra(
                CalendarContract.Events.DESCRIPTION,
                text(context, item, sessionTitle, sessionCreatedAt).substringAfter('\n')
            )
        dueDate(item.dueDate)?.let { day ->
            // All-day events are stored at UTC midnight.
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        return intent
    }

    fun share(context: Context, item: ActionItemEntity, sessionTitle: String, sessionCreatedAt: Long) {
        val chooser = Intent.createChooser(
            shareIntent(context, item, sessionTitle, sessionCreatedAt),
            context.getString(R.string.action_item_share_chooser)
        )
        context.startActivity(chooser)
    }

    /** Opens the calendar app on a new event, or says there is none. */
    fun addToCalendar(context: Context, item: ActionItemEntity, sessionTitle: String, sessionCreatedAt: Long) {
        try {
            context.startActivity(calendarIntent(context, item, sessionTitle, sessionCreatedAt))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.action_item_no_calendar, Toast.LENGTH_LONG).show()
        }
    }

    /** [dueDate] (`yyyy-MM-dd`) as the user reads dates, or `null` when there is none. */
    fun dueLabel(dueDate: String?): String? =
        dueDate(dueDate)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    fun dueDate(dueDate: String?): LocalDate? = dueDate?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun dateLabel(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}
