package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Writing a diary entry, which is two calls rather than one.
 *
 * The diary endpoint takes attachment **ids**, not images: every photo is uploaded first and
 * the entry references what came back. That order is the whole contract — an entry written
 * before its uploads answered would reference nothing — and it is what these tests pin.
 */
class NetworkDiaryWriteTest {

    private lateinit var server: MockWebServer
    private val bodies = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val requests = mutableListOf<Pair<String, String>>()

    private val attachmentsPath = "/api/v1/t/demo/attachments"
    private val diaryPath = "/api/v1/t/demo/plant-instances/p1/diary"
    private val entryPath = "/api/v1/t/demo/plant-instances/p1/diary/e1"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val body = request.body.readUtf8()
                synchronized(requests) { requests += path to body }
                return MockResponse()
                    .setResponseCode(statuses[path] ?: 200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(bodies[path] ?: "{}")
            }
        }
        server.start()
        bodies[attachmentsPath] = """{"attachment_id":"att-1","category":"diary"}"""
        bodies[diaryPath] = """{"key":"e1","text":"x","entry_type":"note"}"""
        bodies[entryPath] = """{"key":"e1","text":"x","entry_type":"note"}"""
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(): NetworkPlantActionsClient {
        val http = OkHttpClient()
        val json = NetworkModule.provideJson()
        return NetworkPlantActionsClient(
            apis = InstanceApiFactory(
                httpClient = http,
                json = json,
                tokenRefresh = TokenRefreshAuthenticator(
                    SessionRefresher(http, json, InMemoryCredentialStore(), FakeConnectionStore()),
                ),
            ),
            connections = FakeConnectionStore(
                Connection.ApiKey(
                    baseUrl = server.url("/").toString(),
                    tenantSlug = "demo",
                    keyHint = "…k_x",
                ),
            ),
            credentials = InMemoryCredentialStore(Credential.ApiKey("kp_sk_x")),
            changes = PlantDataChanges(),
        )
    }

    private fun sent(path: String) = synchronized(requests) { requests.filter { it.first == path } }

    /**
     * F-3: a kept detection frame goes into the plant's photo *gallery*, not in as a diary
     * attachment — the two routes are different things, and the diary refuses the gallery's
     * ids (see `upload`'s KDoc in the client).
     */
    @Test
    fun `keeping a photo files it in the plant's gallery, not as a diary attachment`() = runTest {
        val photosPath = "/api/v1/t/demo/plant-instances/p1/photos"
        bodies[photosPath] =
            """{"attachment_id":"att-9","byte_size":3,"is_cover":false,
               "mime_type":"image/jpeg","uri":"/att/att-9"}"""

        val outcome = client().addPhoto("p1", byteArrayOf(7, 8, 9))

        assertEquals(ActionOutcome.Done, outcome)
        val (path, body) = synchronized(requests) { requests.single() }
        assertEquals(photosPath, path)
        assertTrue(body.contains("name=\"file\""))
        assertTrue(sent(attachmentsPath).isEmpty())
    }

    @Test
    fun `a photo is uploaded first and the entry references what came back`() = runTest {
        val outcome = client().addEntry(
            "p1",
            DiaryDraft(text = "Spider mites", newPhotos = listOf(byteArrayOf(1, 2, 3))),
        )

        assertEquals(ActionOutcome.Done, outcome)
        val upload = sent(attachmentsPath).single().second
        // The category is what the diary accepts; a plant photo's id is refused by it.
        assertTrue("the upload must be filed as a diary attachment: $upload", upload.contains("diary"))
        val entry = sent(diaryPath).single().second
        assertTrue("only the returned id may be sent: $entry", entry.contains("att-1"))
        // The image itself never travels in the entry.
        assertFalse(entry.contains("base64"))
    }

    /**
     * An upload that fails takes the entry with it. Filing the entry anyway would leave a note
     * about a photo that is not there, and the writer would have no way of knowing.
     */
    @Test
    fun `a refused upload does not write half an entry`() = runTest {
        statuses[attachmentsPath] = 500

        val outcome = client().addEntry(
            "p1",
            DiaryDraft(text = "Spider mites", newPhotos = listOf(byteArrayOf(1))),
        )

        assertTrue(outcome is ActionOutcome.Failed)
        assertTrue("no entry may be written: ${sent(diaryPath)}", sent(diaryPath).isEmpty())
    }

    /** A role, not a broken credential: reconnecting cannot widen what an account may do. */
    @Test
    fun `a forbidden write says so as a role`() = runTest {
        statuses[diaryPath] = 403

        val outcome = client().addEntry("p1", DiaryDraft(text = "Spider mites"))

        assertEquals(ActionOutcome.NotPermitted, outcome)
    }

    /** The same distinction on the photo route: 401 is a credential, 403 a role. */
    @Test
    fun `keeping a photo tells a refused credential from a missing role`() = runTest {
        val photosPath = "/api/v1/t/demo/plant-instances/p1/photos"
        statuses[photosPath] = 403
        assertEquals(ActionOutcome.NotPermitted, client().addPhoto("p1", byteArrayOf(1)))

        statuses[photosPath] = 401
        assertEquals(ActionOutcome.Unauthorized, client().addPhoto("p1", byteArrayOf(1)))
    }

    /** The photo is stored on a 201 whatever shape the answer takes: raw JSON, no strict model. */
    @Test
    fun `a stored photo is done even when the answer carries fields this build does not know`() = runTest {
        val photosPath = "/api/v1/t/demo/plant-instances/p1/photos"
        bodies[photosPath] = """{"attachment_id":"att-9","quality_assessment":{"rating":"brand-new"}}"""

        assertEquals(ActionOutcome.Done, client().addPhoto("p1", byteArrayOf(1)))
    }

    @Test
    fun `an edit keeps the ids the entry already had and adds the new one`() = runTest {
        val outcome = client().updateEntry(
            "p1",
            "e1",
            DiaryDraft(text = "Fixed", photoRefs = listOf("old-1"), newPhotos = listOf(byteArrayOf(9))),
        )

        assertEquals(ActionOutcome.Done, outcome)
        val body = sent(entryPath).single().second
        assertTrue("the photos it already had must stay: $body", body.contains("old-1"))
        assertTrue("the new one must be appended: $body", body.contains("att-1"))
    }
}
