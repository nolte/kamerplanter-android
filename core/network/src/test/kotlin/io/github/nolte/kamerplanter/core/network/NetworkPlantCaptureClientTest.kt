package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The plant-capture seam against a scripted instance: the identification gate (R3, R5), the
 * identify → select → link path (R13, R31), the catalogue paging the client-side search
 * rests on (R18), species creation and its three refusals (R25–R27), and creating the plant
 * itself with the field message a 422 carries (R22).
 */
class NetworkPlantCaptureClientTest {

    private lateinit var server: MockWebServer
    private val bodies = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val headers = mutableMapOf<String, Pair<String, String>>()
    private val requests = mutableListOf<Pair<String, String>>()
    private val changes = PlantDataChanges()

    private val statusPath = "/api/v1/recognition/status"
    private val consentsPath = "/api/v1/privacy/consents"
    private val identifyPath = "/api/v1/t/demo/identification/identify"
    private val speciesPath = "/api/v1/species"
    private val plantsPath = "/api/v1/t/demo/plant-instances"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val query = request.path.orEmpty().substringAfter('?', "")
                val body = request.body.readUtf8()
                synchronized(requests) { requests += path to (if (query.isEmpty()) body else "$query|$body") }
                val response = MockResponse()
                    .setResponseCode(statuses[path] ?: 200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(bodies["${request.method} $path"] ?: bodies[path] ?: "{}")
                headers[path]?.let { (name, value) -> response.setHeader(name, value) }
                return response
            }
        }
        server.start()
        bodies[statusPath] = """{"available":true,"primary_adapter":"plantnet"}"""
        bodies[consentsPath] = """[{"purpose":"plant_identification","label":"Plant identification",
            "description":"Sent to Pl@ntNet.","legal_basis":"Art. 6(1)(a) GDPR","required":true,"granted":true}]"""
        bodies["POST $consentsPath"] = """{"purpose":"plant_identification","label":"Plant identification",
            "description":"Sent to Pl@ntNet.","legal_basis":"Art. 6(1)(a) GDPR","required":true,"granted":true}"""
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(): NetworkPlantCaptureClient {
        val http = OkHttpClient()
        val json = NetworkModule.provideJson()
        return NetworkPlantCaptureClient(
            apis = InstanceApiFactory(
                httpClient = http,
                json = json,
                tokenRefresh = TokenRefreshAuthenticator(
                    SessionRefresher(http, json, InMemoryCredentialStore(), FakeConnectionStore()),
                ),
            ),
            connections = FakeConnectionStore(
                Connection.ApiKey(baseUrl = server.url("/").toString(), tenantSlug = "demo", keyHint = "…k_x"),
            ),
            credentials = InMemoryCredentialStore(Credential.ApiKey("kp_sk_x")),
            changes = changes,
        )
    }

    private fun sent(path: String) = synchronized(requests) { requests.filter { it.first == path } }

    // --- the gate (R3, R5, R6) ----------------------------------------------------------

    @Test
    fun `a granted consent on an available recogniser is ready`() = runTest {
        assertEquals(IdentificationReadiness.Ready, client().identificationReadiness())
    }

    @Test
    fun `an ungranted consent comes back with the instance's own wording`() = runTest {
        bodies[consentsPath] = """[{"purpose":"plant_identification","label":"Plant identification",
            "description":"Sent to Pl@ntNet.","legal_basis":"Art. 6(1)(a) GDPR","required":true,"granted":false}]"""

        val readiness = client().identificationReadiness() as IdentificationReadiness.ConsentRequired

        assertEquals(
            ConsentTerms("Plant identification", "Sent to Pl@ntNet.", "Art. 6(1)(a) GDPR"),
            readiness.terms,
        )
    }

    @Test
    fun `a recogniser that is not available is not offered, and no consent is read`() = runTest {
        bodies[statusPath] = """{"available":false,"primary_adapter":"plantnet"}"""

        assertEquals(IdentificationReadiness.NotOffered, client().identificationReadiness())
        assertTrue(sent(consentsPath).isEmpty())
    }

    /** An instance too old for the route answers 404, which is "not offered", not a failure. */
    @Test
    fun `an instance without the route is not offered`() = runTest {
        statuses[statusPath] = 404

        assertEquals(IdentificationReadiness.NotOffered, client().identificationReadiness())
    }

    @Test
    fun `granting the consent names the identification purpose`() = runTest {
        assertEquals(ConsentOutcome.Granted, client().grantIdentificationConsent())
        assertTrue(sent(consentsPath).single().second.contains("plant_identification"))
    }

    // --- identify → select → link (R13, R15, R16, R31) ------------------------------------

    @Test
    fun `an identification carries its ranked candidates and the organ asked for`() = runTest {
        bodies[identifyPath] = """{"is_plant":true,"request_key":"req-1","message":null,"suggestions":[
            {"rank":2,"scientific_name":"Monstera adansonii","confidence":0.21,"external_id":"b",
             "common_names":[],"species_in_database":false},
            {"rank":1,"scientific_name":"Monstera deliciosa","confidence":0.77,"external_id":"a",
             "common_names":["Swiss cheese plant"],"genus":"Monstera","species_in_database":true,
             "matched_species_key":"sp-monstera","auto_accept":true}]}"""

        val outcome = client().identify(byteArrayOf(1, 2), organ = "leaf", language = "en")

        val identified = outcome as IdentifyOutcome.Identified
        assertEquals("req-1", identified.requestKey)
        assertEquals(listOf(1, 2), identified.suggestions.map { it.rank })
        val top = identified.suggestions.first()
        assertEquals("sp-monstera", top.matchedSpeciesKey)
        assertTrue(top.speciesInDatabase)
        assertTrue(top.autoAccept)
        assertEquals(0.77, top.confidence, 0.0001)
        val body = sent(identifyPath).single().second
        assertTrue(body.contains("name=\"organ\"") && body.contains("leaf"))
        assertTrue(body.contains("name=\"language\"") && body.contains("en"))
    }

    /** A matched key the instance does not vouch for (`species_in_database: false`) is dropped. */
    @Test
    fun `a candidate the catalogue does not carry has no species key`() = runTest {
        bodies[identifyPath] = """{"is_plant":true,"request_key":"req-1","suggestions":[
            {"rank":1,"scientific_name":"X y","confidence":0.5,"external_id":"a",
             "matched_species_key":"stale","species_in_database":false}]}"""

        val identified = client().identify(byteArrayOf(1), "auto", "en") as IdentifyOutcome.Identified

        assertEquals(null, identified.suggestions.single().matchedSpeciesKey)
    }

    @Test
    fun `a missing consent on the upload is told apart from a role`() = runTest {
        statuses[identifyPath] = 403
        bodies[identifyPath] = """{"error_code":"CONSENT_REQUIRED","message":"consent"}"""
        assertEquals(IdentifyOutcome.ConsentMissing, client().identify(byteArrayOf(1), "auto", "en"))

        bodies[identifyPath] = """{"error_code":"FORBIDDEN","message":"viewer"}"""
        assertEquals(IdentifyOutcome.NotPermitted, client().identify(byteArrayOf(1), "auto", "en"))
    }

    @Test
    fun `a refused image carries the instance's reason`() = runTest {
        statuses[identifyPath] = 422
        bodies[identifyPath] = """{"message":"image could not be decoded"}"""

        val outcome = client().identify(byteArrayOf(1), "auto", "en") as IdentifyOutcome.Refused

        assertEquals("image could not be decoded", outcome.reason)
    }

    @Test
    fun `a rate limit surfaces the pause the instance asked for`() = runTest {
        statuses[identifyPath] = 429
        headers[identifyPath] = "Retry-After" to "30"

        assertEquals(IdentifyOutcome.RateLimited(30), client().identify(byteArrayOf(1), "auto", "en"))
    }

    @Test
    fun `selecting and linking address the request by key`() = runTest {
        val select = "/api/v1/t/demo/identification/req-1/select"
        val link = "/api/v1/t/demo/identification/req-1/instance"
        bodies[select] = """{"request_key":"req-1","selected_rank":2,"scientific_name":"x",
            "confidence":0.2,"species_in_database":false}"""
        bodies[link] = """{"request_key":"req-1","plant_instance_key":"p-9"}"""

        assertEquals(ActionOutcome.Done, client().selectSuggestion("req-1", 2))
        assertEquals(ActionOutcome.Done, client().linkIdentification("req-1", "p-9"))

        assertTrue(sent(select).single().second.startsWith("selected_rank=2"))
        assertTrue(sent(link).single().second.contains("\"plant_instance_key\":\"p-9\""))
    }

    // --- the catalogue and the places (R18, R23) ---------------------------------------------

    @Test
    fun `the catalogue is paged until the instance's total is reached`() = runTest {
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                val offset = request.requestUrl?.queryParameter("offset")?.toInt() ?: 0
                val items = (offset until minOf(offset + 200, 250)).joinToString(",") {
                    """{"key":"s$it","scientific_name":"Species $it","common_names":["c$it"],"family_key":null,
                        "genus":"G","hardiness_zones":[],"native_habitat":"","growth_habit":"herb",
                        "root_type":"fibrous","allelopathy_score":0.0,"base_temp":10.0}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"items":[$items],"total":250,"offset":$offset,"limit":200}""")
            }
        }

        val loaded = client().catalogue() as Fetched.Loaded

        assertEquals(250, loaded.value.size)
        assertEquals(2, calls)
        assertEquals(SpeciesEntry("s0", "Species 0", listOf("c0")), loaded.value.first())
    }

    @Test
    fun `locations are asked for under their site`() = runTest {
        val sites = "/api/v1/t/demo/sites"
        val locations = "/api/v1/t/demo/locations"
        bodies[sites] = """[{"key":"site-1","name":"Balcony","type":"outdoor","gps_coordinates":null,
            "climate_zone":"7b","total_area_m2":4.0}]"""
        bodies[locations] = """[{"key":"loc-1","name":"Left rail","site_key":"site-1","area_m2":1.0,
            "orientation":null,"light_type":"natural","irrigation_system":"manual","dimensions":[1,1]}]"""

        assertEquals(Fetched.Loaded(listOf(Site("site-1", "Balcony"))), client().sites())
        assertEquals(Fetched.Loaded(listOf(Location("loc-1", "Left rail"))), client().locations("site-1"))
        assertTrue(sent(locations).single().second.startsWith("site_key=site-1"))
    }

    @Test
    fun `a rejected credential on a read is its own answer`() = runTest {
        statuses["/api/v1/t/demo/sites"] = 401

        assertEquals(Fetched.Unauthorized, client().sites())
    }

    // --- species creation (R25–R27) ----------------------------------------------------------

    @Test
    fun `creating a species sends only what the schema declares`() = runTest {
        bodies[speciesPath] = """{"key":"sp-new","scientific_name":"Monstera adansonii","common_names":[],
            "family_key":null,"genus":"Monstera","hardiness_zones":[],"native_habitat":"","growth_habit":"herb",
            "root_type":"fibrous","allelopathy_score":0.0,"base_temp":10.0}"""

        val outcome = client().createSpecies(SpeciesDraft("Monstera adansonii", listOf("Monkey mask"), "Monstera"))

        assertEquals(SpeciesCreateOutcome.Created("sp-new"), outcome)
        val body = sent(speciesPath).single().second
        assertTrue(body.contains("\"scientific_name\":\"Monstera adansonii\""))
        assertTrue(body.contains("\"genus\":\"Monstera\""))
        assertTrue(!body.contains("family") && !body.contains("gbif"))
    }

    @Test
    fun `a species conflict and a viewer's refusal are named, not retried`() = runTest {
        statuses[speciesPath] = 409
        assertEquals(SpeciesCreateOutcome.Conflict, client().createSpecies(SpeciesDraft("A b", emptyList(), null)))

        statuses[speciesPath] = 403
        assertEquals(SpeciesCreateOutcome.NotPermitted, client().createSpecies(SpeciesDraft("A b", emptyList(), null)))
    }

    // --- creating the plant (R22, R24, R32) --------------------------------------------------

    @Test
    fun `creating a plant sends the form's fields and nothing else, then announces the change`() = runTest {
        bodies[plantsPath] = """{"key":"p-9","instance_id":"BAL_MON_01","species_key":"sp-monstera","cultivar_key":null,
            "slot_key":null,"substrate_batch_key":null,"plant_name":null,"planted_on":"2026-09-02","removed_on":null}"""
        // Subscribed before the write: the flow replays nothing, so a late subscriber would
        // prove only that it was late.
        val announced = async(start = CoroutineStart.UNDISPATCHED) { changes.changes.first() }

        val outcome = client().createPlant(
            PlantDraft(
                instanceId = "BAL_MON_01",
                speciesKey = "sp-monstera",
                plantedOn = LocalDate.of(2026, 9, 2),
                plantName = null,
                siteKey = "site-1",
                locationKey = null,
            ),
        )

        assertEquals(PlantCreateOutcome.Created("p-9"), outcome)
        val body = sent(plantsPath).single().second
        assertTrue(body.contains("\"instance_id\":\"BAL_MON_01\""))
        assertTrue(body.contains("\"planted_on\":\"2026-09-02\""))
        assertTrue(body.contains("\"site_key\":\"site-1\""))
        assertTrue(!body.contains("container_volume"))
        assertEquals(Unit, announced.await())
    }

    @Test
    fun `a rejected plant names the field the instance objected to`() = runTest {
        statuses[plantsPath] = 422
        bodies[plantsPath] = """{"message":"Validation failed","details":[
            {"field":"body.instance_id","reason":"already in use"}]}"""

        val outcome = client().createPlant(
            PlantDraft("BAL_MON_01", "sp", LocalDate.of(2026, 9, 2), null, null, null),
        ) as PlantCreateOutcome.Rejected

        assertEquals("instance_id: already in use", outcome.reason)
    }

    @Test
    fun `the identifiers in use are gathered across pages`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.requestUrl?.queryParameter("offset")?.toInt() ?: 0
                val items = (offset until minOf(offset + 200, 201)).joinToString(",") {
                    """{"key":"p$it","instance_id":"ID_$it","species_key":"s","cultivar_key":null,"slot_key":null,
                        "substrate_batch_key":null,"plant_name":null,"planted_on":"2026-01-01","removed_on":null}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("[$items]")
            }
        }

        val taken = client().instanceIds() as Fetched.Loaded

        assertEquals(201, taken.value.size)
        assertTrue("ID_200" in taken.value)
    }
}
