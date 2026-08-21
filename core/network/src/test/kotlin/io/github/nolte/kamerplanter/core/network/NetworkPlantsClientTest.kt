package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the joins and the field mapping, which is where this client's work actually happens
 * — the calls themselves are generated. Driven through MockWebServer with a path-based
 * dispatcher rather than a response queue, because the photo requests are concurrent and
 * their order is not fixed.
 */
class NetworkPlantsClientTest {

    /** Long enough that sitting it out is unmistakable, short enough not to stall the suite. */
    private val slowPhotoMillis = 3_000L

    private lateinit var server: MockWebServer
    private val responses = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val delays = mutableMapOf<String, Long>()
    private val requestedPaths = mutableListOf<String>()

    /**
     * Paths whose overlap is being measured.
     *
     * A request to one of these holds its dispatcher thread for [photoDelayMillis], so the
     * count below reflects requests that are genuinely in flight together. Concurrent, for the
     * same reason `requestedPaths` is guarded: the test thread writes it, MockWebServer's
     * threads read it. `setBodyDelay`
     * would not do: it delays the body while `dispatch` returns immediately, so nothing ever
     * overlaps inside the dispatcher and the peak would read 1 whatever the client does.
     */
    private val probedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inFlight = AtomicInteger()
    private val peakInFlight = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                synchronized(requestedPaths) { requestedPaths += path }
                if (path in probedPaths) {
                    val now = inFlight.incrementAndGet()
                    peakInFlight.updateAndGet { previous -> maxOf(previous, now) }
                    Thread.sleep(photoDelayMillis)
                    inFlight.decrementAndGet()
                }
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

