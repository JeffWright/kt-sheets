package dev.jtbw.googlesheets

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ClearValuesRequest
import com.google.api.services.sheets.v4.model.ClearValuesResponse
import com.google.api.services.sheets.v4.model.UpdateValuesResponse
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import java.io.File

class GoogleSheetsService(private val service: Sheets) {
  companion object {
    private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()

    /**
     * @param credentialsJsonFile: To create a service account and get keys for it, go to:
     * https://console.cloud.google.com/iam-admin/serviceaccounts
     */
    fun create(
      applicationName: String,
      credentialsJsonFile: File,
    ): GoogleSheetsService {
      require(credentialsJsonFile.exists()) {
        "File not found: ${credentialsJsonFile.absolutePath}"
      }

      val httpTransport: NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport()
      val googleCredentials: GoogleCredentials =
        GoogleCredentials.fromStream(credentialsJsonFile.inputStream())

      val service: Sheets =
        Sheets.Builder(httpTransport, JSON_FACTORY, HttpCredentialsAdapter(googleCredentials))
          .setApplicationName(applicationName)
          .build()

      return GoogleSheetsService(service)
    }
  }

  fun getSheet(
    spreadsheetId: String,
    sheetName: String = "Sheet1",
  ): GoogleSheet {
    return GoogleSheet(spreadsheetId, sheetName, service)
  }
}

class GoogleSheet
internal constructor(
  val spreadsheetId: String,
  val sheetName: String,
  private val service: Sheets
) {
  companion object {
    const val FULL_SHEET = "A1:ZZ"
  }

  /** @param range e.g. "A2:E" */
  fun readRange(range: String = FULL_SHEET): List<List<String>> {
    // Build a new authorized API client service.
    val sheetAndRange = "$sheetName!$range"
    val response: ValueRange =
      service.spreadsheets().values().get(spreadsheetId, sheetAndRange).execute()
    return response.getValues()?.map { it.map { it.toString() } } ?: emptyList()
  }

  /** Assumes first row is field names, will pass each [createRow] a map of fieldName -> contents */
  fun <T> readRange(range: String = FULL_SHEET, createRow: (Map<String, String>) -> T): List<T> {
    val data = readRange(range)
    val fields = data.first()
    val rows = data.drop(1)

    return rows.map { row ->
      val paddedRow = buildList {
        addAll(row)
        while (size < fields.size) {
          add("")
        }
      }
      val zipped = fields.zip(paddedRow).toMap()
      createRow(zipped)
    }
  }

  /**
   * @param range e.g. "A2:E"
   * @param parseInput true if you want "=1+A2" to turn into a formula, false if you want it to be a
   * string
   */
  fun writeRange(
    values: List<List<Any?>>,
    range: String = FULL_SHEET,
    parseInput: Boolean = false
  ) {
    val sheetAndRange = "$sheetName!$range"
    val valueInputOption: String = if (parseInput) "USER_ENTERED" else "RAW"
    var result: UpdateValuesResponse? = null
    try {
      // Updates the values in the specified range.
      val body = ValueRange().setValues(values)
      result =
        service
          .spreadsheets()
          .values()
          .update(spreadsheetId, sheetAndRange, body)
          .setValueInputOption(valueInputOption)
          .execute()
      println("${result.updatedCells ?: 0} cells updated.")
    } catch (e: GoogleJsonResponseException) {
      val error: GoogleJsonError = e.details
      if (error.code == 404) {
        println("Spreadsheet not found with id '$spreadsheetId'.")
      }

      throw e
    }
  }

  /**
   * Assumes first row is field names, will write each value's entries under the corresponding
   * columns.  Fields are _not_ case-sensitive
   * @param appendUnknownFields should fields present in the data but not in the Sheet be added to
   * the Sheet?
   */
  fun writeRange(values: List<Map<String, Any?>>, appendUnknownFields: Boolean = true) {
    val fields = (readRange("A1:ZZ1").firstOrNull() ?: emptyList())
      .map { Field(it) }
    // println("DBG: fields = $fields")
    val unknownFields = buildSet {
      values.forEach { addAll(it.keys.map { Field(it) }) }
      fields.forEach { remove(it) }
    }
    // println("DBG: unknown = $unknownFields")

    val fieldsToWrite = if(appendUnknownFields) fields + unknownFields else fields
    val processedValues =
      values.map { value ->
        val fieldMap = value.mapKeys { (k, _) -> Field(k) }
        fieldsToWrite.map { field ->
          // null will be skipped by Google Sheets API, we want blank instead
          fieldMap[field] ?: ""
        }
      }

    clearRange("A2:ZZ")
    writeRange(
      values = processedValues,
      range = "A2:ZZ",
    )

    if(appendUnknownFields) {
      val col = columnIndexToLetter(fields.size + 1)
      writeRange(listOf(unknownFields.map { it.name }), range = "${col}1:ZZ1")
    }
  }

  fun clearRange(
    range: String = FULL_SHEET,
  ) {
    val sheetAndRange = "$sheetName!$range"
    var result: ClearValuesResponse? = null
    try {
      // Updates the values in the specified range.
      // val body = ValueRange()
      //    .setValues(values)
      val request = ClearValuesRequest()
      result =
        service
          .spreadsheets()
          .values()
          .clear(spreadsheetId, sheetAndRange, request)
          // .update(spreadsheetId, sheetAndRange, body)
          // .setValueInputOption(valueInputOption)
          .execute()
      println("${sheetAndRange} cleared.")
    } catch (e: GoogleJsonResponseException) {
      val error: GoogleJsonError = e.details
      if (error.code == 404) {
        println("Spreadsheet not found with id '$spreadsheetId'.")
      }

      throw e
    }
  }

  fun <T> readData(
    range: String = FULL_SHEET,
    vararg selectedColumns: String,
    block: (List<String?>) -> T
  ): List<T> {
    val sheetAndRange = "$sheetName!$range"
    val data = readRange(sheetAndRange)
    val columnNames = data.first().map { it.lowercase() }
    val columnIdxs =
      selectedColumns.map { selected ->
        columnNames.indexOf(selected.lowercase()).takeIf { it >= 0 }
          ?: throw IllegalArgumentException(
            "No column named $selected in $sheetName.  Columns were: $columnNames"
          )
      }

    return data
      .drop(1) // headers
      .map { rowAsList ->
        block(
          columnIdxs.map { idx ->
            rowAsList.getOrNull(idx) // ?: throw IllegalArgumentException("No data for ")
          }
        )
      }
  }
}

private data class Field(val name: String) {

  override fun toString(): String = "<$name>"

  override fun equals(other: Any?): Boolean {
    val otherField = other as? Field ?: return false
    return name.equals(otherField.name, true)
  }

  override fun hashCode(): Int {
    return name.lowercase().hashCode()
  }
}

/* 1 -> A */
// TODO: only supports through Z
private fun columnIndexToLetter(idx: Int): String {
  return ('A'..'Z').drop(idx-1).first().toString()
}

