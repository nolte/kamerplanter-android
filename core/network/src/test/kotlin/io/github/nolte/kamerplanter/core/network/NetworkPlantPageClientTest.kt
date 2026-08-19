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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One plant's page, section by section.
 *
 * The mapping is where this client's work happens — keys resolved to names, dates and enums
 * turned into the strings the UI shows, a removal made into one value instead of three
 * nullable fields — and the failures are the other half: the page treats a refused credential,
 * a missing plant and an unreachable instance as three different things, so this asserts that
 * the client tells them apart (#11).
 */
class NetworkPlantPageClientTest {

    private lateinit var server: MockWebServer
    private val responses = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()

    private val plantPath = "/api/v1/t/demo/plant-instances/p1"
    private val photosPath = "/api/v1/t/demo/plant-instances/p1/photos"
    private val phasesPath = "/api/v1/plant-instances/p1/phases/history"
    private val locationsPath = "/api/v1/t/demo/locations"
    private val dashboardPath = "/api/v1/t/demo/care-reminders/dashboard"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return MockResponse()
                    .setResponseCode(statuses[path] ?: 200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(responses[path] ?: "{}")
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(): NetworkPlantPageClient {
        val http = OkHttpClient()
        val json = NetworkModule.provideJson()
        return NetworkPlantPageClient(
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
        )
    }

    /**
     * `container_volume_liters` is an unquoted number here on purpose, the same way the list's
     * fixture carries one: it is the field the app's own decimal serializer exists for, and a
     * quoted value would test around the defect instead of for it.
     */
    private fun givenPlant(
        name: String? = "Monstera",
        removedOn: String? = null,
        terminationType: String? = null,
        terminationCause: String? = null,
    ) {
        responses[plantPath] = """
            {"key":"p1","instance_id":"MONST-01",
             "plant_name":${name?.let { "\"$it\"" } ?: "null"},
             "planted_on":"2026-03-14",
             "removed_on":${removedOn?.let { "\"$it\"" } ?: "null"},
             "termination_type":${terminationType?.let { "\"$it\"" } ?: "null"},
             "termination_cause":${terminationCause?.let { "\"$it\"" } ?: "null"},
             "species_key":"sp-1","cultivar_key":null,"slot_key":null,"substrate_batch_key":null,
             "container_volume_liters":12.5,"location_key":"loc-1","site_key":"site-1",
             "current_phase":"vegetative","current_phase_started_at":"2026-04-01T08:00:00Z",
             "cultivation_cycle_type":"perennial","substrate_type_override":"soil",
             "mother_key":"p0",
             "species":{"scientific_name":"Monstera deliciosa","common_names":["Swiss cheese plant"]}}
        """.trimIndent()
        responses[locationsPath] = """
            [{"key":"loc-1","name":"Living room","site_key":"site-1","area_m2":1.0,
              "dimensions":[],"irrigation_system":"manual","light_type":"natural","orientation":null}]
        """.trimIndent()
    }

    @Test
    fun `a plant comes back with its location resolved and its dates as text`() = runTest {
        givenPlant()

        val plant = (client().plant("p1") as SectionOutcome.Loaded).value

        assertEquals("Monstera", plant.displayName)
        assertEquals("Swiss cheese plant", plant.species)
        // Never the raw `loc-1`: that is the defect the list was fixed for, one screen over.
        assertEquals("Living room", plant.location)
        assertEquals("2026-03-14", plant.plantedOn)
        assertEquals("vegetative", plant.phase?.name)
        assertEquals("perennial", plant.cultivationCycle)
        assertEquals("soil", plant.substrate)
        assertEquals("p0", plant.motherKey)
        assertEquals(12.5, plant.containerVolumeLiters!!, 0.001)
        assertNull(plant.removal)
    }

    /** A blank name is as unusable as a missing one, so the instance id stands in. */
    @Test
    fun `a plant without a name falls back to its instance id`() = runTest {
        givenPlant(name = "   ")

        val plant = (client().plant("p1") as SectionOutcome.Loaded).value

        assertEquals("MONST-01", plant.displayName)
    }

    /** The three removal fields only mean anything together, so they arrive as one value. */
    @Test
    fun `a removed plant carries how it left`() = runTest {
        // The instance's own vocabulary: `died` and `pest` are values its enums declare, and
        // a made-up one would not deserialize at all — which is a different test.
        givenPlant(removedOn = "2026-07-30", terminationType = "died", terminationCause = "pest")

        val plant = (client().plant("p1") as SectionOutcome.Loaded).value

        assertEquals("2026-07-30", plant.removal?.removedOn)
        assertEquals("died", plant.removal?.type)
        assertEquals("pest", plant.removal?.cause)
    }

    /**
     * The location is enrichment: a page without a location name is still a page, while a page
     * that refused to load because one lookup failed is not.
     */
    @Test
    fun `a failing location lookup costs the name and nothing else`() = runTest {
        givenPlant()
        statuses[locationsPath] = 500

        val plant = (client().plant("p1") as SectionOutcome.Loaded).value

        assertNull(plant.location)
        assertEquals("Monstera", plant.displayName)
    }

    @Test
    fun `a missing plant is told apart from an unreachable instance`() = runTest {
        statuses[plantPath] = 404
        assertEquals(SectionOutcome.NotFound, client().plant("p1"))

        statuses[plantPath] = 500
        assertTrue(client().plant("p1") is SectionOutcome.Unavailable)
    }

    /** 401 and 403 both mean the credential will not open this page, and neither retries. */
    @Test
    fun `a refused credential is its own answer`() = runTest {
        statuses[plantPath] = 401
        assertEquals(SectionOutcome.Unauthorized, client().plant("p1"))

        statuses[plantPath] = 403
        assertEquals(SectionOutcome.Unauthorized, client().plant("p1"))
    }

    @Test
    fun `photos come back cover first, with absolute urls`() = runTest {
        responses[photosPath] = """
            {"plant_instance_key":"p1","photos":[
              {"attachment_id":"a1","uri":"/attachments/a1","mime_type":"image/jpeg",
               "byte_size":10,"is_cover":false,
               "thumbnail_uris":{"small":"/t/s1","medium":"/t/m1","large":"/t/l1"}},
              {"attachment_id":"a2","uri":"/attachments/a2","mime_type":"image/jpeg",
               "byte_size":10,"is_cover":true,
               "thumbnail_uris":{"small":"/t/s2","medium":"/t/m2","large":"/t/l2"}}]}
        """.trimIndent()

        val photos = (client().photos("p1") as SectionOutcome.Loaded).value

        assertEquals(2, photos.size)
        assertTrue(photos.first().isCover)
        assertTrue(photos.first().url, photos.first().url.endsWith("/t/m2"))
        assertTrue(photos.first().url.startsWith("http"))
    }

    @Test
    fun `phase history comes back newest first`() = runTest {
        responses[phasesPath] = """
            [{"key":"h1","phase_name":"seedling","entered_at":"2026-03-14T08:00:00Z",
              "exited_at":"2026-04-01T08:00:00Z","actual_duration_days":18,
              "performance_score":null,"transition_reason":"scheduled"},
             {"key":"h2","phase_name":"vegetative","entered_at":"2026-04-01T08:00:00Z",
              "exited_at":null,"actual_duration_days":null,"performance_score":null,
              "transition_reason":"scheduled"}]
        """.trimIndent()

        val phases = (client().phaseHistory("p1") as SectionOutcome.Loaded).value

        assertEquals(listOf("vegetative", "seedling"), phases.map { it.name })
        assertNull(phases.first().endedAt)
    }

    /**
     * The dashboard is tenant-wide, so the page's own section is what filters it — and it
     * keeps *every* open task for this plant, not only the pressing one the list row shows.
     */
    @Test
    fun `care keeps this plant's open tasks, most pressing first`() = runTest {
        responses[dashboardPath] = """
            [{"care_profile_key":"c1","plant_key":"p1","plant_name":"Monstera",
              "reminder_type":"fertilizing","urgency":"upcoming","due_date":"2026-09-01"},
             {"care_profile_key":"c2","plant_key":"p1","plant_name":"Monstera",
              "reminder_type":"watering","urgency":"overdue","due_date":"2026-08-11"},
             {"care_profile_key":"c3","plant_key":"other","plant_name":"Ficus",
              "reminder_type":"watering","urgency":"overdue","due_date":"2026-08-10"}]
        """.trimIndent()

        val care = (client().care("p1") as SectionOutcome.Loaded).value

        assertEquals(listOf("watering", "fertilizing"), care.map { it.kind })
    }
}
