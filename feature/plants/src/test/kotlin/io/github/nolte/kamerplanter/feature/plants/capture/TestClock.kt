package io.github.nolte.kamerplanter.feature.plants.capture

/**
 * The clock the capture tests run on: `1_000_000 % 46656 = 20224`, which is `FLS` in base 36 —
 * the suffix every proposal in these suites ends in unless a test steps it.
 */
internal const val TEST_CLOCK = 1_000_000L