    /**
     * A factory whose refresh path is inert: these tests are about the joins, not sessions.
     *
     * [maxRequestsPerHost] exists for the concurrency test alone. OkHttp's own dispatcher caps
     * a host at five in-flight requests by default, which silently supplies a bound of its own
     * — a first version of that test passed with the client's semaphore removed entirely,
     * because it was measuring OkHttp rather than this app. Raising the cap lets the app's own
     * limit be the only thing left to observe.
     */
    private fun plantsApiFactory(maxRequestsPerHost: Int? = null): InstanceApiFactory {
        val http = OkHttpClient.Builder()
            .apply {
                maxRequestsPerHost?.let {
                    dispatcher(
                        okhttp3.Dispatcher().apply {
                            this.maxRequests = it
                            this.maxRequestsPerHost = it
                        },
                    )
                }
            }
            .build()
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
        // Wall-clock on a real dispatcher, so it has to stay far below runTest's own 60 s
        // timeout: a stub that stopped answering should fail as a lost thumbnail, not as a
        // coin flip between two timeouts. The whole class runs in under a second, so this is
        // never reached in practice.
        thumbnailBudgetMillis: Long = 5_000L,
        maxRequestsPerHost: Int? = null,
    ) = NetworkPlantsClient(
        apis = plantsApiFactory(maxRequestsPerHost),
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

    /**
     * A plant as `GET /plant-instances` returns it.
     *
     * `container_volume_liters` carries a bare number deliberately, and that one line is doing
     * most of the work in this class. The field is the fatal one — it sits on the response
     * whose failure sinks the whole list — and the generated decimal adapter cannot read an
     * unquoted number. Measured: with the app's decimal serializer removed, thirteen tests here
     * go red with this line and one without it. Dropping it, or quoting the value, restores a
     * blind spot that already shipped once.
     */
    private fun plant(
        key: String,
        name: String? = "Monstera",
        instanceId: String = "inst-$key",
        removedOn: String? = null,
        locationKey: String? = "loc-1",
        siteKey: String? = "site-1",
        currentPhase: String? = null,
    ) = """
        {"key":"$key","instance_id":"$instanceId","plant_name":${name?.let { "\"$it\"" } ?: "null"},
         "planted_on":"2026-03-14","removed_on":${removedOn?.let { "\"$it\"" } ?: "null"},
         "species_key":"sp-1","cultivar_key":null,"slot_key":null,"substrate_batch_key":null,
         "container_volume_liters":12.5,
         "location_key":${locationKey?.let { "\"$it\"" } ?: "null"},
         "site_key":${siteKey?.let { "\"$it\"" } ?: "null"},
         "current_phase":${currentPhase?.let { "\"$it\"" } ?: "null"},
         "species":{"scientific_name":"Monstera deliciosa","common_names":["Swiss cheese plant"]}}
    """.trimIndent()

    private fun givenPlants(vararg plants: String) {
        responses["/api/v1/t/demo/plant-instances"] = "[${plants.joinToString(",")}]"
    }

    /**
     * `area_m2` is a bare JSON number here, which is how the backend sends it — the field is a
     * Python `float` upstream. An earlier version of this fixture quoted it, and that hid a
     * real defect rather than testing around it: the generated decimal adapter reads only the
     * quoted spelling, so against a live instance every location lookup failed and the column
     * went silently empty, because `locationNames` swallows its failures by design. Quoting it
     * again would restore the blind spot.
     */
    private fun givenLocations(vararg entries: Pair<String, String>) {
        responses["/api/v1/t/demo/locations"] = entries.joinToString(",", "[", "]") { (key, name) ->
            """{"key":"$key","name":"$name","site_key":"site-1","area_m2":1.0,"dimensions":[],
                "irrigation_system":"manual","light_type":"natural","orientation":null}"""
        }
    }

    private fun givenNoPhotos(vararg keys: String) = keys.forEach {
        responses["/api/v1/t/demo/plant-instances/$it/photos"] =
            """{"photos":[],"plant_instance_key":"$it"}"""
    }

    /**
     * Runs the load on a real dispatcher.
     *
     * Not a detail: `runTest` drives a virtual clock, and the scheduler goes idle exactly
     * when the load reaches its thumbnail timeout — so the clock jumps straight to the end of
     * the budget however large it is, and any photo response still on the wire is discarded.
     * A raised budget does not help, which is what an earlier version of this file assumed.
     * On a real dispatcher the budget is wall-clock, the way it is in production.
     */
    private suspend fun loaded(): List<PlantSummary> = withContext(Dispatchers.Default) {
        (client().loadPlants() as PlantListOutcome.Loaded).plants
    }

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

    /**
     * Removed instances come back from the endpoint, and are marked rather than dropped.
     *
     * The list hides them by default, which is a decision it takes on data it holds: dropping
     * them here would leave its "show removed" toggle with nothing to show.
     */
    @Test
    fun `marks removed instances instead of dropping them`() = runTest {
        givenPlants(
            plant("p1", name = "Alive"),
            plant("p2", name = "Gone", removedOn = "2026-01-01"),
        )
        givenLocations()
        givenNoPhotos("p1", "p2")

        val rows = loaded().associateBy { it.displayName }

        assertEquals(setOf("Alive", "Gone"), rows.keys)
        assertFalse(rows.getValue("Alive").isRemoved)
        assertTrue(rows.getValue("Gone").isRemoved)
    }

    /** The phase drives a filter, so an instance that reports one has to hand it over. */
    @Test
    fun `carries the growth phase the instance reports`() = runTest {
        givenPlants(plant("p1", name = "Alive", currentPhase = "growth"), plant("p2"))
        givenLocations()
        givenNoPhotos("p1", "p2")

        val rows = loaded().associateBy { it.key }

        assertEquals("growth", rows.getValue("p1").phase)
        assertNull(rows.getValue("p2").phase)
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
     * Asserted on elapsed wall-clock, which is why the load runs on a real dispatcher here
     * rather than on runTest's virtual clock (see [loaded]): budget and socket delay have to
     * be measured against the same clock for the comparison to mean anything. Without the
     * cancel, the enclosing scope waits out the full delay on the way out.
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
        delays["/api/v1/t/demo/plant-instances/p1/photos"] = slowPhotoMillis

        val startedAt = System.nanoTime()
        val rows = withContext(Dispatchers.Default) {
            (client(thumbnailBudgetMillis = 50L).loadPlants() as PlantListOutcome.Loaded).plants
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("the list still arrives", 1, rows.size)
        assertNull("without the thumbnail that did not make it", rows.single().thumbnailUrl)
        assertTrue(
            "the load must not sit out the slow request; took ${elapsedMillis}ms",
            elapsedMillis < slowPhotoMillis / 2,
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

        assertEquals(PlantListOutcome.Unauthorized, withContext(Dispatchers.Default) { client().loadPlants() })
    }

    /** A refused credential has to be told apart from an unreachable instance. */
    @Test
    fun `reports a rejected credential distinctly`() = runTest {
        statuses["/api/v1/t/demo/plant-instances"] = 401

        assertEquals(PlantListOutcome.Unauthorized, withContext(Dispatchers.Default) { client().loadPlants() })
    }

    @Test
    fun `reports a server error as unavailable`() = runTest {
        statuses["/api/v1/t/demo/plant-instances"] = 500

        assertTrue(withContext(Dispatchers.Default) { client().loadPlants() } is PlantListOutcome.Unavailable)
    }

    /**
     * Light mode reads its tenant's plants like any other connection.
     *
     * This test used to assert the opposite — that a light-mode connection has nothing to ask
     * for — which is what an empty plant list on a real light-mode instance turned out to be:
     * not a server without plants, but a client that never sent the request. The instance
     * serves `/api/v1/tenants` without a credential and scopes every plant route to a slug.
     */
    @Test
    fun `a light-mode connection loads its tenant's plants`() = runTest {
        givenPlants(plant("p1"))
        givenLocations("loc-1" to "Living room")
        givenNoPhotos("p1")
        val lightMode = NetworkPlantsClient(
            apis = plantsApiFactory(),
            connections = FakeConnectionStore(
                Connection.LightMode(server.url("/").toString(), tenantSlug = "demo"),
            ),
            // No credential, which is the whole point of the mode; the request still goes out.
            credentials = InMemoryCredentialStore(),
        )

        val outcome = withContext(Dispatchers.Default) { lightMode.loadPlants() }

        assertEquals(
            "Monstera",
            (outcome as PlantListOutcome.Loaded).plants.single().displayName,
        )
        assertTrue(requestedPaths.contains("/api/v1/t/demo/plant-instances"))
    }

    /**
     * Fifty plants never open fifty photo requests (#9).
     *
     * The bound is what makes the criterion's "scrolling a list of 50 plants stays responsive"
     * achievable at all — a tenant this size is exactly where an unbounded fan-out stops being
     * theoretical. `NetworkPlantsClient` holds a `Semaphore` permit across each call, and this
     * measures the effect rather than trusting the construct: the stub counts how many photo
     * requests are in flight at once and remembers the peak.
     *
     * The delay is what makes the measurement possible. Without it every request would finish
     * before the next began, the peak would read 1 on any implementation, and the test would
     * pass just as happily with the semaphore deleted.
     */
    @Test
    fun `fifty plants do not open fifty photo requests at once`() = runTest {
        val plantKeys = (1..FIFTY_PLANTS).map { "p$it" }
        givenPlants(*plantKeys.map { plant(it) }.toTypedArray())
        givenLocations("loc-1" to "Windowsill")

        plantKeys.forEach { key ->
            val path = "/api/v1/t/demo/plant-instances/$key/photos"
            responses[path] = """{"plant_instance_key":"$key","photos":[]}"""
            probedPaths += path
        }

        // Generous: fifty plants at six at a time, each held for photoDelayMillis, is about
        // nine batches — the budget must not be what ends the load.
        withContext(Dispatchers.Default) {
            client(
                thumbnailBudgetMillis = 30_000L,
                // Above the plant count, so nothing but the app's own bound constrains this.
                maxRequestsPerHost = FIFTY_PLANTS * 2,
            ).loadPlants()
        }

        val peak = peakInFlight

        assertTrue(
            "photo requests must stay bounded well below the plant count, peaked at" +
                " ${peak.get()} of $FIFTY_PLANTS",
            peak.get() <= GENEROUS_CONCURRENCY_CEILING,
        )
        // Guards the guard: a peak of 1 would mean the requests never overlapped, so the
        // assertion above would hold for a client with no bound at all.
        assertTrue(
            "the requests have to actually overlap for this to measure anything," +
                " peaked at ${peak.get()}",
            peak.get() > 1,
        )
    }
}

/** A tenant the criterion names by size: "scrolling a list of 50 plants" (#9). */
private const val FIFTY_PLANTS = 50

/** Long enough that the photo requests overlap, short enough not to stall the suite. */
private const val photoDelayMillis = 200L

/**
 * Deliberately looser than the client's own `FETCH_CONCURRENCY`, which is private.
 *
 * The claim under test is "bounded", not "exactly six": pinning the exact value would turn a
 * legitimate tuning change into a red test while catching nothing extra, since the failure
 * this guards against is an unbounded fan-out — fifty plants, fifty sockets — not the
 * difference between six and eight.
 */
private const val GENEROUS_CONCURRENCY_CEILING = 12
