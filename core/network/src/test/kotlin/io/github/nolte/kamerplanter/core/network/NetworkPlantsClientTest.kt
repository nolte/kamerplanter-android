package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private lateinit var server: MockWebServer
    private val responses = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
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
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(credential: Credential = Credential.ApiKey("kp_sk_x")) =
        NetworkPlantsClient(
            apis = InstanceApiFactory(OkHttpClient(), NetworkModule.provideJson()),
            connections = FakeConnectionStore(
                Connection.ApiKey(
                    baseUrl = server.url("/").toString(),
                    tenantSlug = "demo",
                    keyHint = "…k_x",
                ),
            ),
            credentials = InMemoryCredentialStore(credential),
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
            apis = InstanceApiFactory(OkHttpClient(), NetworkModule.provideJson()),
            connections = FakeConnectionStore(Connection.LightMode(server.url("/").toString())),
            credentials = InMemoryCredentialStore(),
        )

        assertTrue(lightMode.loadPlants() is PlantListOutcome.Unavailable)
        assertTrue(requestedPaths.isEmpty())
    }
}

private class FakeConnectionStore(initial: Connection?) : ConnectionStore {
    private val flow = MutableStateFlow(initial)
    override val connection: Flow<Connection?> = flow
    override suspend fun save(connection: Connection) {
        flow.value = connection
    }
    override suspend fun clear() {
        flow.value = null
    }
}
