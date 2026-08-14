package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.Tenant
import io.github.nolte.kamerplanter.core.connection.maskSecret

/**
 * Composes the connection to store from what the user supplied and what verification
 * resolved. Returns `null` when a credential-bearing method has no [tenant] to adopt —
 * an unscoped credential is not a connection (R15), and the caller turns that into a
 * failure rather than persisting half of one.
 *
 * Light mode ignores [tenant] and [identity]: an instance without accounts has neither.
 *
 * Lives here rather than beside [Connection] in `:core:connection` because it reads a
 * [ConnectionRequest] — the in-flight, still-secret-bearing input of the pairing flow,
 * which is this module's concern. `:core:connection` holds what a connection *is* once it
 * has been established; how one gets composed belongs to the flow that establishes it.
 */
internal fun ConnectionRequest.connectionFor(tenant: Tenant?, identity: String?): Connection? =
    when (this) {
        is ConnectionRequest.QrPairing ->
            tenant?.let { Connection.QrPairing(baseUrl, it.slug, identity) }
        is ConnectionRequest.ApiKey ->
            tenant?.let { Connection.ApiKey(baseUrl, it.slug, maskSecret(key)) }
        is ConnectionRequest.LightMode -> Connection.LightMode(baseUrl)
    }
