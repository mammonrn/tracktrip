package app.ptrip.tracktrip.ui

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.ProfilePatch
import app.ptrip.tracktrip.data.UserProfile
import app.ptrip.tracktrip.ui.theme.HudAvatar
import app.ptrip.tracktrip.ui.theme.HudCyan
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudText
import app.ptrip.tracktrip.ui.theme.HudTextDim
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * The rider's own profile: their photo, and the optional details they can fill
 * in.
 *
 * Nothing here is required, and the screen says so on every field. The account
 * works without any of it — this is for riders who want their friends to
 * recognise them in a member list.
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onSave: (ProfilePatch) -> Unit,
    onAvatarPicked: (AvatarBytes) -> Unit,
    onImageUnreadable: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = state.user

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = stringResource(R.string.profile_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
        )

        state.error?.let { HudError(it) }

        if (user == null) {
            if (state.loading) HudLoading()
            return@Column
        }

        ProfileForm(
            user = user,
            state = state,
            onSave = onSave,
            onAvatarPicked = onAvatarPicked,
            onImageUnreadable = onImageUnreadable,
            onBack = onBack,
        )
    }
}

@Composable
private fun ProfileForm(
    user: UserProfile,
    state: ProfileUiState,
    onSave: (ProfilePatch) -> Unit,
    onAvatarPicked: (AvatarBytes) -> Unit,
    onImageUnreadable: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unreadableMessage = stringResource(R.string.profile_image_unreadable)

    // Keyed on the loaded rider, so a save (which returns a fresh user) or a
    // reload rebases the fields instead of leaving stale edits on screen.
    var firstName by remember(user) { mutableStateOf(user.firstName.orEmpty()) }
    var lastName by remember(user) { mutableStateOf(user.lastName.orEmpty()) }
    var username by remember(user) { mutableStateOf(user.username.orEmpty()) }
    var phone by remember(user) { mutableStateOf(user.phone.orEmpty()) }
    var birthDate by remember(user) { mutableStateOf(user.birthDate.orEmpty()) }

    // PickVisualMedia is the system photo picker: no storage permission, and
    // the rider only ever hands over the one image they chose.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readAvatarBytes(context.contentResolver, uri)
            if (bytes == null) onImageUnreadable(unreadableMessage) else onAvatarPicked(bytes)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                HudAvatar(
                    name = user.label,
                    photoUrl = user.photoUrl,
                    diameter = 88.dp,
                    modifier = Modifier.clickable {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                )
                if (state.uploadingAvatar) {
                    CircularProgressIndicator(
                        color = HudCyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(88.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    text = user.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = HudText,
                )
                Text(
                    text = stringResource(R.string.profile_change_photo),
                    style = MaterialTheme.typography.labelSmall,
                    color = HudCyan,
                )
                user.email?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = HudTextDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        HudSurface {
            ProfileField(
                value = firstName,
                onValueChange = { firstName = it },
                label = stringResource(R.string.profile_first_name),
            )
            ProfileField(
                value = lastName,
                onValueChange = { lastName = it },
                label = stringResource(R.string.profile_last_name),
            )
            ProfileField(
                value = username,
                onValueChange = { username = it },
                label = stringResource(R.string.profile_username),
                supporting = stringResource(R.string.profile_username_hint),
            )
            ProfileField(
                value = phone,
                onValueChange = { phone = it },
                label = stringResource(R.string.profile_phone),
                keyboardType = KeyboardType.Phone,
            )
            BirthDateField(
                value = birthDate,
                onValueChange = { birthDate = it },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudPrimaryButton(
                text = stringResource(R.string.profile_save),
                onClick = {
                    onSave(
                        patchFor(
                            user = user,
                            firstName = firstName,
                            lastName = lastName,
                            username = username,
                            phone = phone,
                            birthDate = birthDate,
                        )
                    )
                },
                loading = state.saving,
                modifier = Modifier.weight(1f),
            )
            HudSecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = onBack,
                enabled = !state.saving,
            )
        }
    }
}

/**
 * Only the fields that actually changed.
 *
 * A blank field becomes an explicit null, which is how the server is told to
 * clear it — an empty string would be stored as an empty string on anything
 * that didn't trim, and "" is not the same as "not set".
 */
private fun patchFor(
    user: UserProfile,
    firstName: String,
    lastName: String,
    username: String,
    phone: String,
    birthDate: String,
): ProfilePatch {
    fun changed(edited: String, stored: String?): ProfilePatch.Field<String?>? {
        val trimmed = edited.trim().ifEmpty { null }
        return if (trimmed == stored) null else ProfilePatch.Field(trimmed)
    }

    return ProfilePatch(
        firstName = changed(firstName, user.firstName),
        lastName = changed(lastName, user.lastName),
        username = changed(username, user.username),
        phone = changed(phone, user.phone),
        birthDate = changed(birthDate, user.birthDate),
    )
}

@Composable
private fun ProfileField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.profile_optional)) },
        supportingText = supporting?.let { hint -> { Text(hint) } },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

/**
 * A read-only field that opens the platform date picker.
 *
 * Not a text field: a birth date typed by hand is the field most likely to
 * arrive as 17/03/1994 and be rejected by a server that wants 1994-03-17.
 */
@Composable
private fun BirthDateField(value: String, onValueChange: (String) -> Unit) {
    val context = LocalContext.current
    val label = stringResource(R.string.profile_birth_date)

    val showPicker = {
        val calendar = Calendar.getInstance()
        parseIsoDate(value)?.let { calendar.set(it.year, it.month, it.day) }
        DatePickerDialog(
            context,
            { _, year, monthIndex, dayOfMonth ->
                onValueChange("%04d-%02d-%02d".format(year, monthIndex + 1, dayOfMonth))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).apply {
            // The server refuses a birth date in the future; so does the
            // picker, which is a better place for the rider to find out.
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.profile_optional)) },
            readOnly = true,
            enabled = false,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // A disabled text field swallows nothing, so the tap target is a
        // transparent box laid over it rather than the field itself.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = showPicker)
        )
    }
}

private data class IsoDate(val year: Int, val month: Int, val day: Int)

private fun parseIsoDate(value: String): IsoDate? {
    val parts = value.split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    // Calendar months are zero-based; the wire format is not.
    return IsoDate(year, month - 1, day)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E1A)
@Composable
private fun ProfilePreview() {
    TracktripTheme {
        ProfileScreen(
            state = ProfileUiState(
                loading = false,
                user = UserProfile(
                    id = 1,
                    email = "rider@gmail.com",
                    displayName = "Poom",
                    photoUrl = null,
                    firstName = "Poom",
                    lastName = null,
                    username = "poom.rides",
                    phone = null,
                    birthDate = "1994-03-17",
                    totalKm = 412.5,
                ),
            ),
            onSave = {},
            onAvatarPicked = {},
            onImageUnreadable = {},
            onBack = {},
        )
    }
}
