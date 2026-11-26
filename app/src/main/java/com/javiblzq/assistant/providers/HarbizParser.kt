package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.HarbizExercise
import com.javiblzq.assistant.data.HarbizSet
import com.javiblzq.assistant.data.HarbizWorkout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.round

data class HarbizCalSummary(
    val id: String,
    val title: String,
    val scheduledAtMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean,
    val removed: Boolean,
    val eventType: String = "workout",
    val raw: JSONObject = JSONObject()
)

object HarbizParser {
    private val cardioWords = Regex(
        """\b(run|running|carrera|rodaje|series|fartlek|tempo|cuestas|trail|km|z[1-5]|cardio)\b""",
        RegexOption.IGNORE_CASE
    )
    private val htmlTags = Regex("<[^>]*>")
    private val spaces = Regex("[\\s\\u00a0]+")
    private val summaryFields = listOf("target", "description", "notes", "comments")

    fun parseEjsonTimestampMs(value: Any?): Long? {
        if (value == null || value === JSONObject.NULL) return null
        val raw: Any = if (value is JSONObject) {
            value.opt("\$date") ?: return null
        } else {
            value
        }
        return when (raw) {
            is Number -> raw.toLong()
            is String -> parseIsoToMs(raw)
            else -> null
        }
    }

    private fun parseIsoToMs(raw: String): Long? {
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val d = fmt.parse(raw)
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun toLocalDateIso(epochMs: Long, timezoneId: String = "Europe/Madrid"): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone(timezoneId)
        return fmt.format(Date(epochMs))
    }

    fun discoverWorkoutEvents(rawArray: JSONArray): List<HarbizCalSummary> {
        val seen = HashSet<String>()
        val out = mutableListOf<HarbizCalSummary>()
        for (i in 0 until rawArray.length()) {
            val item = rawArray.optJSONObject(i) ?: continue
            if (!isWorkoutEvent(item)) continue
            val id = item.optString("_id", "")
            if (id.isEmpty() || id in seen) continue
            val removed = item.optBoolean("removed", false)
            if (removed) continue
            val scheduledAt = parseEjsonTimestampMs(item.opt("date")) ?: continue
            val updatedAt = parseEjsonTimestampMs(item.opt("updatedAt")) ?: scheduledAt
            val title = item.optString("title", "Entrenamiento").ifBlank { "Entrenamiento" }.take(200)
            val completed = item.optBoolean("completed", false)
            seen.add(id)
            out.add(
                HarbizCalSummary(
                    id = id,
                    title = title,
                    scheduledAtMs = scheduledAt,
                    updatedAtMs = updatedAt,
                    completed = completed,
                    removed = false,
                    eventType = eventType(item),
                    raw = item
                )
            )
        }
        return out.sortedByDescending { it.scheduledAtMs }
    }

