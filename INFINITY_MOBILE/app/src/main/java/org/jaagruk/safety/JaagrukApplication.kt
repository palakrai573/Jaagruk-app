package org.jaagruk.safety

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The application entry point, and Hilt's root.
 *
 * Hilt arrives in phase 1 rather than later because the layers being ported from the sibling
 * Jaagruk repository — the Room stack, the sync queue, the AR controllers and the input layer —
 * are already constructor-injected. Retrofitting them onto manual singletons would be churn with
 * no destination, and retrofitting them twice would be worse.
 *
 * The class name is already Jaagruk. The package is not, yet: the `org.jaagruk.safety` -> 
 * `org.jaagruk.safety` move is phase 2, done in one pass across all 54 files so a half-renamed
 * tree never has to compile.
 */
@HiltAndroidApp
class JaagrukApplication : Application()
