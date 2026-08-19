package app.ptrip.tracktrip.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading `Build.MANUFACTURER`, which is whatever the maker typed.
 *
 * The field is not a controlled vocabulary: it is "samsung" on one phone,
 * "HUAWEI" on another, "Xiaomi" on a third, and sub-brands ship their parent's
 * software under their own name. Getting this wrong means a rider is shown
 * instructions for a settings screen their phone does not have, which is worse
 * than showing nothing — they go looking, do not find it, and stop trusting
 * the section.
 */
class VendorBatteryAdviceTest {

    @Test
    fun `capitalisation is not a signal`() {
        listOf("samsung", "Samsung", "SAMSUNG", "  samsung  ").forEach { written ->
            assertEquals(PhoneVendor.SAMSUNG, VendorBatteryAdvice.forManufacturer(written))
        }
    }

    @Test
    fun `sub-brands get their parent's software`() {
        // Redmi and Poco are MIUI, and their owners need the autostart
        // instructions even though the word "Xiaomi" is nowhere on the phone.
        assertEquals(PhoneVendor.XIAOMI, VendorBatteryAdvice.forManufacturer("Redmi"))
        assertEquals(PhoneVendor.XIAOMI, VendorBatteryAdvice.forManufacturer("POCO"))
        assertEquals(PhoneVendor.VIVO, VendorBatteryAdvice.forManufacturer("iQOO"))
        assertEquals(PhoneVendor.HUAWEI, VendorBatteryAdvice.forManufacturer("HONOR"))
    }

    @Test
    fun `the ones with their own wording are named separately`() {
        // Realme and OnePlus share ancestry with Oppo and label their settings
        // differently. Sending a OnePlus owner to a ColorOS screen name gives
        // them nothing to find.
        assertEquals(PhoneVendor.REALME, VendorBatteryAdvice.forManufacturer("realme"))
        assertEquals(PhoneVendor.ONEPLUS, VendorBatteryAdvice.forManufacturer("OnePlus"))
        assertEquals(PhoneVendor.OPPO, VendorBatteryAdvice.forManufacturer("OPPO"))
    }

    @Test
    fun `a phone close to stock is told nothing extra, because there is nothing`() {
        // Pixels and Motorolas have no second layer: Android's own exemption
        // is the whole story. A section that appeared on every phone with
        // generic advice would teach riders to skip it.
        listOf("Google", "motorola", "Nokia", "Fairphone", "").forEach { written ->
            assertEquals(PhoneVendor.OTHER, VendorBatteryAdvice.forManufacturer(written))
        }
        assertFalse(VendorBatteryAdvice.hasExtraLayer(PhoneVendor.OTHER))
    }

    @Test
    fun `a phone that does not say who made it is treated as stock`() {
        // An emulator, or a build with the field stripped. Saying nothing is
        // the safe answer; guessing would send somebody hunting for a screen
        // that may not exist.
        assertEquals(PhoneVendor.OTHER, VendorBatteryAdvice.forManufacturer(null))
        assertEquals(PhoneVendor.OTHER, VendorBatteryAdvice.forManufacturer("   "))
    }

    @Test
    fun `every named vendor has something to say`() {
        PhoneVendor.entries
            .filter { it != PhoneVendor.OTHER }
            .forEach { vendor ->
                assertTrue("$vendor should carry advice", VendorBatteryAdvice.hasExtraLayer(vendor))
            }
    }
}
