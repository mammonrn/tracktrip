package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R

/**
 * Placeholder landing screen after a successful sign-in. Intentionally empty —
 * the real trip list arrives once the backend has trip endpoints.
 */
@Composable
fun TripListScreen(
    displayName: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.trips_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = displayName?.let { stringResource(R.string.signed_in_as, it) }
                ?: stringResource(R.string.signed_in),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onSignOut, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.sign_out))
        }
    }
}
