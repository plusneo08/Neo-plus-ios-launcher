package com.neo.plus.iosluancher

import android.content.Context
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** The lifecycle Compose needs to live in a window that is not an Activity (the accessibility overlay). */
private class BarOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun resume() { if (registry.currentState != Lifecycle.State.RESUMED) registry.currentState = Lifecycle.State.RESUMED }
}

/** Builds the home-screen top bar as a view that can be added over every other app. */
object SystemBar {
    private var owner: BarOwner? = null

    fun create(ctx: Context): View {
        val o = owner ?: BarOwner().also { owner = it }
        val v = ComposeView(ctx)
        v.setViewTreeLifecycleOwner(o)
        v.setViewTreeSavedStateRegistryOwner(o)
        o.resume()
        v.setContent { SystemWideStatusBar() }
        return v
    }
}
