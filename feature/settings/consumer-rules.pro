# No custom keep rules are needed yet: ML Kit barcode-scanning ships its own consumer
# proguard rules in its AAR, and the pairing seam (PairingClient) is plain Kotlin.
# This file exists so consumerProguardFiles(...) resolves and stays the obvious home
# for any future rule this module's engine requires.
