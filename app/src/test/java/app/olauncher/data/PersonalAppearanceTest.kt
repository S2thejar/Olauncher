package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalAppearanceTest {
    @Test fun colorAcceptsSixDigits() {
        assertEquals(0xffeceff1.toInt(), PersonalAppearance.colorFromHex(" #ECEff1 "))
        assertEquals(0xff000000.toInt(), PersonalAppearance.colorFromHex("000000"))
    }

    @Test fun colorRejectsInvalidOrAlphaValues() {
        for (value in listOf("", "#abc", "#ffeeaabb", "badcol", "+12345", "##000000")) {
            assertNull(value, PersonalAppearance.colorFromHex(value))
        }
    }
}
