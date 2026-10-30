package lu.boud.gymsync

import lu.boud.gymsync.Fixtures.USER
import lu.boud.gymsync.api.ActivityItem
import lu.boud.gymsync.api.PerformedActivity
import lu.boud.gymsync.api.PerformedData
import lu.boud.gymsync.api.PhysicalActivity
import lu.boud.gymsync.api.PhysicalProperty
import lu.boud.gymsync.api.SessionDetail
import lu.boud.gymsync.sync.HcExerciseType
import lu.boud.gymsync.sync.Mapper
import lu.boud.gymsync.sync.MappingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MapperTest {

    @Test
    fun `session 1375 - bike then strength, CET offset after fall-back`() {
        val r = Mapper.planSessions(USER, Fixtures.item(1375), Fixtures.detail(1375))
        assertEquals(2, r.sessions.size)
        val (bike, str) = r.sessions
        assertEquals(HcExerciseType.BIKING_STATIONARY, bike.type)
        assertEquals(OffsetDateTime.parse("2025-10-27T09:02:02+01:00"), bike.start)
        assertEquals(OffsetDateTime.parse("2025-10-27T09:22:02+01:00"), bike.end)
        assertEquals(230.0, bike.kcal, 0.0)
        assertEquals(10189.005379, bike.distanceMeters!!, 0.001)
        assertEquals("gymsync:$USER:1375:cardio", bike.clientRecordId)
        assertEquals("gymsync:cal:$USER:1375:cardio", bike.calClientRecordId)

        assertEquals(HcExerciseType.STRENGTH_TRAINING, str.type)
        assertEquals(bike.end, str.start)
        assertEquals(OffsetDateTime.parse("2025-10-27T09:52:45+01:00"), str.end)
        assertEquals(35.0 + 115 + 37 + 37 + 19, str.kcal, 0.0)
        assertNull(str.distanceMeters)
        assertEquals("gymsync:$USER:1375:strength", str.clientRecordId)
        assertEquals("Gym · Free training", str.title)
        assertTrue("no calorie-sum warning expected: ${r.warnings}", r.warnings.none { "!=" in it })
        assertEquals(ZoneOffset.ofHours(1), bike.start.offset)
    }

    @Test
    fun `session 1366 - CEST offset, HR present, calories sum to total`() {
        val r = Mapper.planSessions(USER, Fixtures.item(1366), Fixtures.detail(1366))
        assertEquals(2, r.sessions.size)
        assertEquals(ZoneOffset.ofHours(2), r.sessions[0].start.offset)
        assertEquals(232.0, r.sessions[0].kcal, 0.0)
        assertEquals(639.0, r.sessions.sumOf { it.kcal }, 0.0)
        assertEquals(OffsetDateTime.parse("2025-10-01T09:52:36+02:00"), r.sessions[1].end)
    }

    @Test
    fun `session 1374 maps and sessions touch`() {
        val r = Mapper.planSessions(USER, Fixtures.item(1374), Fixtures.detail(1374))
        assertEquals(2, r.sessions.size)
        assertEquals(r.sessions[0].end, r.sessions[1].start)
        assertEquals(457.0, r.sessions.sumOf { it.kcal }, 0.0)
    }

    @Test
    fun `clientRecordId is stable across runs`() {
        val a = Mapper.planSessions(USER, Fixtures.item(1375), Fixtures.detail(1375)).sessions.map { it.clientRecordId }
        val b = Mapper.planSessions(USER, Fixtures.item(1375), Fixtures.detail(1375)).sessions.map { it.clientRecordId }
        assertEquals(a, b)
    }

    @Test
    fun `cardio and strength never overlap in any fixture`() {
        for (id in listOf(1375L, 1374L, 1366L)) {
            val s = Mapper.planSessions(USER, Fixtures.item(id), Fixtures.detail(id)).sessions
            s.zipWithNext().forEach { (x, y) -> assertTrue(!y.start.isBefore(x.end)) }
        }
    }

    @Test
    fun `unknown cardio machine falls back to OTHER_WORKOUT, known names map`() {
        assertEquals(HcExerciseType.OTHER_WORKOUT, Mapper.cardioType("Mystery Machine 3000", "Quick start", "Unknown"))
        assertEquals(HcExerciseType.BIKING_STATIONARY, Mapper.cardioType("Bike Excite Live", null, "UprightBike"))
        assertEquals(HcExerciseType.RUNNING_TREADMILL, Mapper.cardioType("Run Excite", null, null))
        assertEquals(HcExerciseType.ROWING_MACHINE, Mapper.cardioType("Skillrow", null, null))
        assertEquals(HcExerciseType.ELLIPTICAL, Mapper.cardioType("Synchro", null, null))
        assertEquals(HcExerciseType.STAIR_CLIMBING_MACHINE, Mapper.cardioType("Climb Excite", null, null))
    }

    @Test
    fun `non WorkoutSession type is skipped without records`() {
        val item = Fixtures.item(1375).copy(type = "Class")
        val r = Mapper.planSessions(USER, item, Fixtures.detail(1375))
        assertTrue(r.sessions.isEmpty())
    }

    @Test
    fun `missing calories value fails closed`() {
        val d = Fixtures.detail(1375)
        val broken = d.copy(physicalActivities = d.physicalActivities.map { pa ->
            pa.copy(performedPhysicalActivity = pa.performedPhysicalActivity?.copy(
                data = PerformedData(pa.performedPhysicalActivity!!.data!!.data.filter { it.physicalProperty != "Calories" })))
        })
        try { Mapper.planSessions(USER, Fixtures.item(1375), broken); fail("expected MappingException") }
        catch (e: MappingException) { assertTrue(e.message!!.contains("Calories")) }
    }

    @Test
    fun `bad timestamp fails closed`() {
        val d = Fixtures.detail(1375).copy(startedOn = "2026-10-27T12:02:02Z")
        try { Mapper.planSessions(USER, Fixtures.item(1375), d); fail("expected MappingException") }
        catch (e: MappingException) { /* ok */ }
    }

    @Test
    fun `planned exercises without a performed record are ignored`() {
        val d = Fixtures.detail(1375)
        val withPlanned = d.copy(physicalActivities = d.physicalActivities + PhysicalActivity(position = 99, isCardio = false,
            physicalActivityName = "Planned only", performedPhysicalActivity = null))
        val r = Mapper.planSessions(USER, Fixtures.item(1375), withPlanned)
        assertEquals(2, r.sessions.size)
        assertEquals(OffsetDateTime.parse("2025-10-27T09:52:45+01:00"), r.sessions[1].end)
    }

    @Test
    fun `strength only visit gives one strength session from startedOn`() {
        val d = Fixtures.detail(1375)
        val strengthOnly = d.copy(physicalActivities = d.physicalActivities.filter { !it.isCardio })
        val r = Mapper.planSessions(USER, Fixtures.item(1375), strengthOnly)
        assertEquals(1, r.sessions.size)
        assertEquals(HcExerciseType.STRENGTH_TRAINING, r.sessions[0].type)
        assertEquals(OffsetDateTime.parse("2025-10-27T09:02:02+01:00"), r.sessions[0].start)
        assertEquals("gymsync:$USER:1375:strength", r.sessions[0].clientRecordId)
    }

    @Test
    fun `interleaved cardio and strength gives one whole-visit session`() {
        val d = Fixtures.detail(1375)
        val pas = d.physicalActivities
        val bike = pas.first { it.isCardio }
        // put the bike in the middle with a doneOn between two strength exercises
        val movedBike = bike.copy(position = 4, performedPhysicalActivity = bike.performedPhysicalActivity!!.copy(doneOn = "2025-10-27 09:33:00 +01:00"))
        val interleaved = pas.filter { !it.isCardio }.map { if (it.position!! >= 4) it.copy(position = it.position!! + 1) else it } + movedBike
        val r = Mapper.planSessions(USER, Fixtures.item(1375), d.copy(physicalActivities = interleaved))
        assertEquals(1, r.sessions.size)
        assertEquals("gymsync:$USER:1375:all", r.sessions[0].clientRecordId)
        assertTrue(r.warnings.any { "interleaved" in it })
    }

    // ---- time edge cases: DST transitions and midnight crossing, built from a minimal detail
    private fun detailAt(idCr: Long, start: String, cardioEnd: String, strEnd: String): SessionDetail {
        fun pa(name: String, cardio: Boolean, done: String, kcal: Double, pos: Int) = PhysicalActivity(
            position = pos, isCardio = cardio, physicalActivityName = name, equipmentName = if (cardio) "Bike Excite" else "Leg Press",
            performedPhysicalActivity = PerformedActivity(doneOn = done, doneAs = "Added",
                data = PerformedData(data = listOf(PhysicalProperty("Calories", kcal, "Kcal")))))
        return SessionDetail(idCr = idCr, displayName = "Free training", startedOn = start, closedOn = start.take(10),
            physicalActivities = listOf(pa("Bike", true, cardioEnd, 100.0, 2), pa("Leg press", false, strEnd, 50.0, 3)),
            doneSummary = listOf(PhysicalProperty("Calories", 150.0, "Kcal")))
    }
    private fun itemAt(idCr: Long, pd: Int) = ActivityItem(id = "session_$idCr", type = "WorkoutSession", partitionDate = pd, hour = 1, minute = 0, idCr = idCr, calories = 150)

    @Test
    fun `spring forward 29 Mar 2026 - offset changes inside the visit`() {
        val r = Mapper.planSessions(USER, itemAt(1L, 20260329),
            detailAt(1L, "2026-03-29 01:30:00 +01:00", "2026-03-29 01:50:00 +01:00", "2026-03-29 03:20:00 +02:00"))
        assertEquals(2, r.sessions.size)
        assertEquals(ZoneOffset.ofHours(1), r.sessions[0].start.offset)
        assertEquals(ZoneOffset.ofHours(2), r.sessions[1].end.offset)
        // 01:50 +01:00 -> 03:20 +02:00 is 30 real minutes
        assertEquals(30 * 60L, java.time.Duration.between(r.sessions[1].start, r.sessions[1].end).seconds)
    }

    @Test
    fun `fall back 25 Oct 2026 - ambiguous hour handled by offsets`() {
        val r = Mapper.planSessions(USER, itemAt(2L, 20261025),
            detailAt(2L, "2026-10-25 02:30:00 +02:00", "2026-10-25 02:50:00 +02:00", "2026-10-25 02:20:00 +01:00"))
        assertEquals(2, r.sessions.size)
        assertEquals(30 * 60L, java.time.Duration.between(r.sessions[1].start, r.sessions[1].end).seconds)
    }

    @Test
    fun `session crossing midnight`() {
        val r = Mapper.planSessions(USER, itemAt(3L, 20261027),
            detailAt(3L, "2026-10-27 23:40:00 +01:00", "2026-10-28 00:00:00 +01:00", "2026-10-28 00:30:00 +01:00"))
        assertEquals(2, r.sessions.size)
        assertEquals(50 * 60L, java.time.Duration.between(r.sessions[0].start, r.sessions[1].end).seconds)
    }

    @Test
    fun `exercise ending before session start fails closed`() {
        try {
            Mapper.planSessions(USER, itemAt(4L, 20261027),
                detailAt(4L, "2025-10-27 09:00:00 +01:00", "2025-10-27 08:50:00 +01:00", "2025-10-27 09:30:00 +01:00"))
            fail("expected MappingException")
        } catch (e: MappingException) { /* ok */ }
    }
}
