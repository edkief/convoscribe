package com.convoscribe.llm

import com.convoscribe.data.SearchText
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * An action item read from a summary (issue #31): what is to be done,
 * who will do it, by when (`yyyy-MM-dd`) and where in the recording
 * it was agreed; each `null` when the summary does not say.
 */
data class ExtractedActionItem(
    val text: String,
    val owner: String? = null,
    val due: String? = null,
    val atMs: Long? = null
)

/**
 * A summary split into what the user reads and its action items.
 *
 * @property prose the summary without the JSON block.
 * @property items the action items, in the order the summary gives them.
 * @property fromJson `true` when [items] came from the JSON block,
 *   `false` when it was missing or unreadable and they came from the
 *   summary's *Action items* bullet list.
 */
data class ParsedSummary(
    val prose: String,
    val items: List<ExtractedActionItem>,
    val fromJson: Boolean
)

/**
 * Reads the action items of a summary (issue #31). The model is asked
 * ([PromptBuilder.ACTION_ITEMS_CONTRACT]) to end the summary with a
 * ```` ```json ```` block holding an array of
 * `{"text", "owner", "due", "at"}` objects; [split] takes it off the
 * prose and decodes it leniently. When there is no block, or it does
 * not decode, the bullets under the summary's *Action items* heading
 * are read instead.
 *
 * Owners are matched to the session's [knownNames] (speaker labels and
 * participants), ignoring case and accents, so an item says "Alice"
 * the way the transcript does.
 */
object ActionItemsParser {

    private val json = Json { isLenient = true }

    private const val FENCE = "```"

    private val TIME = Regex("\\b((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\b")

    /** A transcript timestamp in a bullet: always bracketed, so "at 14:30" stays a clock time. */
    private val BRACKETED_TIME = Regex("\\[((?:\\d{1,2}:)?\\d{1,2}:\\d{2})]")

    private val ISO_DATE = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b")

    private val BULLET = Regex("^\\s*(?:[-*•+]|\\d{1,2}[.)])\\s+(.*)$")

    private val HEADING_MARKUP = Regex("^[\\s#*_>]*(?:\\d{1,2}[.)]\\s*)?[\\s*_]*")

    /** Up to three capitalised words: what a name looks like when the session knows none. */
    private const val NAME = "\\p{Lu}[\\p{L}.'-]*(?: \\p{Lu}[\\p{L}.'-]*){0,2}"

    /** "Name: task" or "**Name** — task" at the start of a bullet. */
    private val LEADING_OWNER = Regex("^\\**($NAME)\\**\\s*(?::|—|–| - )\\s*(.+)$")

    /** "task (Name)" or "task (owner: Name)" at the end of a bullet. */
    private val TRAILING_OWNER = Regex("^(.+?)\\s*\\((?:[\\p{L} ]{1,12}:\\s*)?($NAME)\\)\\s*\\.?$")

    /** A bullet marker that is not a number: `-`, `*`, `•` or `+` and a space. */
    private val MARK_BULLET = Regex("^\\s*[-*•+]\\s")

    /** Folded words that mean there are no action items. */
    private val NONE = setOf("none", "nothing", "n a", "na", "aucun", "aucune", "neant", "rien")

    /** Longest heading text that still reads as a heading, not a sentence. */
    private const val MAX_HEADING_CHARS = 40

    /**
     * [text] as the user reads it and its action items: the JSON block
     * when there is one that decodes, otherwise the *Action items*
     * bullet list.
     */
    fun split(text: String, knownNames: List<String> = emptyList()): ParsedSummary {
        val block = findBlock(text)
        val prose = (if (block == null) text else text.substring(0, block.start)).trimEnd()
        val fromJson = block?.let { decode(it.body, knownNames) }
        return if (fromJson != null) {
            ParsedSummary(prose, fromJson, fromJson = true)
        } else {
            ParsedSummary(prose, fromBullets(prose, knownNames), fromJson = false)
        }
    }

    /**
     * What of a summary still streaming in to show: everything before
     * the JSON block, which is cut off as soon as its opening fence
     * starts to arrive.
     */
    fun visible(streaming: String): String {
        findBlock(streaming)?.let { return streaming.substring(0, it.start).trimEnd() }
        val fence = streaming.lastIndexOf(FENCE)
        if (fence >= 0) {
            val rest = streaming.substring(fence + FENCE.length).trimStart()
            if (rest.isEmpty() || "json".startsWith(rest.lowercase())) return streaming.substring(0, fence).trimEnd()
        }
        // A fence on its way: one or two backticks at the very end.
        return streaming.trimEnd('`').let { if (it.length < streaming.length) it.trimEnd() else streaming }
    }

    private class Block(val start: Int, val body: String)

    /**
     * The last ```` ```json ```` block of [text], or the last fenced
     * block that holds an array, up to its closing fence or the end
     * when the reply was cut off; `null` when there is none.
     */
    private fun findBlock(text: String): Block? {
        var index = text.lastIndexOf(FENCE)
        while (index >= 0) {
            val after = text.substring(index + FENCE.length)
            val opener = after.trimStart()
            val isJson = opener.startsWith("json", ignoreCase = true)
            val body = if (isJson) opener.substring("json".length) else opener
            if (isJson || body.trimStart().startsWith("[")) {
                val end = body.indexOf(FENCE)
                return Block(index, if (end >= 0) body.substring(0, end) else body)
            }
            index = if (index == 0) -1 else text.lastIndexOf(FENCE, index - 1)
        }
        return null
    }

    /** The items in a JSON [body], or `null` when it is not an array of items (or an object holding one). */
    private fun decode(body: String, knownNames: List<String>): List<ExtractedActionItem>? {
        val element = try {
            json.parseToJsonElement(body.trim())
        } catch (_: IllegalArgumentException) {
            return null
        }
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element.values.firstOrNull { it is JsonArray } as? JsonArray ?: return null
            else -> return null
        }
        return array.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val text = item.string("text", "task", "item", "action")?.let(::clean) ?: return@mapNotNull null
            ExtractedActionItem(
                text = text,
                owner = item.string("owner", "assignee", "who")?.let { owner(it, knownNames) },
                due = item.string("due", "due_date", "dueDate")?.let(::isoDate),
                atMs = item.string("at", "timestamp", "time")?.let(::timeMs)
            )
        }
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** The bullets under the last *Action items* heading of [prose]. */
    private fun fromBullets(prose: String, knownNames: List<String>): List<ExtractedActionItem> {
        val lines = prose.lines()
        val heading = lines.indexOfLast { line -> headingText(line)?.let(::isActionsHeading) == true }
        if (heading < 0) return emptyList()
        val items = ArrayList<ExtractedActionItem>()
        // "4. Action items: Alice sends the deck" on the heading's own line.
        inlineContent(lines[heading])?.let { inline -> bulletItem(inline, knownNames)?.let(items::add) }
        for (line in lines.drop(heading + 1)) {
            if (line.isBlank()) continue
            val bullet = BULLET.find(line)?.groupValues?.get(1)
            if (bullet == null || isSection(line)) break
            bulletItem(bullet, knownNames)?.let(items::add)
        }
        return items
    }

    /**
     * The title of [line] when it could head a section: not a `-` or
     * `•` bullet, without heading markup and before any `:` content,
     * and short; `null` otherwise.
     */
    private fun headingText(line: String): String? {
        if (MARK_BULLET.containsMatchIn(line)) return null
        val bare = line.replace(HEADING_MARKUP, "").replace("*", "").replace("_", "").trim()
        val title = bare.substringBefore(':').trim()
        return title.takeIf { it.isNotEmpty() && it.length <= MAX_HEADING_CHARS }
    }

    private fun isActionsHeading(title: String): Boolean {
        val folded = SearchText.fold(title)
        return "action" in folded || "next step" in folded || "prochaine" in folded || "a faire" in folded
    }

    /** A numbered line that opens the next section ("5. Follow-ups:"), or a Markdown heading. */
    private fun isSection(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return true
        if (MARK_BULLET.containsMatchIn(line)) return false
        val bare = trimmed.replace("*", "").trim()
        return bare.endsWith(":") && bare.length <= MAX_HEADING_CHARS
    }

    private fun inlineContent(line: String): String? =
        line.replace("*", "").substringAfter(':', "").trim().takeIf { it.isNotEmpty() }

    /** One bullet as an item; `null` for "None" and the like. */
    private fun bulletItem(raw: String, knownNames: List<String>): ExtractedActionItem? {
        var text = clean(raw) ?: return null
        if (isNone(text)) return null
        val atMs = BRACKETED_TIME.find(text)?.let { match ->
            timeMs(match.groupValues[1])?.also { text = text.removeRange(match.range).replace("  ", " ").trim() }
        }
        val due = ISO_DATE.find(text)?.groupValues?.get(1)?.let(::isoDate)
        var owner: String? = null
        LEADING_OWNER.find(text)?.let { match ->
            owner = match.groupValues[1]
            text = match.groupValues[2]
        }
        if (owner == null) {
            owner = knownNames.sortedByDescending { it.length }.firstOrNull { name -> mentions(text, name) }
        }
        if (owner == null) {
            TRAILING_OWNER.find(text)?.let { match ->
                owner = match.groupValues[2]
                text = match.groupValues[1]
            }
        }
        text = text.trim().trimEnd(',', ';').trim()
        if (text.isEmpty()) return null
        return ExtractedActionItem(text = text, owner = owner?.let { owner(it, knownNames) }, due = due, atMs = atMs)
    }

    /** [text] without Markdown emphasis or surrounding whitespace; `null` when nothing is left. */
    private fun clean(text: String): String? =
        text.replace("**", "").replace("__", "").trim().takeIf { it.isNotEmpty() }

    private fun isNone(text: String): Boolean =
        SearchText.terms(text).joinToString(" ").let { it.isEmpty() || it in NONE }

    /** Whether [text] names [name] as a whole word, ignoring case and accents. */
    private fun mentions(text: String, name: String): Boolean {
        val wanted = SearchText.fold(name.trim())
        if (wanted.isEmpty()) return false
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(wanted) + "(?![\\p{L}\\p{N}])").containsMatchIn(
            SearchText.fold(text)
        )
    }

    /** [owner] as the session spells it when it is (or names) one of [knownNames], else as written. */
    internal fun owner(owner: String, knownNames: List<String>): String? {
        val trimmed = owner.trim().trim('*').trim()
        if (trimmed.isEmpty() || isNone(trimmed) || SearchText.fold(trimmed) == "null") return null
        val folded = SearchText.fold(trimmed)
        return knownNames.firstOrNull { SearchText.fold(it.trim()) == folded }
            ?: knownNames.sortedByDescending { it.length }.firstOrNull { mentions(trimmed, it) }
            ?: trimmed
    }

    /** [text] when it is a real `yyyy-MM-dd` date, else `null`. */
    internal fun isoDate(text: String): String? {
        val match = ISO_DATE.find(text) ?: return null
        return try {
            LocalDate.parse(match.groupValues[1]).toString()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `[h:]mm:ss`, bracketed or not, in milliseconds; `null` when [text] holds no time. */
    internal fun timeMs(text: String): Long? {
        val match = TIME.find(text) ?: return null
        val parts = match.groupValues[1].split(':').map { it.toLong() }
        val seconds = parts.fold(0L) { total, part -> total * 60 + part }
        return seconds * 1_000L
    }
}
