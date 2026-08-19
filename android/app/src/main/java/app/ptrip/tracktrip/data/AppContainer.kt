package app.ptrip.tracktrip.data

import android.content.Context
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.location.SharingController

/**
 * The app's shared, long-lived objects, built once and handed to whatever
 * needs them.
 *
 * **One instance per process**, obtained through [from]. That is not tidiness:
 * [ApiClient] serialises token refreshes through a mutex, and the backend
 * rotates the refresh token on every use and treats a re-presented one as
 * theft — by revoking every token the user has. Two clients means two mutexes,
 * which means a 401 in the location service and a 401 on screen can refresh at
 * once and sign the rider out. The service and the UI are separate entry
 * points into the same process, so they must meet here.
 */
class AppContainer private constructor(context: Context) {

    private val appContext = context.applicationContext

    val baseUrl: String = appContext.getString(R.string.api_base_url)

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    val apiClient: ApiClient by lazy { ApiClient(baseUrl = baseUrl, tokenStore = tokenStore) }

    val tripApi: TripApi by lazy { TripApi(apiClient) }

    val meApi: MeApi by lazy { MeApi(apiClient) }

    /**
     * The live position feed. One per process, like the API client: it holds
     * an OkHttp client configured for long-lived connections, and a second
     * copy would mean a second connection pool for no gain.
     */
    val positionSocket: PositionSocket by lazy {
        PositionSocket(baseUrl = baseUrl, tokenStore = tokenStore)
    }

    val authApi: AuthApi by lazy { AuthApi(baseUrl = baseUrl) }

    val settings: AppSettings by lazy { AppSettings(appContext) }

    val sharingController: SharingController by lazy { SharingController(appContext, tripApi) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun from(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
