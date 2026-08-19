package app.ptrip.tracktrip.location

/**
 * The extra thing a rider has to switch off, on the phone they actually own.
 *
 * ## Why Android's own exemption is not enough
 *
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` turns off *Android's* Doze and
 * app-standby. Several manufacturers ship a second, entirely separate killer
 * on top of it — Samsung's sleeping-apps list, Xiaomi's autostart, Oppo's and
 * Vivo's background permissions, Huawei's protected apps. Granting the
 * standard exemption tells those layers nothing at all, and the symptom is
 * the one that started this: a rider's own pin going stale mid-ride on a phone
 * whose settings all look correct.
 *
 * ## Why this is a written path and not a deep link
 *
 * There are intents that open some of these screens. They are undocumented,
 * they are different on every skin version, and they are removed without
 * notice — a deep link that worked on MIUI 12 throws `ActivityNotFoundException`
 * on 14, and the rider is left staring at a button that does nothing. A
 * sentence naming the setting is worse than a working link and much better
 * than a broken one, and it keeps working when the phone is updated.
 *
 * ## Why the wording is per-brand
 *
 * Each of these calls the same idea something different. Telling a Samsung
 * owner to "disable autostart restrictions" sends them looking for a setting
 * their phone does not have; the words below are the ones actually printed on
 * each maker's screen, in the place they are printed.
 */
enum class PhoneVendor {
    SAMSUNG,
    XIAOMI,
    OPPO,
    VIVO,
    HUAWEI,
    REALME,
    ONEPLUS,

    /**
     * Pixels, Motorolas, Nokias, and anything else that ships close to stock.
     *
     * These have no second layer, so the standard exemption is genuinely the
     * whole story and there is nothing extra to tell anybody. Saying something
     * anyway would teach riders that this section is noise.
     */
    OTHER,
}

object VendorBatteryAdvice {

    /**
     * Which vendor's advice to show, from `Build.MANUFACTURER`.
     *
     * Matched case-insensitively on a substring, because the field is whatever
     * the maker wrote: "samsung", "Xiaomi", "HUAWEI", "OnePlus". Realme and
     * OnePlus are named separately from Oppo despite the shared parentage —
     * their settings screens are labelled differently, and a rider following
     * instructions for a phone they do not have gives up rather than hunting.
     */
    fun forManufacturer(manufacturer: String?): PhoneVendor {
        val name = manufacturer?.trim()?.lowercase().orEmpty()
        return when {
            name.isEmpty() -> PhoneVendor.OTHER
            name.contains("samsung") -> PhoneVendor.SAMSUNG
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") ->
                PhoneVendor.XIAOMI
            name.contains("realme") -> PhoneVendor.REALME
            name.contains("oneplus") -> PhoneVendor.ONEPLUS
            name.contains("oppo") -> PhoneVendor.OPPO
            name.contains("vivo") || name.contains("iqoo") -> PhoneVendor.VIVO
            name.contains("huawei") || name.contains("honor") -> PhoneVendor.HUAWEI
            else -> PhoneVendor.OTHER
        }
    }

    /** Whether this phone has a second layer worth telling its owner about. */
    fun hasExtraLayer(vendor: PhoneVendor): Boolean = vendor != PhoneVendor.OTHER
}
