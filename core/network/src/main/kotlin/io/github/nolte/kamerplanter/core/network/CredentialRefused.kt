package io.github.nolte.kamerplanter.core.network

/**
 * The statuses that mean "your credential, not your request".
 *
 * 403 belongs beside 401: it means the credential authenticated but may not reach this tenant
 * — an API key whose scope no longer covers it, say. Neither is worth a retry button, and both
 * send the user to Settings.
 *
 * One definition rather than one per client. Each client keeps its own failure type, which
 * carries different things worth knowing, but the rule for reading a status is the same
 * everywhere and a copy of it in each file is a copy that can drift.
 */
internal val CREDENTIAL_REFUSED = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN)

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
