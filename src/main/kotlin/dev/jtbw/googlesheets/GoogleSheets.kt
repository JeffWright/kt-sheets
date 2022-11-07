package dev.jtbw.googlesheets

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.UpdateValuesResponse
import com.google.api.services.sheets.v4.model.ValueRange
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.security.GeneralSecurityException


private object SheetsApi {
    private const val APPLICATION_NAME = "Google Sheets API"
    private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()
    private const val TOKENS_DIRECTORY_PATH = "tokens"

    val service: Sheets by lazy {
        val HTTP_TRANSPORT: NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport()
        Sheets.Builder(
            HTTP_TRANSPORT,
            JSON_FACTORY,
            getCredentials(HTTP_TRANSPORT)
        )
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    /**
     * Global instance of the scopes required by this quickstart.
     * If modifying these scopes, delete your previously saved tokens/ folder.
     */
    private val SCOPES = listOf(SheetsScopes.SPREADSHEETS)
    private const val CREDENTIALS_FILE_PATH = "/sheets_credentials.json"

    /**
     * Creates an authorized Credential object.
     *
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    @Throws(IOException::class)
    private fun getCredentials(HTTP_TRANSPORT: NetHttpTransport): Credential {
        // Load client secrets.
        val `in` = SheetsApi::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH)
            ?: throw FileNotFoundException("Resource not found: $CREDENTIALS_FILE_PATH -- place file under /resources \n" +
                    "get it from https://console.cloud.google.com/apis/credentials \n" +
                    "see also: https://developers.google.com/sheets/api/quickstart/java")

        val clientSecrets: GoogleClientSecrets =
            GoogleClientSecrets.load(
                JSON_FACTORY, InputStreamReader(`in`)
            )

        // Build flow and trigger user authorization request.
        val flow: GoogleAuthorizationCodeFlow =
            GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES
            )
                .setDataStoreFactory(
                    FileDataStoreFactory(
                        File(
                            TOKENS_DIRECTORY_PATH
                        )
                    )
                )
                .setAccessType("offline")
                .build()
        val receiver: LocalServerReceiver = LocalServerReceiver.Builder().setPort(8888).build()
        return AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
    }

    /**
     * Prints the names and majors of students in a sample spreadsheet:
     * https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    @JvmStatic
    fun main(args: Array<String>) {
    }
}

class GoogleSheet(
    val spreadsheetId: String,
    val sheetName: String = "Sheet1",
) {
    fun readRange(
        /** e.g. "A2:E" */
        range: String
    ): List<List<String>> {
        // Build a new authorized API client service.
        val sheetAndRange = "$sheetName!$range"
        val service = SheetsApi.service
        val response: ValueRange = service.spreadsheets().values()
            .get(spreadsheetId, sheetAndRange)
            .execute()
        return response.getValues()
            ?.map {
                it.map { it.toString() }
            }
            ?: emptyList()
    }

    fun writeRange(
        /** e.g. "A2:E" */
        range: String,
        values: List<List<Any?>>,
        parseInput: Boolean = false
    ) {
        val valueInputOption: String = if (parseInput) "USER_ENTERED" else "RAW"
        var result: UpdateValuesResponse? = null
        try {
            // Updates the values in the specified range.
            val body = ValueRange()
                .setValues(values)
            result = SheetsApi.service.spreadsheets().values().update(spreadsheetId, range, body)
                .setValueInputOption(valueInputOption)
                .execute()
            println("${result.updatedCells} cells updated.")
        } catch (e: GoogleJsonResponseException) {
            val error: GoogleJsonError = e.details
            if (error.code == 404) {
                println("Spreadsheet not found with id '$spreadsheetId'.")
            }

            throw e
        }
    }

    fun <T> readData(range: String, vararg selectedColumns: String, block: (List<String?>) -> T) : List<T> {
        val data = readRange(range)
        val columnNames = data.first()
            .map { it.lowercase() }
        val columnIdxs = selectedColumns
            .map { selected ->
                columnNames.indexOf(selected.lowercase())
                    .takeIf { it >=0 }
                    ?: throw IllegalArgumentException("No column named $selected in $sheetName.  Columns were: $columnNames")
            }

        return data
            .drop(1) // headers
            .map { rowAsList ->
                block(
                    columnIdxs.map { idx ->
                        rowAsList.getOrNull(idx)// ?: throw IllegalArgumentException("No data for ")
                    }
                )
            }
    }
}