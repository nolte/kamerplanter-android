package io.github.nolte.kamerplanter.core.network

/**
 * The statuses that mean "your credential, not your request".
 *
 * 403 belongs beside 401 for a *read*: it means the credential authenticated but may not reach
 * this tenant — an API key whose scope no longer covers it, say. Neither is worth a retry
 * button, and both send the user to Settings.
 *
 * For a **write** the two part company, which is why the constants are visible on their own.
 * A 403 on writing a diary entry is a role — this account may not write here — and no amount
 * of reconnecting widens a role. Telling its owner to re-pair sends them round a loop that
 * ends where it began.
 *
 * One definition rather than one per client. Each client keeps its own failure type, which
 * carries different things worth knowing, but the rule for reading a status is the same
 * everywhere and a copy of it in each file is a copy that can drift.
 */
internal val CREDENTIAL_REFUSED = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN)

internal const val HTTP_UNAUTHORIZED = 401
internal const val HTTP_FORBIDDEN = 403
