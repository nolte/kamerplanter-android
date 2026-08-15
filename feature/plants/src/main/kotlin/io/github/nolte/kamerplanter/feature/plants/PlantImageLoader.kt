package io.github.nolte.kamerplanter.feature.plants

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient

/**
 * A Coil loader whose requests carry the stored credential.
 *
 * Thumbnail URIs point at tenant-scoped, authenticated attachments, so Coil's default loader
 * fetches them unauthenticated and every row shows a broken image. Coil is wired here rather
 * than in `:core:network` because image loading belongs to whichever feature renders images;
 * the network module supplies only the authenticated [okhttp3.OkHttpClient].
 *
 * Coil caches by URL, so scrolling back up does not refetch.
 */
internal fun plantImageLoader(context: Context, http: AuthenticatedImageClient): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http.client })) }
        .build()
