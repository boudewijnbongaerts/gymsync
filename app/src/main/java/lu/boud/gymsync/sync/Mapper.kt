package lu.boud.gymsync.sync

import lu.boud.gymsync.api.ActivityItem
import lu.boud.gymsync.api.PhysicalActivity
import lu.boud.gymsync.api.SessionDetail
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Health Connect exercise types, mirrored as an enum so this file has no Android/HC imports. */
enum class HcExerciseType { BIKING_STATIONARY, RUNNING_TREADMILL, ROWING_MACHINE, ELLIPTICAL, STAIR_CLIMBING_MACHINE, STRENGTH_TRAINING, OTHER_WORKOUT }

/** One Health Connect exercise session plus its calorie (and optional distance) companion records. */
data class PlannedSession(
    val clientRecordId: String,
    val type: HcExerciseType,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val title: String,
    val kcal: Double,
    val distanceMeters: Double?,
    val deviceModel: String,
) {
    val calClientRecordId get() = clientRecordId.replaceFirst(":", ":cal:") // gymsync:cal:{user}:{idCr}:{part}
}

data class MapResult(val sessions: List<PlannedSession>, val warnings: List<String>)

class MappingException(message: String) : Exception(message)

/**
 * Pure mapping from API models to a plan of Health Connect records. No Android imports.
 * Rules (see API_NOTES.md "Mapping decisions"):
 *  - only `WorkoutSession` items; only exercises with a performed record (doneOn);
 *  - cardio block [startedOn, lastCardioDoneOn] when all cardio exercises come first, strength block after it;
 *  - calories per block = sum of that block's exercise calories; distance = sum of cardio HDistance (metres);
 *  - any malformed/unknown value -> MappingException (fail closed for that session).
 */
object Mapper {
    const val RECORD_VERSION = 1L
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")

    fun parseTime(s: String): OffsetDateTime = try {
        OffsetDateTime.parse(s, fmt)
    } catch (e: DateTimeParseException) {
        throw MappingException("Bad timestamp '$s'")
    }

    fun cardioType(equipmentName: String?, activityName: String?, equipmentType: String?): HcExerciseType {
        val s = listOfNotNull(equipmentName, activityName, equipmentType).joinToString(" ").lowercase()
        return when {
            listOf("bike", "cycle", "recline", "skillbike").any { it in s } -> HcExerciseType.BIKING_STATIONARY
            listOf("run", "treadmill", "skillrun").any { it in s } -> HcExerciseType.RUNNING_TREADMILL
            listOf("row", "skillrow").any { it in s } -> HcExerciseType.ROWING_MACHINE
            listOf("synchro", "vario", "cross", "elliptical").any { it in s } -> HcExerciseType.ELLIPTICAL
            listOf("climb", "stair").any { it in s } -> HcExerciseType.STAIR_CLIMBING_MACHINE
            else -> HcExerciseType.OTHER_WORKOUT
        }
    }

    private class Done(val pa: PhysicalActivity, val doneOn: OffsetDateTime, val kcal: Double, val distanceM: Double?, val durationS: Double?)

    private fun prop(pa: PhysicalActivity, name: String): Double? =
        pa.performedPhysicalActivity?.data?.data?.firstOrNull { it.physicalProperty == name }?.value