    fun parseWorkoutDetail(
        detail: JSONObject,
        timezoneId: String = "Europe/Madrid",
        summary: HarbizCalSummary? = null
    ): HarbizWorkout? {
        val usableDetail = detail.takeIf { summary == null || detail.optString("_id", summary.id) == summary.id }
            ?: JSONObject()
        val raw = mergeObjects(summary?.raw, usableDetail)
        if (summary?.eventType == "cardio") raw.put("type", "cardio")
        val id = raw.optString("_id", "")
        if (id.isEmpty() || !isWorkoutEvent(raw) || raw.optBoolean("removed", false)) return null
        val scheduledAtMs = parseEjsonTimestampMs(raw.opt("date")) ?: return null
        val updatedAtMs = parseEjsonTimestampMs(raw.opt("updatedAt")) ?: scheduledAtMs
        val title = raw.optString("title", "Entrenamiento").ifBlank { "Entrenamiento" }.take(200)
        val completed = raw.optBoolean("completed", false)
        val workoutObj = raw.optJSONObject("workout") ?: JSONObject()
        val rawExercises = workoutObj.optJSONArray("exercises") ?: raw.optJSONArray("exercises") ?: JSONArray()

        val exercises = mutableListOf<HarbizExercise>()
        val cardioExercises = mutableListOf<Pair<String, String?>>()
        var hasWeight = false
        var nodeCount = 0

        fun visit(node: JSONObject, group: Int?, path: String, depth: Int) {
            if (depth > 3 || ++nodeCount > 400) return
            val children = node.optJSONArray("supersetExercises")
            val rawName = nonEmpty(node, "name_provisional") ?: nonEmpty(node, "supersetName_provisional")
            val exId = nonEmpty(node, "exerciseId") ?: nonEmpty(node, "supersetExerciseId")
            val executable = node.optString("type") == "simple" || exId != null
            if (rawName != null && cardioWords.containsMatchIn(rawName)) {
                cardioExercises.add(rawName to firstSummary(node))
            }
            if ((parseNumber(node.opt("weightKg")) ?: parseNumber(node.opt("weight")) ?: 0.0) > 0.0) {
                hasWeight = true
            }

            if (executable) {
                val sourceName = (rawName ?: "Ejercicio").take(200)
                val catalog = SemyraCatalog.resolveCatalogExercise(sourceName)
                val sourceId = exId ?: "path:$path"
                val target = nonEmpty(node, "target") ?: nonEmpty(node, "supersetTarget")
                val rest = nonEmpty(node, "rest") ?: nonEmpty(node, "supersetRest")
                val statusDone = node.optString("status") == "completed"

                val resultSets = node.optJSONObject("result")?.optJSONArray("sets") ?: JSONArray()
                val parsedSets = mutableListOf<HarbizSet>()
                for (i in 0 until resultSets.length().coerceAtMost(100)) {
                    val s = resultSets.optJSONObject(i) ?: continue
                    val reps = parseNumber(s.opt("reps"))?.toInt()
                    val weight = parseNumber(s.opt("weight")) ?: parseNumber(s.opt("weightKg"))
                    val durationS = parseNumber(s.opt("durationS"))?.toInt()
                    val setType = nonEmpty(s, "setType")
                    val setDone = statusDone && (!s.has("completed") || s.optBoolean("completed", true))
                    parsedSets.add(
                        HarbizSet(
                            setNumber = i + 1,
                            reps = reps,
                            weightKg = weight,
                            durationS = durationS,
                            setType = setType,
                            completed = setDone
                        )
                    )
                }

                exercises.add(
                    HarbizExercise(
                        order = exercises.size + 1,
                        sourceId = sourceId,
                        displayName = catalog?.displayName ?: sourceName,
                        catalogId = catalog?.id,
                        target = target,
                        rest = rest,
                        supersetGroup = group,
                        completed = statusDone || (parsedSets.isNotEmpty() && parsedSets.all { it.completed }),
                        sets = parsedSets
                    )
                )
            }

            if (children != null) {
                val nextGroup = group ?: (exercises.size + 1)
                for (i in 0 until children.length()) {
                    val child = children.optJSONObject(i) ?: continue
                    visit(child, nextGroup, "$path.$i", depth + 1)
                }
            }
        }

        for (i in 0 until rawExercises.length()) {
            val exObj = rawExercises.optJSONObject(i) ?: continue
            visit(exObj, null, i.toString(), 0)
        }

        val workingSets = exercises.flatMap { it.sets }
            .filter { it.completed && it.setType != "warmup" }
        val volumeLoadKg = round(
            workingSets.sumOf { s ->
                val w = s.weightKg ?: 0.0
                val r = s.reps ?: 0
                if (w > 0.0 && r > 0) w * r else 0.0
            } * 10.0
        ) / 10.0

        val type = eventType(raw)
        val isCardio = type == "cardio" || hasCardioContent(raw) || hasCardioContent(workoutObj) || (
            !hasWeight && exercises.none { ex -> ex.sets.any { (it.weightKg ?: 0.0) > 0.0 } } &&
                (cardioWords.containsMatchIn(title) || cardioExercises.isNotEmpty())
            )
        val subtitle = if (isCardio) {
            val cardio = raw.optJSONObject("cardio") ?: workoutObj.optJSONObject("cardio")
            val exercise = cardioExercises.firstOrNull { it.second != null } ?: cardioExercises.firstOrNull()
            val plannedCardio = (raw.opt("cardioText") as? String)?.let(::cleanText)?.takeIf { it.isNotEmpty() }
            val cardioText = (raw.opt("cardio") as? String)?.let(::cleanText)?.takeIf { hasCardioContent(raw) }
            val result = raw.optJSONObject("resultCardio")
            val candidate = plannedCardio ?: firstSummary(cardio) ?: cardioText?.takeIf { it.isNotEmpty() }
                ?: firstSummary(raw) ?: firstSummary(workoutObj)
                ?: exercise?.second ?: exercise?.first?.takeUnless { it.equals(title, ignoreCase = true) }
                ?: resultCardioSummary(result?.optJSONObject("target")) ?: firstSummary(result)
            candidate.orEmpty().take(60).trimEnd()
        } else ""
        val match = SemyraCatalog.suggestTemplate(exercises.map { it.catalogId })
        return HarbizWorkout(
            id = id,
            title = title,
            scheduledDateIso = toLocalDateIso(scheduledAtMs, timezoneId),
            scheduledAtMs = scheduledAtMs,
            updatedAtMs = updatedAtMs,
            completed = completed,
            routineName = match.templateName,
            exercises = exercises,
            workingSetsCount = workingSets.size,
            volumeLoadKg = volumeLoadKg,
            eventType = type,
            isCardioOrRun = isCardio,
            summarySubtitle = subtitle
        )
    }

