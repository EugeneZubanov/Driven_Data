package com.quietnight.app

import com.quietnight.app.ui.OemHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemHelpTest {
    @Test fun picksStepsByMaker() {
        assertEquals("Samsung", OemHelp.stepsFor("samsung").brand)
        assertEquals("Xiaomi", OemHelp.stepsFor("Xiaomi").brand)
        assertEquals("Xiaomi", OemHelp.stepsFor("POCO").brand)
        assertEquals("Honor", OemHelp.stepsFor("HONOR").brand)
        assertEquals("Huawei", OemHelp.stepsFor("HUAWEI").brand)
        assertEquals("OnePlus", OemHelp.stepsFor("OnePlus").brand)
        assertEquals("Pixel", OemHelp.stepsFor("Google").brand)
        assertEquals("your phone", OemHelp.stepsFor("Fairphone").brand)
        assertTrue(listOf("samsung", "xiaomi", "huawei", "oneplus", "google", "", "x").all { OemHelp.stepsFor(it).steps.isNotEmpty() })
    }
}
