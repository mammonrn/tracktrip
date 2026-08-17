package app.ptrip.tracktrip.data

import org.json.JSONObject

/**
 * The signed-in rider, as GET /me returns them.
 *
 * Everything except [id] is optional. A rider signs in with Google and is
 * immediately able to use the app, so the profile fields stay empty until they
 * choose to fill them in.
 */
data class UserProfile(
    val id: Long,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val firstName: String?,
    val lastName: String?,
    val username: String?,
    val phone: String?,
    val birthDate: String?,
    val totalKm: Double,
) {
    /** What to show when there is no name yet — never a blank line. */
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: email ?: "Rider $id"
}

/**
 * The fields a rider may edit, and whether each was touched.
 *
 * `null` inside a [Field] clears the value on the server, which is a different
 * thing from the field being absent — hence the wrapper rather than a nullable
 * property. Without it there would be no way to say "leave the phone number
 * alone" and "remove the phone number" in the same shape.
 */
data class ProfilePatch(
    val displayName: Field<String>? = null,
    val firstName: Field<String?>? = null,
    val lastName: Field<String?>? = null,
    val username: Field<String?>? = null,
    val phone: Field<String?>? = null,
    val birthDate: Field<String?>? = null,
) {
    @JvmInline
    value class Field<out T>(val value: T)

    val isEmpty: Boolean
        get() = displayName == null &&
            firstName == null &&
            lastName == null &&
            username == null &&
            phone == null &&
            birthDate == null

    internal fun toJson(): JSONObject {
        val json = JSONObject()
        displayName?.let { json.put("display_name", it.value) }
        firstName?.let { json.putOrNull("first_name", it.value) }
        lastName?.let { json.putOrNull("last_name", it.value) }
        username?.let { json.putOrNull("username", it.value) }
        phone?.let { json.putOrNull("phone", it.value) }
        birthDate?.let { json.putOrNull("birth_date", it.value) }
        return json
    }
}

/** `put(key, null)` *removes* the key in org.json; this sends a JSON null. */
private fun JSONObject.putOrNull(key: String, value: String?): JSONObject =
    put(key, value ?: JSONObject.NULL)

/** The signed-in rider's own account: reading it, editing it, and their photo. */
class MeApi(private val client: ApiClient) {

    suspend fun profile(): UserProfile = JSONObject(client.get("/me")).toUserProfile()

    suspend fun updateProfile(patch: ProfilePatch): UserProfile =
        JSONObject(client.patch("/me", patch.toJson())).toUserProfile()

    /**
     * Uploads a new avatar and returns the rider with `photoUrl` pointing at
     * it.
     *
     * The filename is a placeholder: the server discards whatever is sent and
     * names the file itself, but multipart wants a name and an empty one
     * confuses some proxies.
     */
    suspend fun uploadAvatar(bytes: ByteArray, contentType: String): UserProfile =
        JSONObject(
            client.postFile(
                path = "/me/avatar",
                fieldName = "avatar",
                filename = "avatar",
                contentType = contentType,
                bytes = bytes,
            )
        ).toUserProfile()
}

internal fun JSONObject.toUserProfile() = UserProfile(
    id = optLong("id"),
    email = optStringOrNull("email"),
    displayName = optStringOrNull("display_name"),
    photoUrl = optStringOrNull("photo_url"),
    firstName = optStringOrNull("first_name"),
    lastName = optStringOrNull("last_name"),
    username = optStringOrNull("username"),
    phone = optStringOrNull("phone"),
    birthDate = optStringOrNull("birth_date"),
    totalKm = optDouble("total_km", 0.0),
)
