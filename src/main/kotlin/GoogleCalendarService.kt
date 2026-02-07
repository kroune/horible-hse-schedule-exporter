import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import java.io.FileInputStream
import java.io.InputStreamReader

object GoogleCalendarService {
    private const val APPLICATION_NAME = "Timetable Exporter"
    private val JSON_FACTORY = GsonFactory.getDefaultInstance()
    private val SCOPES = listOf(CalendarScopes.CALENDAR_EVENTS)
    private const val TOKENS_DIRECTORY_PATH = "tokens"
    private const val CREDENTIALS_FILE_PATH = "credentials.json"

    private fun getCredentials(HTTP_TRANSPORT: NetHttpTransport): Credential {
        val inputStream = FileInputStream(CREDENTIALS_FILE_PATH)
        val clientSecrets = com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets.load(
            JSON_FACTORY, InputStreamReader(inputStream)
        )

        val flow = com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow.Builder(
            HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES
        )
            .setDataStoreFactory(
                com.google.api.client.util.store.FileDataStoreFactory(
                    java.io.File(TOKENS_DIRECTORY_PATH)
                )
            )
            .setAccessType("offline")
            .build()

        val receiver = com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver.Builder()
            .setPort(8888)
            .build()

        return com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp(flow, receiver)
            .authorize("user")
    }

    fun createCalendarService(): Calendar {
        val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()
        val credential = getCredentials(HTTP_TRANSPORT)

        return Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }
}