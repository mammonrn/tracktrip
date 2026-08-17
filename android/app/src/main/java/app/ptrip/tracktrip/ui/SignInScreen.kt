package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.ui.theme.HudAmber
import app.ptrip.tracktrip.ui.theme.HudCyan
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.TracktripTheme

@Composable
fun SignInScreen(
    state: SignInUiState,
    onSignInClick: () -> Unit,
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
            text = stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = HudAmber,
        )

        if (state is SignInUiState.Loading) {
            CircularProgressIndicator(
                color = HudCyan,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            HudPrimaryButton(
                text = stringResource(R.string.sign_in_with_google),
                onClick = onSignInClick,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        if (state is SignInUiState.Error) {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E1A)
@Composable
private fun SignInScreenPreview() {
    TracktripTheme {
        SignInScreen(state = SignInUiState.SignedOut, onSignInClick = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E1A)
@Composable
private fun SignInScreenErrorPreview() {
    TracktripTheme {
        SignInScreen(
            state = SignInUiState.Error("Can't reach the server. Check your connection."),
            onSignInClick = {},
        )
    }
}
