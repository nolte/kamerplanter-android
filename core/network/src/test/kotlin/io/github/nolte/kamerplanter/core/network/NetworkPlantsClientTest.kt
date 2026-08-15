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
 * Covers the joins and the field mapping, which is where this client's work actually happens
 * — the calls themselves are generated. Driven through MockWebServer with a path-based
 * dispatcher rather than a response queue, because the photo requests are concurrent and
 * their order is not fixed.
 */
class NetworkPlantsClientTest {

    /** Long enough that sitting it out is unmistakable, short enough not to stall the suite. */
    private val SLOW_PHOTO_MILLIS = 3_000L

    private lateinit var server: MockWebServer
    private val responses = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val delays = mutableMapOf<String, Long>()
    private val requestedPaths = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                synchronized(requestedPaths) { requestedPaths += path }
                val status = statuses[path] ?: 200
                val body = responses[path] ?: "{}"
                return MockResponse()
                    .setResponseCode(status)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body)
                    .apply {
                        delays[path]?.let { setBodyDelay(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** A factory whose refresh path is inert: these tests are about the joins, not sessions. */
    private fun plantsApiFactory(): InstanceApiFactory {
        val http = OkHttpClient()
        val json = NetworkModule.provideJson()
        return InstanceApiFactory(
            httpClient = http,
            json = json,
            tokenRefresh = TokenRefreshAuthenticator(
                SessionRefresher(http, json, InMemoryCredentialStore(), FakeConnectionStore()),
            ),
        )
    }

    private fun client(
        credential: Credential = Credential.ApiKey("kp_sk_x"),
        // Generous by default so the virtual clock in runTest cannot trip the budget while a
        // test is asserting something else entirely.
        thumbnailBudgetMillis: Long = 60_000L,
    ) = NetworkPlantsClient(
        apis = plantsApiFactory(),
        connections = FakeConnectionStore(
            Connection.ApiKey(
                baseUrl = server.url("/").toString(),
                tenantSlug = "demo",
                keyHint = "…k_x",
            ),
        ),
        credentials = InMemoryCredentialStore(credential),
        thumbnailBudgetMillis = thumbnailBudgetMillis,
    )

    private fun plant(
        key: String,
        name: String? = "Monstera",
        instanceId: String = "inst-$key",
        removedOn: String? = null,
        locationKey: String? = "loc-1",
        siteKey: String? = "site-1",
    ) = """
        {"key":"$key","instance_id":"$instanceId","plant_name":${name?.let { "\"$it\"" } ?: "null"},
         "planted_on":"2026-03-14","removed_on":${removedOn?.let { "\"$it\"" } ?: "null"},
         "species_key":"sp-1","cultivar_key":null,"slot_key":null,"substrate_batch_key":null,
         "location_key":${locationKey?.let { "\"$it\"" } ?: "null"},
         "site_key":${siteKey?.let { "\"$it\"" } ?: "null"},
         "species":{"scientific_name":"Monstera deliciosa","common_names":["Swiss cheese plant"]}}
    """.trimIndent()

    private fun givenPlants(vararg plants: String) {
        responses["/api/v1/t/demo/plant-instances"] = "[${plants.joinToString(",")}]"
    }

    private fun givenLocations(vararg entries: Pair<String, String>) {
        responses["/api/v1/t/demo/locations"] = entries.joinToString(",", "[", "]") { (key, name) ->
            """{"key":"$key","name":"$name","site_key":"site-1","area_m2":"1.0","dimensions":[],
                "irrigation_system":"manual","light_type":"natural","orientation":null}"""
        }
    }

    private fun givenNoPhotos(vararg keys: String) = keys.forEach {
        responses["/api/v1/t/demo/plant-instances/$it/photos"] =
            """{"photos":[],"plant_instance_key":"$it"}"""
    }

    private suspend fun loaded(): List<PlantSummary> =
        (client().loadPlants() as PlantListOutcome.Loaded).plants

    @Test
    fun `maps name, species and resolved location onto a row`() = runTest {
        givenPlants(plant("p1"))
        givenLocations("loc-1" to "Living room")
        givenNoPhotos("p1")

        val row = loaded().single()

        assertEquals("Monstera", row.displayName)
        assertEquals("Swiss cheese plant", row.species)
        // Never the raw location_key.
        assertEquals("Living room", row.location)
    }

    /** `plant_name` is nullable, and a row headed by nothing is unusable. */
    @Test
    fun `falls back to the instance id when a plant has no name`() = runTest {
        givenPlants(plant("p1", name = null, instanceId = "1f0b2c"))
        givenLocations()
        givenNoPhotos("p1")

        assertEquals("1f0b2c", loaded().single().displayName)
    }

    @Test
    fun `falls back to the instance id when the name is blank`() = runTest {
        givenPlants(plant("p1", name = "   ", instanceId = "1f0b2c"))
        givenLocations()
        givenNoPhotos("p1")

        assertEquals("1f0b2c", loaded().single().displayName)
    }

    /** Removed instances come back from the endpoint; a living-plants list must not show them. */
    @Test
    fun `hides removed instances`() = runTest {
        givenPlants(
            plant("p1", name = "Alive"),
            plant("p2", name = "Gone", removedOn = "2026-01-01"),
        )
        givenLocations()
        givenNoPhotos("p1", "p2")

        assertEquals(listOf("Alive"), loaded().map { it.displayName })
    }

    @Test
    fun `joins the care dashboard onto the right plant`() = runTest {
        givenPlants(plant("p1", name = "Thirsty"), plant("p2", name = "Fine"))
        givenLocations()
        givenNoPhotos("p1", "p2")
        responses["/api/v1/t/demo/care-reminders/dashboard"] =
            """[{"care_profile_key":"cp1","plant_key":"p1","plant_name":"Thirsty",
                 "reminder_type":"watering","urgency":"due"}]"""

        val rows = loaded().associateBy { it.displayName }

        assertEquals("watering", rows.getValue("Thirsty").careAction?.kind)
        assertNull(rows.getValue("Fine").careAction)
    }

    @Test
    fun `prefers the cover photo and makes its thumbnail absolute`() = runTest {
        givenPlants(plant("p1"))
        givenLocations()
        responses["/api/v1/t/demo/plant-instances/p1/photos"] = """
            {"plant_instance_key":"p1","photos":[
              {"attachment_id":"a1","byte_size":1,"is_cover":false,"mime_type":"image/jpeg",
               "uri":"/x","thumbnail_uris":{"small":"/thumbs/other.jpg","medium":"m","large":"l"}},
              {"attachment_id":"a2","byte_size":1,"is_cover":true,"mime_type":"image/jpeg",
               "uri":"/y","thumbnail_uris":{"small":"/thumbs/cover.jpg","medium":"m","large":"l"}}]}
        """.trimIndent()

        val url = loaded().single().thumbnailUrl

        assertTrue(url.orEmpty(), url.orEmpty().endsWith("/thumbs/cover.jpg"))
        assertTrue(url.orEmpty(), url.orEmpty().startsWith("http"))
    }

    /**
     * The budget has to produce a list, not merely stop waiting for one. An earlier version
     * timed out the `awaitAll` while the requests stayed children of the enclosing scope, so
     * the load took just as long *and* lost the thumbnails that had already arrived.
     *
     * Asserted on elapsed wall-clock, because that is the difference: `withTimeoutOrNull`
     * runs on runTest's virtual clock and returns at once, while the socket delay is real.
     * Without the cancel, the enclosing scope waits out the full delay on the way out.
     */
    @Test
    fun `a slow photo endpoint costs its thumbnail, not the list`() = runTest {
        givenPlants(plant("p1"))
        givenLocations()
        // A photo that would arrive — but only long after the budget is spent.
        responses["/api/v1/t/demo/plant-instances/p1/photos"] = """
            {"plant_instance_key":"p1","photos":[
              {"attachment_id":"a1","byte_size":1,"is_cover":true,"mime_type":"image/jpeg",
               "uri":"/y","thumbnail_uris":{"small":"/thumbs/late.jpg","medium":"m","large":"l"}}]}
        """.trimIndent()
        delays["/api/v1/t/demo/plant-instances/p1/photos"] = SLOW_PHOTO_MILLIS

        val startedAt = System.nanoTime()
        val rows = (client(thumbnailBudgetMillis = 50L).loadPlants() as PlantListOutcome.Loaded).plants
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("the list still arrives", 1, rows.size)
        assertNull("without the thumbnail that did not make it", rows.single().thumbnailUrl)
        assertTrue(
            "the load must not sit out the slow request; took ${elapsedMillis}ms",
            elapsedMillis < SLOW_PHOTO_MILLIS / 2,
        )
    }

    /** Most plants have no photo — that is a row without a picture, not a failed load. */
    @Test
    fun `a plant without photos still produces a row`() = runTest {
        givenPlants(plant("p1"))
        givenLocations()
        givenNoPhotos("p1")

        assertNull(loaded().single().thumbnailUrl)
    }

    /**
     * Locations and care are enrichment: losing them costs a location name and a badge, and
     * showing nothing at all would be a worse answer than showing the plants.
     */
    @Test
    fun `a failing locations call still yields rows`() = runTest {
        givenPlants(plant("p1"))
        statuses["/api/v1/t/demo/locations"] = 500
        statuses["/api/v1/t/demo/care-reminders/dashboard"] = 500
        givenNoPhotos("p1")

        val row = loaded().single()

        assertEquals("Monstera", row.displayName)
        assertNull(row.location)
        assertNull(row.careAction)
    }

    @Test
    fun `sorts by display name, case-insensitively`() = runTest {
        givenPlants(
            plant("p1", name = "zebra"),
            plant("p2", name = "Acer"),
            plant("p3", name = "mint"),
        )
        givenLocations()
        givenNoPhotos("p1", "p2", "p3")

        assertEquals(listOf("Acer", "mint", "zebra"), loaded().map { it.displayName })
    }

    /** One call per site, however many plants sit in it (AC: no request per row). */
    @Test
    fun `resolves locations once per site rather than once per plant`() = runTest {
        givenPlants(
            plant("p1", siteKey = "site-1"),
            plant("p2", siteKey = "site-1"),
            plant("p3", siteKey = "site-1"),
        )
        givenLocations("loc-1" to "Living room")
        givenNoPhotos("p1", "p2", "p3")

        loaded()

        assertEquals(1, requestedPaths.count { it == "/api/v1/t/demo/locations" })
    }

    /**
     * The care dashboard is decoded entry by entry, so a reminder kind this build has never
     * heard of costs the plant it belongs to and nothing else. Decoded as a whole — which is
     * what the generated `List<CareDashboardEntryResponse>` does — the unknown enum throws
     * for the entire response and every plant in the tenant silently loses its badge.
     */
    @Test
    fun `an unknown reminder kind costs one badge, not all of them`() = runTest {
        givenPlants(plant("p1", name = "Known"), plant("p2", name = "Novel"))
        givenLocations()
        givenNoPhotos("p1", "p2")
        responses["/api/v1/t/demo/care-reminders/dashboard"] =
            """[{"care_profile_key":"cp1","plant_key":"p1","plant_name":"Known",
                 "reminder_type":"watering","urgency":"due"},
                {"care_profile_key":"cp2","plant_key":"p2","plant_name":"Novel",
                 "reminder_type":"invented_next_release","urgency":"due"}]"""

        val rows = loaded().associateBy { it.displayName }

        assertEquals("watering", rows.getValue("Known").careAction?.kind)
        // The unknown kind still produces a badge — the UI maps anything it does not
        // recognise to "needs attention" rather than dropping the row's care state.
        assertEquals("invented_next_release", rows.getValue("Novel").careAction?.kind)
    }

    /** An entry missing the fields a row needs is dropped, not allowed to sink the rest. */
    @Test
    fun `a malformed dashboard entry does not take the others with it`() = runTest {
        givenPlants(plant("p1", name = "Fine"))
        givenLocations()
        givenNoPhotos("p1")
        responses["/api/v1/t/demo/care-reminders/dashboard"] =
            """[{"nonsense":true},
                {"care_profile_key":"cp1","plant_key":"p1","plant_name":"Fine",
                 "reminder_type":"watering","urgency":"due"}]"""

        assertEquals("watering", loaded().single().careAction?.kind)
    }

    /**
     * 403 means the credential authenticated but may not read this tenant — an API key whose
     * scope no longer covers it. A retry button cannot fix that; Settings can.
     */
    @Test
    fun `a forbidden response routes to reconnecting rather than to a retry`() = runTest {
        statuses["/api/v1/t/demo/plant-instances"] = 403

        assertEquals(PlantListOutcome.Unauthorized, client().loadPlants())
    }

    /** A refused credential has to be told apart from an unreachable instance. */
    @Test
    fun `reports a rejected credential distinctly`() = runTest {
        statuses["/api/v1/t/demo/plant-instances"] = 401

        assertEquals(PlantListOutcome.Unauthorized, client().loadPlants())
    }

    @Test
    fun `reports a server error as unavailable`() = runTest {
        statuses["/api/v1/t/demo/plant-instances"] = 500

        assertTrue(client().loadPlants() is PlantListOutcome.Unavailable)
    }

    /** Light mode has no tenant, so there are no tenant-scoped plants to ask for. */
    @Test
    fun `a light-mode connection has no plants to load`() = runTest {
        val lightMode = NetworkPlantsClient(
            apis = plantsApiFactory(),
            connections = FakeConnectionStore(Connection.LightMode(server.url("/").toString())),
            credentials = InMemoryCredentialStore(),
        )

        assertTrue(lightMode.loadPlants() is PlantListOutcome.Unavailable)
        assertTrue(requestedPaths.isEmpty())
    }
}
