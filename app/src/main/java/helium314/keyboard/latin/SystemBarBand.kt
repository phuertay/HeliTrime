// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import helium314.keyboard.latin.settings.Settings
import kotlin.math.max
import kotlin.math.min

/**
 * Space the keyboard keeps under itself for the system bar.
 *
 * On a phone that bar is the hide-keyboard and switch-keyboard buttons. The window is full
 * screen, so those buttons are drawn on top of whatever the keyboard paints at the bottom.
 * The gesture pill and that button zone are different insets; the buttons are the taller one.
 * A window the framework has already placed above the bar does not need the space again.
 */
object SystemBarBand {
    const val LIFT_UNKNOWN = -1

    /** A wrong inset must not eat the keys. A quarter of the keyboard is the ceiling. */
    private const val MAX_SHARE = 4

    /** Whichever inset reaches higher: the gesture pill, or the button zone. */
    fun furniturePx(tappableBottom: Int, navigationBottom: Int): Int =
        max(0, max(tappableBottom, navigationBottom))

    /**
     * Pixels to keep empty at the bottom of this window.
     *
     * [liftPx] is how far the window's bottom already sits above the physical bottom of the
     * screen, or [LIFT_UNKNOWN] before the window has been laid out.
     */
    fun bandPx(
        sdkInt: Int,
        tappableBottom: Int,
        navigationBottom: Int,
        navigationVisible: Boolean,
        systemWindowBottom: Int,
        liftPx: Int,
    ): Int {
        if (sdkInt < 20) return 0
        if (sdkInt >= 29 && !navigationVisible && tappableBottom <= 0) return 0
        val furniture = if (sdkInt >= 29) {
            max(furniturePx(tappableBottom, navigationBottom), max(0, systemWindowBottom))
        } else {
            max(0, systemWindowBottom)
        }
        if (liftPx == LIFT_UNKNOWN) return furniture
        return max(0, furniture - max(0, liftPx))
    }

    /** [viewHeightPx] is the keyboard frame. Zero means it has not been measured yet. */
    fun clampToHeight(bandPx: Int, viewHeightPx: Int): Int {
        val band = max(0, bandPx)
        if (viewHeightPx <= 0) return 0
        return min(band, viewHeightPx / MAX_SHARE)
    }

    /** Bottom padding for the keyboard frame. Zero when the bar does not cover this window. */
    @JvmStatic
    fun reservedBottom(view: View, insets: WindowInsets?): Int {
        val values = Settings.getValues() ?: return 0
        if (values.mIsFloatingKeyboard) return 0
        val fromView = when {
            insets != null -> WindowInsetsCompat.toWindowInsetsCompat(insets, view)
            else -> ViewCompat.getRootWindowInsets(view)
        }
        // The view and the window manager disagree on some phones. The bar is whichever is taller.
        val fromWindow = windowInsets(view)
        val tappable = max(
            insetBottom(fromView, WindowInsetsCompat.Type.tappableElement()),
            insetBottom(fromWindow, WindowInsetsCompat.Type.tappableElement()),
        )
        val navigation = max(
            insetBottom(fromView, WindowInsetsCompat.Type.navigationBars()),
            insetBottom(fromWindow, WindowInsetsCompat.Type.navigationBars()),
        )
        val systemBars = max(
            insetBottom(fromView, WindowInsetsCompat.Type.systemBars()),
            insetBottom(fromWindow, WindowInsetsCompat.Type.systemBars()),
        )
        val navigationVisible = fromView?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true
            || fromWindow?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true
        val band = bandPx(Build.VERSION.SDK_INT, tappable, navigation, navigationVisible, systemBars, liftOf(view))
        val frame = view.findViewById<View>(R.id.main_keyboard_frame) ?: return 0
        // Height includes padding already applied. Clamp against the keys, or the padding chases itself.
        return clampToHeight(band, frame.height - frame.paddingBottom)
    }

    private fun insetBottom(insets: WindowInsetsCompat?, type: Int): Int =
        insets?.getInsets(type)?.bottom ?: 0

    private fun windowInsets(view: View): WindowInsetsCompat? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val windowManager = view.context.getSystemService(android.view.WindowManager::class.java) ?: return null
        return WindowInsetsCompat.toWindowInsetsCompat(windowManager.currentWindowMetrics.windowInsets)
    }

    private fun liftOf(view: View): Int {
        if (!view.isAttachedToWindow || view.height <= 0) return LIFT_UNKNOWN
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return screenBottom(view) - (location[1] + view.height)
    }

    private fun screenBottom(view: View): Int {
        val windowManager = view.context.getSystemService(android.view.WindowManager::class.java)
            ?: return view.resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.bounds.bottom
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }
}
