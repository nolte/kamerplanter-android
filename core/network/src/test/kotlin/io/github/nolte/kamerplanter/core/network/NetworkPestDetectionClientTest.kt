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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the gating, the consent question and the failure mapping — the three places where
 * this client decides something rather than relaying it.
 *
 * The gating tests are the load-bearing ones. The feature ships disabled upstream and depends
 * on an adapter an operator has to configure, so a client that guessed "available" would put a
 * shutter in front of the user that answers 4xx; and one that guessed "consent granted" would
 * send someone's photo to a third party they never agreed to.
 */
class NetworkPestDetectionClientTest {

    private lateinit var server: MockWebServer
    private val responses = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val requests = mutableListOf<RecordedRequest>()

    private val statusPath = "/api/v1/t/demo/pests/status"
    private val consentsPath = "/api/v1/privacy/consents"
    private val detectPath = "/api/v1/t/demo/pests/detect"
    private val plantDetectPath = "/api/v1/t/demo/pests/plants/p1/detect"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                synchronized(requests) { requests += request }
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

    private fun client(connected: Boolean = true) = NetworkPestDetectionClient(
        apis = InstanceApiFactory(
            httpClient = OkHttpClient(),
            json = NetworkModule.provideJson(),
            tokenRefresh = TokenRefreshAuthenticator(
                SessionRefresher(
                    OkHttpClient(),
                    NetworkModule.provideJson(),
                    InMemoryCredentialStore(),
                    FakeConnectionStore(),
                ),
            ),
        ),
        connections = FakeConnectionStore(
            Connection.ApiKey(
                baseUrl = server.url("/").toString(),
                tenantSlug = "demo",
                keyHint = "…k_x",
            ).takeIf { connected },
        ),
        credentials = InMemoryCredentialStore(Credential.ApiKey("kp_sk_x")),
        json = NetworkModule.provideJson(),
    )

    private fun givenStatus(
        available: Boolean = true,
        featureEnabled: Boolean = true,
        activeAdapter: String? = "local",
        adapters: String = """{"local":{"configured":true,"is_external":false}}""",
    ) {
        responses[statusPath] = """
            {"available":$available,"feature_enabled":$featureEnabled,"primary_adapter":"local",
             "active_adapter":${activeAdapter?.let { "\"$it\"" } ?: "null"},"adapters":$adapters}
        """.trimIndent()
    }

    /** `GET /privacy/consents` lists every known purpose, granted or not, with its wording. */
    private fun givenConsents(vararg granted: Pair<String, Boolean>) {
        responses[consentsPath] = granted.joinToString(",", "[", "]") { (purpose, isGranted) ->
            """{"description":"Your photo is sent to an external recognition service.",
                "granted":$isGranted,"label":"Cloud pest detection","legal_basis":"consent",
                "purpose":"$purpose","required":false}"""
        }
    }

    private fun detection(
        isConfident: Boolean = true,
        findings: String = "[]",
    ) = """
        {"source":"local","adapter_key":"local","is_confident":$isConfident,"trigger":"manual",
         "tiles_processed":4,"suggested_next_step":"Look again","image_hash":"sha256:x",
         "disclaimer":"Only an estimate.","key":"det-1","findings":$findings}
    """.trimIndent()

    private fun jpeg(size: Int = 32) = ByteArray(size) { 0x1 }

    private fun pathsHit() = synchronized(requests) { requests.map { it.path.orEmpty().substringBefore('?') } }

    // ── Gating ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an enabled feature with a configured adapter is ready`() = runTest {
        givenStatus()