    /** Maps one visit to 0, 1 or 2 planned sessions. */
    fun planSessions(userId: String, item: ActivityItem, detail: SessionDetail): MapResult {
        val warnings = mutableListOf<String>()
        if (item.type != "WorkoutSession") return MapResult(emptyList(), listOf("skip ${item.idCr}: type ${item.type}"))
        if (detail.idCr != item.idCr) throw MappingException("Detail idCr ${detail.idCr} != ${item.idCr}")
        val start = parseTime(detail.startedOn)

        val done = detail.physicalActivities
            .filter { it.performedPhysicalActivity?.doneOn != null }
            .sortedBy { it.position ?: Int.MAX_VALUE }
            .map { pa ->
                val kcal = prop(pa, "Calories") ?: throw MappingException("Exercise '${pa.physicalActivityName}' has no Calories value")
                if (kcal < 0 || kcal > 5000) throw MappingException("Implausible calories $kcal")
                val dist = prop(pa, "HDistance")?.also { if (it < 0 || it > 200_000) throw MappingException("Implausible distance $it") }
                Done(pa, parseTime(pa.performedPhysicalActivity!!.doneOn!!), kcal, dist, prop(pa, "Duration"))
            }
        if (done.isEmpty()) return MapResult(emptyList(), listOf("skip ${item.idCr}: no performed exercises"))

        // Timeline sanity: doneOn must be non-decreasing and after start.
        var prev = start
        for (d in done) {
            if (d.doneOn.isBefore(prev)) throw MappingException("Exercise end ${d.doneOn} before ${prev}")
            prev = d.doneOn
        }
        val end = done.last().doneOn
        if (end == start) throw MappingException("Zero-length session")

        val cardio = done.filter { it.pa.isCardio }
        val strength = done.filter { !it.pa.isCardio }
        val title = "Gym · " + (detail.displayName ?: detail.name ?: item.name ?: "Workout")
        val firstMachine = done.first().pa.equipmentName ?: done.first().pa.physicalActivityName ?: "Technogym"
        fun id(part: String) = "gymsync:$userId:${item.idCr}:$part"

        val sessions = mutableListOf<PlannedSession>()
        val cardioFirst = cardio.isNotEmpty() && done.take(cardio.size).all { it.pa.isCardio }
        when {
            cardio.isNotEmpty() && strength.isNotEmpty() && cardioFirst -> {
                val cardioEnd = cardio.last().doneOn
                val c0 = cardio.first()
                sessions += PlannedSession(id("cardio"), cardioType(c0.pa.equipmentName, c0.pa.physicalActivityName, c0.pa.equipmentType),
                    start, cardioEnd, title, cardio.sumOf { it.kcal }, cardio.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(), firstMachine)
                sessions += PlannedSession(id("strength"), HcExerciseType.STRENGTH_TRAINING,
                    cardioEnd, end, title, strength.sumOf { it.kcal }, null, strength.first().pa.equipmentName ?: firstMachine)
            }
            cardio.isNotEmpty() && strength.isEmpty() -> {
                val c0 = cardio.first()
                sessions += PlannedSession(id("cardio"), cardioType(c0.pa.equipmentName, c0.pa.physicalActivityName, c0.pa.equipmentType),
                    start, end, title, cardio.sumOf { it.kcal }, cardio.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(), firstMachine)
            }
            cardio.isEmpty() -> {
                sessions += PlannedSession(id("strength"), HcExerciseType.STRENGTH_TRAINING, start, end, title, strength.sumOf { it.kcal }, null, firstMachine)
            }
            else -> {
                // Cardio and strength interleaved: one session for the whole visit, no split.
                warnings += "session ${item.idCr}: interleaved cardio/strength, written as one STRENGTH_TRAINING session"
                sessions += PlannedSession(id("all"), HcExerciseType.STRENGTH_TRAINING, start, end, title, done.sumOf { it.kcal },
                    cardio.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(), firstMachine)
            }
        }
        // Cross-check against the session total (data is per-exercise and exact; a mismatch is only a warning).
        val total = detail.doneSummary.firstOrNull { it.physicalProperty == "Calories" }?.value ?: item.calories?.toDouble()
        val sum = sessions.sumOf { it.kcal }
        if (total != null && kotlin.math.abs(total - sum) > 2.0) warnings += "session ${item.idCr}: exercise calories $sum != session total $total"
        // Invariants: every block has positive length; sessions touch and never overlap.
        sessions.forEach { if (!it.start.isBefore(it.end)) throw MappingException("Zero-length block ${it.clientRecordId}") }
        sessions.zipWithNext().forEach { (a, b) -> if (b.start != a.end) throw MappingException("Sessions do not touch") }
        return MapResult(sessions, warnings)
    }
}
