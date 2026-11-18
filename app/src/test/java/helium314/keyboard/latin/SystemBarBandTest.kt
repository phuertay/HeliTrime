// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemBarBandTest {
    @Test fun `gesture buttons are the taller inset`() {
        // A phone in gesture navigation: the pill is 42px, the hide-keyboard and
        // switch-keyboard zone is 135px, and the window reaches the physical bottom.
        assertEquals(135, SystemBarBand.bandPx(35, 42, 135, true, 135, 0))
    }

    @Test fun `three button bar is that same zone`() {
        assertEquals(135, SystemBarBand.bandPx(35, 135, 135, true, 135, 0))
    }

    @Test fun `system bars fill in when the navigation inset is missing`() {
        assertEquals(135, SystemBarBand.bandPx(35, 0, 0, true, 135, 0))
    }

    @Test fun `a window already above the bar keeps no extra space`() {
        assertEquals(0, SystemBarBand.bandPx(35, 42, 135, true, 135, 135))
        assertEquals(85, SystemBarBand.bandPx(35, 42, 135, true, 135, 50))
    }

    @Test fun `a hidden bar with nothing tappable keeps no space`() {
        assertEquals(0, SystemBarBand.bandPx(35, 0, 135, false, 135, 0))
    }

    @Test fun `before tappable insets the system window is the bar`() {
        assertEquals(48, SystemBarBand.bandPx(28, 0, 0, true, 48, 0))
        assertEquals(0, SystemBarBand.bandPx(28, 0, 0, true, 48, 48))
    }

    @Test fun `an unmeasured window reserves the bar until the next layout`() {
        assertEquals(135, SystemBarBand.bandPx(35, 42, 135, true, 135, SystemBarBand.LIFT_UNKNOWN))
    }

    @Test fun `the band cannot eat the keyboard`() {
        assertEquals(0, SystemBarBand.clampToHeight(135, 0))
        assertEquals(100, SystemBarBand.clampToHeight(800, 400))
        assertEquals(135, SystemBarBand.clampToHeight(135, 800))
    }
}
