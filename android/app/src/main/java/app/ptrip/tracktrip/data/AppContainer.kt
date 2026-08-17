package app.ptrip.tracktrip.data

import android.content.Context
import app.ptrip.tracktrip.R

/**
 * The app's shared, long-lived objects, built once and handed to whatever
 * needs them.
 *
 * One [TokenStore] and one [ApiClient] for the whole process is the point:
 * the client serialises token refreshes through a mutex, which only works if
 * everyone is going through the same instance.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val baseUrl: String = appContext.getString(R.string.api_base_url)

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    val apiClient: ApiClient by lazy { ApiClient(baseUrl = baseUrl, tokenStore = tokenStore) }

    val tripApi: TripApi by lazy { TripApi(apiClient) }

    val authApi: AuthApi by lazy { AuthApi(baseUrl = baseUrl) }
}
