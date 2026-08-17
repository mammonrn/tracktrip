package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudTextDim
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme

/** Matches the backend's 1–60 characters after trimming. */
private const val TRIP_NAME_MAX_LENGTH = 60

@Composable
fun CreateTripScreen(
    creating: Boolean,
    error: String?,
    onCreate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val canSubmit = trimmed.isNotEmpty() && trimmed.length <= TRIP_NAME_MAX_LENGTH && !creating

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        HudTopBar(
            title = stringResource(R.string.new_trip),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
            modifier = Modifier.padding(bottom = 12.dp),
        )

        HudSurface {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= TRIP_NAME_MAX_LENGTH) name = it },
                label = { Text(stringResource(R.string.trip_name_label)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${trimmed.length}/$TRIP_NAME_MAX_LENGTH",
                style = MaterialTheme.typography.labelSmall,
                color = HudTextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        error?.let { HudError(it, modifier = Modifier.padding(top = 12.dp)) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudPrimaryButton(
                text = stringResource(R.string.create_trip),
                onClick = { onCreate(trimmed) },
                enabled = canSubmit,
                loading = creating,
                modifier = Modifier.weight(1f),
            )
            HudSecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = onBack,
                enabled = !creating,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E1A)
@Composable
private fun CreateTripPreview() {
    TracktripTheme {
        CreateTripScreen(creating = false, error = null, onCreate = {}, onBack = {})
    }
}
