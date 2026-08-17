package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )

        if (state is SignInUiState.Loading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        } else {
            Button(
                onClick = onSignInClick,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(text = stringResource(R.string.sign_in_with_google))
            }
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

@Preview(showBackground = true)
@Composable
private fun SignInScreenPreview() {
    MaterialTheme {
        SignInScreen(state = SignInUiState.SignedOut, onSignInClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SignInScreenErrorPreview() {
    MaterialTheme {
        SignInScreen(
            state = SignInUiState.Error("Can't reach the server. Check your connection."),
            onSignInClick = {},
        )
    }
}
