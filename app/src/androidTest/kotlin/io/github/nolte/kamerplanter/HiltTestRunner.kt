package io.github.nolte.kamerplanter

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps [KamerplanterApplication] for Hilt's test application so an instrumented test can
 * replace bindings before the graph is built.
 *
 * Without this the real `@HiltAndroidApp` object is constructed first and every binding is
 * already frozen — `@BindValue` and test modules would compile and then quietly have no
 * effect, which is the failure mode that looks like a passing test.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
