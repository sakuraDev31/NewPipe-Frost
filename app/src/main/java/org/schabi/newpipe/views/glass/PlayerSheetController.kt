package org.schabi.newpipe.views.glass

import android.view.View
import androidx.fragment.app.FragmentActivity

/**
 * Connected player sheet controller delegating to LiquidGlassPlayerSheetController.
 */
class PlayerSheetController(
    activity: FragmentActivity,
    playerSheet: View,
    groundedDock: View,
    landscapeDuoPill: View? = null
) : LiquidGlassPlayerSheetController(activity, playerSheet, groundedDock, landscapeDuoPill)
