package lu.boud.gymsync

import kotlinx.serialization.json.Json
import lu.boud.gymsync.api.ActivityHistory
import lu.boud.gymsync.api.ActivityItem
import lu.boud.gymsync.api.Envelope
import lu.boud.gymsync.api.SessionDetail

object Fixtures {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun text(name: String): String =
        Fixtures::class.java.getResource("/fixtures/$name")?.readText() ?: error("fixture $name missing")

    fun history(): ActivityHistory = json.decodeFromString<Envelope<ActivityHistory>>(text("activity_history_page1.json")).data!!

    fun item(idCr: Long): ActivityItem = history().items.first { it.idCr == idCr }

    fun detail(idCr: Long): SessionDetail = json.decodeFromString<Envelope<SessionDetail>>(text("session_$idCr.json")).data!!

    const val USER = "00000000-0000-0000-0000-00000000user"
}
