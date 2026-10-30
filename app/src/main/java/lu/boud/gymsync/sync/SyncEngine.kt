package lu.boud.gymsync.sync

import lu.boud.gymsync.api.ActivityItem
import lu.boud.gymsync.api.ApiException
import lu.boud.gymsync.api.MywellnessApi
import lu.boud.gymsync.api.Session
import lu.boud.gymsync.data.Credentials
import java.time.LocalDate
import java.time.ZoneId

/** Outcome of one sync run. `error` null means clean. */
data class SyncOutcome(
    val sessionsWritten: Int,
    val newDoneIds: Set<String>,
    val error: String?,
    val retryable: Boolean,
    val warnings: List<String>,
)

/**
 * fetch -> map -> write, with pacing (<= 1 request/second) and fail-closed per session.
 * Android-free except for the injected writer, so it can be driven by MockWebServer tests.
 */
class SyncEngine(
    private val api: MywellnessApi,
    private val write: suspend (List<PlannedSession>) -> Int,
    private val sleep: suspend (Long) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
) {
    companion object {
        const val FIRST_RUN_DAYS = 30L
        const val NORMAL_DAYS = 7L
        const val MAX_PAGES = 5
        const val PACE_MS = 1000L
    }

    suspend fun run(creds: Credentials, firstRunDone: Boolean, alreadyDone: Set<String>): SyncOutcome {
        val warnings = mutableListOf<String>()
        val session = try {
            api.login(creds.email, creds.password)
        } catch (e: ApiException) {
            return SyncOutcome(0, emptySet(), e.message ?: "Login failed", retryable = e is ApiException.Network || e is ApiException.Http && e.code >= 500 || e is ApiException.Http && e.code == 429, warnings)
        }

        val days = if (firstRunDone) NORMAL_DAYS else FIRST_RUN_DAYS
        val cutoff = today().minusDays(days).let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }

        // 1. Session list, paged while the oldest item is still inside the window.
        val items = mutableListOf<ActivityItem>()
        var pager: String? = null
        var pages = 0
        var partial: String? = null
        while (true) {
            val page = try {
                api.activityHistory(session, pager)
            } catch (e: ApiException) {
                if (pages == 0) return SyncOutcome(0, emptySet(), "Session list: ${e.message}", retryable = e is ApiException.Network || e is ApiException.Http, warnings)
                partial = "Could not load older sessions (${e.message})"; break
            }
            pages++
            items += page.items.filter { it.partitionDate >= cutoff }
            val oldest = page.items.lastOrNull()?.partitionDate
            if (page.items.isEmpty() || oldest == null || oldest < cutoff || page.pager.isNullOrBlank() || pages >= MAX_PAGES) break
            pager = page.pager
            sleep(PACE_MS)
        }
        val unknownTypes = items.filter { it.type != "WorkoutSession" }
        if (unknownTypes.isNotEmpty()) warnings += "${unknownTypes.size} item(s) of other types skipped: ${unknownTypes.map { it.type }.distinct()}"
        val todo = items.filter { it.type == "WorkoutSession" && it.idCr.toString() !in alreadyDone }

        // 2. Details + mapping, fail closed per session.
        val plans = mutableListOf<PlannedSession>()
        val doneNow = mutableSetOf<String>()
        val failed = mutableListOf<String>()
        var sess: Session = session
        var relogged = false
        for (item in todo) {
            sleep(PACE_MS)
            val detail = try {
                api.sessionDetail(sess, item.idCr, item.partitionDate)
            } catch (e: ApiException.TokenNotValid) {
                if (relogged) { failed += "${item.idCr}: token"; continue }
                relogged = true
                try {
                    sess = api.login(creds.email, creds.password)
                    api.sessionDetail(sess, item.idCr, item.partitionDate)
                } catch (e2: ApiException) { failed += "${item.idCr}: ${e2.message}"; continue }
            } catch (e: ApiException) {
                failed += "${item.idCr}: ${e.message}"; continue
            }
            try {
                val r = Mapper.planSessions(sess.userId, item, detail)
                warnings += r.warnings
                if (r.sessions.isNotEmpty()) { plans += r.sessions; doneNow += item.idCr.toString() }
            } catch (e: MappingException) {
                failed += "${item.idCr}: ${e.message}"
            }
        }

        // 3. Write everything that mapped cleanly (upsert, so re-runs are harmless).
        val written = try {
            write(plans)
        } catch (e: Exception) {
            return SyncOutcome(0, emptySet(), "Health Connect write failed: ${e.message}", retryable = false, warnings)
        }

        val error = buildList {
            if (failed.isNotEmpty()) add("${failed.size} session(s) skipped: " + failed.joinToString("; ").take(200))
            partial?.let { add(it) }
        }.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        return SyncOutcome(written, doneNow, error, retryable = false, warnings)
    }
}
