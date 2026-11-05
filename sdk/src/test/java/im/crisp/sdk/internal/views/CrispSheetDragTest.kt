package im.crisp.sdk.internal.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrispSheetDragTest {
    private val density = 2f

    @Test
    fun material3DismissesWhenDraggedFarEnough() {
        assertTrue(CrispSheetDrag.shouldDismissCompose(offsetY = 112f, velocityY = 0f, density = density))
        assertFalse(CrispSheetDrag.shouldDismissCompose(offsetY = 111f, velocityY = 0f, density = density))
    }

    @Test
    fun material3DismissesWhenFlungDown() {
        assertTrue(CrispSheetDrag.shouldDismissCompose(offsetY = 10f, velocityY = 250f, density = density))
        assertFalse(CrispSheetDrag.shouldDismissCompose(offsetY = 10f, velocityY = 249f, density = density))
    }

    @Test
    fun material3StaysWhenFlungUp() {
        assertFalse(CrispSheetDrag.shouldDismissCompose(offsetY = 100f, velocityY = -5000f, density = density))
    }

    @Test
    fun material3StaysWhenFlungBackToItsPlace() {
        assertFalse(CrispSheetDrag.shouldDismissCompose(offsetY = 0f, velocityY = 5000f, density = density))
    }

    @Test
    fun materialDismissesWhenReleasedBelowTheMiddle() {
        // Halfway between the expanded sheet top (100) and the parent bottom (1000): 550
        assertTrue(shouldDismissMaterial(sheetTop = 551f))
        assertFalse(shouldDismissMaterial(sheetTop = 550f))
    }

    @Test
    fun materialDismissesWhenFlungDown() {
        assertTrue(shouldDismissMaterial(sheetTop = 110f, velocityY = 501f))
        assertFalse(shouldDismissMaterial(sheetTop = 110f, velocityY = 500f))
    }

    @Test
    fun materialStaysWhenFlungSideways() {
        assertFalse(shouldDismissMaterial(sheetTop = 110f, velocityX = 2000f, velocityY = 1000f))
    }

    @Test
    fun materialStaysWhenMovingUp() {
        assertFalse(shouldDismissMaterial(sheetTop = 900f, velocityY = -1f))
    }

    @Test
    fun materialIgnoresVelocitiesBelowTheMinimumFlingOne() {
        assertEquals(0, CrispSheetDrag.clampMaterialVelocity(-49f, minVelocity = 50, maxVelocity = 8000))
        assertEquals(-50, CrispSheetDrag.clampMaterialVelocity(-50f, minVelocity = 50, maxVelocity = 8000))
        assertEquals(8000, CrispSheetDrag.clampMaterialVelocity(9000f, minVelocity = 50, maxVelocity = 8000))
    }

    @Test
    fun materialSettlesFromTheDistanceWhenReleasedStill() {
        assertEquals(281, materialSettleDuration(dy = -100))
        assertEquals(486, materialSettleDuration(dy = 900))
    }

    @Test
    fun materialSettlesFromTheVelocity() {
        assertEquals(240, materialSettleDuration(dy = -100, velocityY = 5000))
        // At most 600ms
        assertEquals(600, materialSettleDuration(dy = -100, velocityY = 1000))
        // Weighted by the vertical part of the velocity
        assertEquals(120, materialSettleDuration(dy = -100, velocityX = 5000, velocityY = 5000))
    }

    private fun materialSettleDuration(dy: Int, velocityX: Int = 0, velocityY: Int = 0) =
        CrispSheetDrag.materialSettleDuration(dy, velocityX, velocityY, parentWidth = 720, dragRange = 1000)

    private fun shouldDismissMaterial(sheetTop: Float, velocityX: Float = 0f, velocityY: Float = 0f) =
        CrispSheetDrag.shouldDismissMaterial(
            sheetTop = sheetTop,
            velocityX = velocityX,
            velocityY = velocityY,
            parentHeight = 1000,
            expandedOffset = 100,
            velocityThreshold = 500
        )
}