        assertEquals(DetectionReadiness.Ready, client().readiness())
    }

    @Test
    fun `a disabled feature is not offered`() = runTest {
        givenStatus(featureEnabled = false)

        assertEquals(DetectionReadiness.NotOffered, client().readiness())
    }

    /** `available` is the backend's own summary; it can be false while the flag is on. */
    @Test
    fun `an unavailable feature is not offered`() = runTest {
        givenStatus(available = false)

        assertEquals(DetectionReadiness.NotOffered, client().readiness())
    }

    /**
     * An instance that lists no adapters at all has nothing configured, which is exactly what
     * "your operator has not set this up" means — and is a different answer from the one below.
     */
    @Test
    fun `an instance with no adapters at all is not offered`() = runTest {
        responses[statusPath] = """
            {"available":true,"feature_enabled":true,"primary_adapter":"local",
             "active_adapter":"local"}
        """.trimIndent()

        assertEquals(DetectionReadiness.NotOffered, client().readiness())
    }

    /** An empty map lists no adapters either — a serialised default, not a missing answer. */
    @Test
    fun `an instance with an empty adapter map is not offered`() = runTest {
        givenStatus(adapters = "{}")

        assertEquals(DetectionReadiness.NotOffered, client().readiness())
    }

    /**
     * An instance that names an active adapter and then does not describe it.
     *
     * Not "the operator has not enabled this" — the feature is on — and not "unreachable"
     * either, since it answered 200. Telling the user either would send them after the wrong
     * thing: an administrator who has already switched it on, or a server that is answering
     * perfectly well.
     */
    @Test
    fun `an active adapter missing from the map is an answer the app cannot read`() = runTest {
        givenStatus(
            activeAdapter = "cloud",
            adapters = """{"local":{"configured":true,"is_external":false}}""",
        )

        assertEquals(DetectionReadiness.NotUnderstood, client().readiness())
    }

    @Test
    fun `an adapter that is not configured is not offered`() = runTest {
        givenStatus(adapters = """{"local":{"configured":false,"is_external":false}}""")

        assertEquals(DetectionReadiness.NotOffered, client().readiness())
    }

    /**
     * An instance older than the endpoint answers 404, and the app supports whatever the user
     * runs. From their side that is the same as an operator not offering it.
     */
    @Test
    fun `an instance without the endpoint is not offered`() = runTest {
        statuses[statusPath] = 404

        assertEquals(DetectionReadiness.NotOffered, client().readiness())
    }

    @Test
    fun `a refused credential is reported as such`() = runTest {
        statuses[statusPath] = 401

        assertEquals(DetectionReadiness.Unauthorized, client().readiness())
    }

    /**
     * A 403 here is not a refused credential, and this method used to say it was.
     *
     * An API key that authenticates but whose scope excludes pest detection answers 403. Told
     * "your connection is no longer accepted, connect again", its owner re-pairs a connection
     * that works perfectly — and lands back on the same 403. `detect()` already made this
     * distinction; `readiness()` contradicted it two methods away.
     */
    @Test
    fun `a credential that may not run detections is told apart from a refused one`() = runTest {
        statuses[statusPath] = 403

        assertEquals(DetectionReadiness.NotPermitted, client().readiness())
    }

    @Test
    fun `without a connection there is nothing to ask`() = runTest {
        assertEquals(DetectionReadiness.NotConnected, client(connected = false).readiness())
        assertTrue("no request may be made without a connection", pathsHit().isEmpty())
    }

    // ── Consent ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a cloud adapter without consent asks for it`() = runTest {
        givenStatus(
            activeAdapter = "cloud",
            adapters = """{"cloud":{"configured":true,"is_external":true,
                "requires_consent":"pest_detection_cloud"}}""",
        )
        givenConsents("pest_detection_cloud" to false)

        val readiness = client().readiness() as DetectionReadiness.ConsentRequired

        assertEquals("pest_detection_cloud", readiness.purpose)
        // Verbatim from the instance. The user is giving an Art. 6(1)(a) GDPR consent, and
        // app-authored wording would have them agreeing to text this app invented about
        // processing it does not perform.
        assertEquals(
            ConsentTerms(
                label = "Cloud pest detection",
                description = "Your photo is sent to an external recognition service.",
                legalBasis = "consent",
            ),
            readiness.terms,
        )
    }

    /**
     * An instance that answered nothing leaves the app with no wording to show — so it says so
     * rather than inventing any, and the screen falls back to its own text knowingly.
     */
    @Test
    fun `an unreadable consent list yields no wording rather than invented wording`() = runTest {
        givenStatus(
            activeAdapter = "cloud",
            adapters = """{"cloud":{"configured":true,"is_external":true,
                "requires_consent":"pest_detection_cloud"}}""",
        )
        statuses[consentsPath] = 500

        val readiness = client().readiness() as DetectionReadiness.ConsentRequired

        assertNull(readiness.terms)
    }

    @Test
    fun `a cloud adapter with consent is ready`() = runTest {
        givenStatus(
            activeAdapter = "cloud",
            adapters = """{"cloud":{"configured":true,"is_external":true,
                "requires_consent":"pest_detection_cloud"}}""",
        )
        givenConsents("pest_detection_cloud" to true)

        assertEquals(DetectionReadiness.Ready, client().readiness())
    }

    /**
     * The requirement is read off the *active* adapter, not off any adapter in the map.
     *
     * Asking for a consent the upload will never need trains the user to click through consent
     * prompts, which is exactly what makes the real one worthless.
     */
    @Test
    fun `a configured cloud adapter does not make the active local one need consent`() = runTest {
        givenStatus(
            activeAdapter = "local",
            adapters = """{"local":{"configured":true,"is_external":false},
                "cloud":{"configured":true,"is_external":true,
                "requires_consent":"pest_detection_cloud"}}""",
        )

        assertEquals(DetectionReadiness.Ready, client().readiness())
        assertFalse("the consent list is irrelevant here", consentsPath in pathsHit())
    }

    /**
     * An unreadable consent list must not be read as "granted".
     *
     * The whole reason for asking is that an image must not leave the device on an assumption,
     * so the failure routes back through the consent prompt — which grants it again, harmlessly
     * — rather than uploading on a guess.
     */
    @Test
    fun `an unreadable consent list asks rather than assumes`() = runTest {
        givenStatus(
            activeAdapter = "cloud",
            adapters = """{"cloud":{"configured":true,"is_external":true,
                "requires_consent":"pest_detection_cloud"}}""",
        )
        statuses[consentsPath] = 500

        assertTrue(client().readiness() is DetectionReadiness.ConsentRequired)
    }

    @Test
    fun `granting a consent posts the purpose`() = runTest {
        responses[consentsPath] = """{"description":"d","granted":true,"label":"l",
            "legal_basis":"consent","purpose":"pest_detection_cloud","required":false}"""

        assertEquals(ConsentOutcome.Granted, client().grantConsent("pest_detection_cloud"))

        val posted = synchronized(requests) { requests.single { it.method == "POST" } }
        assertTrue(posted.body.readUtf8().contains("pest_detection_cloud"))
    }

    // ── Detection ────────────────────────────────────────────────────────────────────

    @Test
    fun `maps findings, their boxes and the beneficial flag`() = runTest {
        responses[detectPath] = detection(
            findings = """[
              {"label":"tetranychus_urticae","category":"pest","common_name":"Spider mite",
               "confidence":0.87,"mode":"direct","matched_pest_key":"pest-1",
               "bounding_box":{"x":0.3,"y":0.28,"width":0.22,"height":0.2}},
              {"label":"coccinella","category":"pest","common_name":"Ladybird",
               "confidence":0.6,"mode":"direct","matched_beneficial_key":"ben-1"}
            ]""",
        )

        val outcome = client().detect(jpeg(), plantKey = null, language = "en")

        val detection = (outcome as DetectionOutcome.Completed).detection
        assertEquals("det-1", detection.key)
        assertTrue(detection.isConfident)
        assertEquals("Only an estimate.", detection.disclaimer)
        assertEquals(4, detection.tilesProcessed)

        val mite = detection.findings.first()
        assertEquals("Spider mite", mite.commonName)
        assertEquals(0.87, mite.confidence, 1e-9)
        assertEquals(BoundingBox(0.3, 0.28, 0.22, 0.2), mite.boundingBox)
        assertFalse(mite.isBeneficial)

        val ladybird = detection.findings[1]
        // The fixture calls it a pest and matches it to a beneficial, which is the only way to
        // tell the two signals apart: with both agreeing, reading the category would pass too.
        // The key is what the backend resolved against its own master data; the category is the
        // recognizer's vocabulary — and getting this backwards is someone spraying a ladybird.
        assertEquals("pest", ladybird.category)
        assertTrue("matched_beneficial_key decides, not the category", ladybird.isBeneficial)
        assertNull("a symptom-mode finding carries no box", ladybird.boundingBox)
    }

    /** Abstention is an answer the screen renders, not a failure it hides. */
    @Test
    fun `an abstention comes back as a completed detection`() = runTest {
        responses[detectPath] = detection(isConfident = false)

        val outcome = client().detect(jpeg(), plantKey = null, language = "en")

        val detection = (outcome as DetectionOutcome.Completed).detection
        assertFalse(detection.isConfident)
        assertTrue(detection.findings.isEmpty())
    }

    @Test
    fun `a plant key routes to the plant-bound endpoint`() = runTest {
        responses[plantDetectPath] = detection()

        client().detect(jpeg(), plantKey = "p1", language = "en")

        assertEquals(listOf(plantDetectPath), pathsHit())
    }

    /**
     * The language part has to reach FastAPI's `Form(...)` unquoted.
     *
     * With the JSON converter first in the chain it would be encoded as `"en"` — quotes
     * included — and the backend would look for a language literally named `"en"`. The scalar
     * converter in `InstanceApiFactory` is what prevents that, and this is the call site that
     * proves it end to end rather than by reflection.
     */
    @Test
    fun `sends the image and an unquoted language part`() = runTest {
        responses[detectPath] = detection()

        client().detect(jpeg(), plantKey = null, language = "en")

        val body = synchronized(requests) { requests.single() }.body.readUtf8()
        assertTrue(body, body.contains("name=\"image\""))
        assertTrue(body, body.contains("name=\"language\""))
        assertTrue("the language must not be JSON-quoted: $body", body.contains("\r\n\r\nen\r\n"))
    }

    /**
     * Refused locally, before the upload.
     *
     * The upload is the expensive half — a microscope frame over a phone uplink — and there is
     * no point spending it to be told a size the app could measure itself.
     */
    @Test
    fun `an oversized image is refused without a request`() = runTest {
        val outcome = client().detect(jpeg(9 * 1024 * 1024), plantKey = null, language = "en")

        assertEquals(DetectionOutcome.Refused(RefusedReason.TOO_LARGE), outcome)
        assertTrue("nothing may be uploaded", pathsHit().isEmpty())
    }

    @Test
    fun `an empty frame is refused without a request`() = runTest {
        val outcome = client().detect(ByteArray(0), plantKey = null, language = "en")

        assertEquals(DetectionOutcome.Refused(RefusedReason.NOT_PROCESSABLE), outcome)
        assertTrue(pathsHit().isEmpty())
    }

    /**
     * The rejection a self-hoster actually meets.
     *
     * nginx defaults to a 1 MB request body, which is under every microscope capture, and it
     * answers 413 before the instance sees anything — so the local 8 MB guard never fires and
     * the instance's own limit is never consulted. Left unmapped this reads as "your instance
     * could not be reached. Check that it is running", which sends someone to debug a server
     * that is answering correctly.
     */
    @Test
    fun `a proxy rejecting the body size is told apart from the instance's own limit`() = runTest {
        statuses[detectPath] = 413

        assertEquals(
            DetectionOutcome.Refused(RefusedReason.REFUSED_BY_PROXY),
            client().detect(jpeg(), plantKey = null, language = "en"),
        )
    }

    /**
     * The consent write can be refused on its own.
     *
     * `readiness()` already separates a 403 from a 401; this method used to fold them back
     * together, so a credential that authenticates but may not record a consent was told to
     * re-pair — which returns it to the same 403.
     */
    @Test
    fun `a consent the credential may not record is not a refused credential`() = runTest {
        statuses[consentsPath] = 403

        assertEquals(ConsentOutcome.NotPermitted, client().grantConsent("pest_detection_cloud"))
    }

    @Test
    fun `an unsupported media type is refused`() = runTest {
        statuses[detectPath] = 415

        assertEquals(
            DetectionOutcome.Refused(RefusedReason.UNSUPPORTED_TYPE),
            client().detect(jpeg(), plantKey = null, language = "en"),
        )
    }

    @Test
    fun `an unreadable image comes back as not processable`() = runTest {
        statuses[detectPath] = 422

        assertEquals(
            DetectionOutcome.Refused(RefusedReason.NOT_PROCESSABLE),
            client().detect(jpeg(), plantKey = null, language = "en"),
        )
    }

    /**
     * Two different 403s, told apart by the error envelope.
     *
     * Only one of them has a way out the app can offer, so collapsing them would leave a user
     * whose consent lapsed staring at "you are not allowed to do this".
     */
    @Test
    fun `a consent 403 is told apart from a permission 403`() = runTest {
        statuses[detectPath] = 403
        responses[detectPath] = """{"error_code":"CONSENT_REQUIRED","error_id":"e1",
            "message":"Consent for 'pest_detection_cloud' is required for this action.",
            "method":"POST","path":"$detectPath","timestamp":"2026-08-15T09:00:00Z"}"""

        assertEquals(
            DetectionOutcome.Refused(RefusedReason.CONSENT_MISSING),
            client().detect(jpeg(), plantKey = null, language = "en"),
        )

        responses[detectPath] = """{"error_code":"PERMISSION_DENIED","error_id":"e2",
            "message":"Not allowed.","method":"POST","path":"$detectPath",
            "timestamp":"2026-08-15T09:00:00Z"}"""

        assertEquals(
            DetectionOutcome.Refused(RefusedReason.NOT_PERMITTED),
            client().detect(jpeg(), plantKey = null, language = "en"),
        )
    }

    /**
     * A reverse proxy in front of the instance answers a 403 with HTML, and a detection has to
     * fail as a detection rather than as a parse error.
     */
    @Test
    fun `a 403 whose body is not an error envelope is a permission failure`() = runTest {
        statuses[detectPath] = 403
        responses[detectPath] = "<html><body>Forbidden</body></html>"

        assertEquals(
            DetectionOutcome.Refused(RefusedReason.NOT_PERMITTED),
            client().detect(jpeg(), plantKey = null, language = "en"),
        )
    }

    @Test
    fun `a refused credential on upload is reported as such`() = runTest {
        statuses[detectPath] = 401

        assertEquals(
            DetectionOutcome.Unauthorized,
            client().detect(jpeg(), plantKey = null, language = "en"),
        )
    }

    @Test
    fun `a server error is worth a retry`() = runTest {
        statuses[detectPath] = 503

        val outcome = client().detect(jpeg(), plantKey = null, language = "en")

        assertTrue(outcome.toString(), outcome is DetectionOutcome.Unavailable)
    }
}