    private fun eventType(raw: JSONObject): String {
        val type = raw.optString("type")
        if (type.equals("workout", true)) return "workout"
        return if (type.equals("cardio", true) || cardioWords.containsMatchIn(type) ||
            hasCardioContent(raw) || raw.optJSONObject("workout")?.let(::hasCardioContent) == true
        ) "cardio" else "workout"
    }

    private fun hasCardioContent(raw: JSONObject): Boolean {
        if ((raw.opt("cardioText") as? String)?.isNotBlank() == true || raw.optJSONObject("resultCardio") != null) {
            return true
        }
        return when (val cardio = raw.opt("cardio")) {
            is JSONObject -> true
            is JSONArray -> cardio.length() > 0
            is Boolean -> cardio
            is String -> cardio.isNotBlank() && !cardio.equals("false", true)
            else -> false
        }
    }

    private fun isWorkoutEvent(raw: JSONObject): Boolean =
        raw.optString("type").equals("workout", true) || eventType(raw) == "cardio" ||
            hasCardioContent(raw) || cardioWords.containsMatchIn(raw.optString("title")) ||
            hasRunningExercise(raw.optJSONObject("workout")?.optJSONArray("exercises") ?: raw.optJSONArray("exercises"))

    private fun hasRunningExercise(exercises: JSONArray?): Boolean {
        if (exercises == null) return false
        for (i in 0 until exercises.length().coerceAtMost(400)) {
            val node = exercises.optJSONObject(i) ?: continue
            if (cardioWords.containsMatchIn(node.optString("name_provisional"))) return true
        }
        return false
    }

    private fun mergeObjects(summary: JSONObject?, detail: JSONObject): JSONObject {
        if (summary == null) return detail
        val merged = JSONObject()
        summary.keys().forEach { key -> merged.put(key, summary.opt(key)) }
        detail.keys().forEach { key ->
            val value = detail.opt(key)
            if (value == null || value === JSONObject.NULL || (value is String && value.isBlank())) return@forEach
            merged.put(key, if (value is JSONObject) mergeObjects(summary.optJSONObject(key), value) else value)
        }
        return merged
    }

    private fun firstSummary(source: JSONObject?): String? {
        source ?: return null
        for (key in summaryFields) {
            val text = (source.opt(key) as? String)?.let(::cleanText)
            if (!text.isNullOrBlank()) return text
        }
        val durationS = parseNumber(source.opt("durationS")) ?: parseNumber(source.opt("duration"))
        val distanceM = parseNumber(source.opt("distanceM")) ?: parseNumber(source.opt("distance"))
        val distance = distanceM?.takeIf { it > 0 }?.let {
            val km = it / 1000
            "${String.format(Locale.US, "%.2f", km).trimEnd('0').trimEnd('.')} KM"
        }
        val duration = durationS?.let(::formatCardioDuration)
        val zone = (source.opt("zone") as? String)?.let(::cleanText)?.takeIf { it.isNotBlank() }
        val timeAndZone = listOfNotNull(duration, zone).joinToString(" ").takeIf { it.isNotEmpty() }
        return listOfNotNull(distance, timeAndZone).joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    private fun resultCardioSummary(target: JSONObject?): String? {
        target ?: return null
        val time = target.optJSONObject("time") ?: return null
        val duration = (parseNumber(time.opt("hours")) ?: 0.0) * 3600 +
            (parseNumber(time.opt("minutes")) ?: 0.0) * 60 + (parseNumber(time.opt("seconds")) ?: 0.0)
        return formatCardioDuration(duration)
    }

    private fun formatCardioDuration(seconds: Double): String? = when {
        seconds >= 60 -> "${(seconds / 60).toInt()}'"
        seconds >= 1 -> "${seconds.toInt()} s"
        else -> null
    }

    private fun cleanText(text: String): String = text
        .replace(htmlTags, " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace('’', '\'')
        .replace("&amp;", "&")
        .replace(spaces, " ")
        .trim()

    private fun nonEmpty(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)?.toString()?.trim() ?: return null
        return if (v.isEmpty() || v == "null") null else v
    }

    private fun parseNumber(value: Any?): Double? {
        if (value == null || value === JSONObject.NULL) return null
        return when (value) {
            is Number -> {
                val d = value.toDouble()
                if (d.isFinite() && d >= 0.0 && d <= 1_000_000.0) d else null
            }
            is String -> {
                val clean = value.trim().replace(",", ".")
                val d = clean.toDoubleOrNull()
                if (d != null && d.isFinite() && d >= 0.0 && d <= 1_000_000.0) d else null
            }
            else -> null
        }
    }
}
