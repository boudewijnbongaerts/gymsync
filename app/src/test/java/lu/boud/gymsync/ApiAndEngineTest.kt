package lu.boud.gymsync

import kotlinx.coroutines.runBlocking
import lu.boud.gymsync.api.ApiException
import lu.boud.gymsync.api.MywellnessApi
import lu.boud.gymsync.data.Credentials
import lu.boud.gymsync.sync.PlannedSession
import lu.boud.gymsync.sync.SyncEngine
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ApiAndEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MywellnessApi
    private val creds = Credentials("x@example.com", "secret")

    private val loginOk = """{"accountConfirmed":true,"userContext":{"id":"${Fixtures.USER}"},"result":"Success",
        "facilities":[{"id":"demo","url":"tgmobileapp","name":"Technogym App","isDemo":true},{"id":"f1","url":"exampleclub","name":"Example Club","isDemo":false}],
        "token":"TOKEN1"}"""

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = MywellnessApi(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), coreBase = base, servicesBase = base)
    }
    @After fun tearDown() { try { server.close() } catch (e: Exception) { } }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    private fun history() = json(200, Fixtures.text("activity_history_page1.json"))
    private fun detail(id: Long) = json(200, Fixtures.text("session_$id.json"))

    private val written = mutableListOf<PlannedSession>()
    private fun engine(today: LocalDate = LocalDate.of(2025, 10, 28)) = SyncEngine(api, { written += it; it.size }, { /* no pacing in tests */ }, ZoneId.of("Europe/Berlin"), { today })

    @Test fun `login ok parses token, user and non-demo facility`() {
        server.enqueue(json(200, loginOk))
        val s = api.login("x@example.com", "pw")
        assertEquals("TOKEN1", s.token); assertEquals(Fixtures.USER, s.userId); assertEquals("exampleclub", s.facilityUrl)
        val req = server.takeRequest()
        assertTrue(req.requestLine.contains("/v2/enduser/authentication/login"))
        assertEquals("EC1D38D7-D359-48D0-A60C-D8C0B8FB9DF9", req.headers["X-MWAPPS-APPID"])
        assertNull(req.headers["Authorization"])
    }

    @Test fun `wrong password is BadCredentials`() {
        server.enqueue(json(401, """{"result":"UsernameOrPasswordNotValid"}"""))
        try { api.login("x@example.com", "pw"); fail() } catch (e: ApiException.BadCredentials) { /* ok */ }
    }

    @Test fun `mfa is reported and nothing is written`() = runBlocking {
        server.enqueue(json(401, """{"result":"MfaRequired"}"""))
        val o = engine().run(creds, firstRunDone = true, alreadyDone = emptySet())
        assertEquals("Two-factor login isn't supported", o.error); assertTrue(written.isEmpty())
    }

    @Test fun `happy path writes sessions inside the 7-day window and marks them done`() = runBlocking {
        server.enqueue(json(200, loginOk))
        server.enqueue(history())
        server.enqueue(detail(1375)); server.enqueue(detail(1374))
        // window 21 Oct..28 Oct holds 1375, 1374, 1373, 1372; the last two are already done.
        val o = engine().run(creds, firstRunDone = true, alreadyDone = setOf("1373", "1372"))
        assertNull(o.error)
        assertEquals(4, written.size)
        assertEquals(setOf("1375", "1374"), o.newDoneIds)
        assertEquals(4, o.sessionsWritten)
    }

    @Test fun `already-done sessions are not fetched again`() = runBlocking {
        server.enqueue(json(200, loginOk)); server.enqueue(history()); server.enqueue(detail(1375))
        val o = engine().run(creds, firstRunDone = true, alreadyDone = setOf("1374", "1373", "1372"))
        assertNull(o.error); assertEquals(2, written.size); assertEquals(setOf("1375"), o.newDoneIds)
        assertEquals(3, server.requestCount)
    }

    @Test fun `TokenNotValid on detail triggers one re-login then succeeds`() = runBlocking {
        server.enqueue(json(200, loginOk)); server.enqueue(history())
        server.enqueue(json(200, """{"errors":[{"type":"Security","field":"TokenNotValid","errorMessage":"expired"}]}"""))
        server.enqueue(json(200, loginOk.replace("TOKEN1", "TOKEN2")))
        server.enqueue(detail(1375))
        val o = engine().run(creds, firstRunDone = true, alreadyDone = setOf("1374", "1373", "1372"))
        assertNull(o.error); assertEquals(2, written.size)
        server.takeRequest(); server.takeRequest(); server.takeRequest(); server.takeRequest()
        assertEquals("Bearer TOKEN2", server.takeRequest().headers["Authorization"])
    }

    @Test fun `429 on session list writes nothing and is retryable`() = runBlocking {
        server.enqueue(json(200, loginOk)); server.enqueue(json(429, "slow down"))
        val o = engine().run(creds, true, emptySet())
        assertNotNull(o.error); assertTrue(o.retryable); assertTrue(written.isEmpty())
    }

    @Test fun `500 on session list writes nothing`() = runBlocking {
        server.enqueue(json(200, loginOk)); server.enqueue(json(500, "boom"))
        val o = engine().run(creds, true, emptySet())
        assertNotNull(o.error); assertTrue(written.isEmpty())
    }

    @Test fun `HTML instead of JSON writes nothing`() = runBlocking {
        server.enqueue(json(200, loginOk))
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>maintenance</html>").build())
        val o = engine().run(creds, true, emptySet())
        assertTrue(o.error!!.contains("Unexpected response format")); assertTrue(written.isEmpty())
    }

    @Test fun `empty items writes nothing and is clean`() = runBlocking {
        server.enqueue(json(200, loginOk)); server.enqueue(json(200, """{"data":{"pager":"","items":[]},"token":"T"}"""))
        val o = engine().run(creds, true, emptySet())
        assertNull(o.error); assertTrue(written.isEmpty()); assertEquals(0, o.sessionsWritten)
    }

    @Test fun `unknown session detail (no data) is skipped, others still written`() = runBlocking {
        server.enqueue(json(200, loginOk)); server.enqueue(history())
        server.enqueue(json(200, Fixtures.text("session_detail_unknown.json"))); server.enqueue(detail(1374))
        val o = engine().run(creds, true, setOf("1373", "1372"))
        assertTrue(o.error!!.contains("1375")); assertEquals(setOf("1374"), o.newDoneIds); assertEquals(2, written.size)
    }

    @Test fun `first run pages while oldest item is inside the 30-day window`() = runBlocking {
        server.enqueue(json(200, loginOk))
        server.enqueue(history()) // oldest 10 Sep < cutoff 28 Sep -> stops after page 1
        repeat(20) { server.enqueue(json(200, Fixtures.text("session_detail_unknown.json"))) }
        val o = engine().run(creds, firstRunDone = false, alreadyDone = emptySet())
        // 12 sessions between 28 Sep and 28 Oct -> 12 detail calls, all "unknown" -> all skipped, none written
        assertEquals(1 + 1 + 12, server.requestCount)
        assertTrue(written.isEmpty()); assertNotNull(o.error)
    }

    @Test fun `network failure on login is retryable and writes nothing`() = runBlocking {
        server.close()
        val o = engine().run(creds, true, emptySet())
        assertTrue(o.retryable); assertTrue(written.isEmpty())
    }
}
