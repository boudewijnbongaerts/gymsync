package lu.boud.gymsync

import lu.boud.gymsync.data.SyncTimes
import lu.boud.gymsync.sync.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val times = SyncTimes(14, 0, 21, 0)
    private fun at(s: String) = ZonedDateTime.parse(s).withZoneSameInstant(zone)

    @Test fun `morning picks 14_00 today`() =
        assertEquals(at("2026-10-28T14:00+01:00"), Scheduler.nextRun(times, at("2026-10-28T09:00+01:00")))

    @Test fun `afternoon picks 21_00 today`() =
        assertEquals(at("2026-10-28T21:00+01:00"), Scheduler.nextRun(times, at("2026-10-28T14:00:10+01:00")))

    @Test fun `late evening picks 14_00 tomorrow`() =
        assertEquals(at("2026-10-29T14:00+01:00"), Scheduler.nextRun(times, at("2026-10-28T21:05+01:00")))

    @Test fun `unsorted times still work`() =
        assertEquals(at("2026-10-28T14:00+01:00"), Scheduler.nextRun(SyncTimes(21, 0, 14, 0), at("2026-10-28T09:00+01:00")))

    @Test fun `across fall-back night the next run is on wall-clock time`() =
        assertEquals(at("2026-10-25T14:00+01:00"), Scheduler.nextRun(times, at("2026-10-24T22:00+02:00")))
}
