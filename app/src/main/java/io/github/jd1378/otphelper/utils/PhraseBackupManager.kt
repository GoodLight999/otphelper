package io.github.jd1378.otphelper.utils

import android.content.Context
import android.net.Uri
import io.github.jd1378.otphelper.UserSettings
import java.io.IOException
import java.io.Reader
import org.json.JSONArray
import org.json.JSONObject

enum class PhraseListKind(val wireName: String) {
  SENSITIVE("sensitive_phrases"),
  IGNORED("ignored_phrases"),
  CLEANUP("cleanup_phrases");

  companion object {
    fun fromWireName(value: String?): PhraseListKind? = entries.firstOrNull { it.wireName == value }
  }
}

data class PhraseLists(
    val sensitive: List<String>,
    val ignored: List<String>,
    val cleanup: List<String>,
)

object PhraseBackupManager {
  private const val SCHEMA = "otphelper.phrases"
  private const val VERSION = 1
  internal const val MAX_FILE_CHARS = 2_000_000
  private const val MAX_PHRASES_PER_LIST = 20_000
  private const val MAX_PHRASE_CHARS = 20_000

  fun readText(context: Context, uri: Uri): String =
      context.contentResolver.openInputStream(uri)?.reader(Charsets.UTF_8)?.use(::readBounded)
          ?: throw IOException("Unable to open import file")

  internal fun readBounded(reader: Reader): String {
    val result = StringBuilder(minOf(MAX_FILE_CHARS, 16_384))
    val buffer = CharArray(8_192)
    while (true) {
      val count = reader.read(buffer)
      if (count < 0) break
      require(result.length + count <= MAX_FILE_CHARS) { "The import file is too large" }
      result.append(buffer, 0, count)
    }
    return result.toString()
  }

  fun writeText(context: Context, uri: Uri, text: String) {
    require(text.length <= MAX_FILE_CHARS) { "The export file is too large" }
    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
      it.write(text)
    } ?: throw IOException("Unable to open export file")
  }

  fun encodeSingle(kind: PhraseListKind, phrases: List<String>): String =
      requireOutputSize(
          JSONObject()
              .put("schema", SCHEMA)
              .put("version", VERSION)
              .put("kind", kind.wireName)
              .put("phrases", JSONArray(normalize(phrases)))
              .toString(2)
      )

  fun encodeAll(settings: UserSettings): String =
      requireOutputSize(
          JSONObject()
              .put("schema", SCHEMA)
              .put("version", VERSION)
              .put(
                  "lists",
                  JSONObject()
                      .put(
                          PhraseListKind.SENSITIVE.wireName,
                          JSONArray(normalize(settings.sensitivePhrasesList)),
                      )
                      .put(
                          PhraseListKind.IGNORED.wireName,
                          JSONArray(normalize(settings.ignoredPhrasesList)),
                      )
                      .put(
                          PhraseListKind.CLEANUP.wireName,
                          JSONArray(normalize(settings.cleanupPhrasesList)),
                      ),
              )
              .toString(2)
      )

  /**
   * Imports the native JSON format, a JSON string array, or a UTF-8 text file with one phrase per
   * line. The relaxed formats make it possible to recover lists from older manual backups.
   */
  fun decodeSingle(text: String, expectedKind: PhraseListKind): List<String> {
    require(text.length <= MAX_FILE_CHARS) { "The import file is too large" }
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "The import file is empty" }

    if (trimmed.startsWith("[")) return normalize(jsonArrayToList(JSONArray(trimmed)))
    if (!trimmed.startsWith("{")) return normalize(trimmed.lineSequence().toList())

    val root = JSONObject(trimmed)
    validateOptionalHeader(root)
    root.optJSONObject("lists")?.let { lists ->
      return normalize(jsonArrayToList(requireArray(lists, expectedKind.wireName)))
    }

    val actualKind =
        if (root.has("kind")) PhraseListKind.fromWireName(root.optString("kind")) else null
    require(actualKind == null || actualKind == expectedKind) {
      "This backup contains ${actualKind?.wireName ?: "an unknown list"}, not ${expectedKind.wireName}"
    }
    return normalize(jsonArrayToList(requireArray(root, "phrases")))
  }

  fun decodeAll(text: String): PhraseLists {
    require(text.length <= MAX_FILE_CHARS) { "The import file is too large" }
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "The import file is empty" }
    val root = JSONObject(trimmed)
    validateRequiredHeader(root)
    val lists = root.optJSONObject("lists") ?: throw IllegalArgumentException("Not a complete backup")
    return PhraseLists(
        sensitive = normalize(jsonArrayToList(requireArray(lists, PhraseListKind.SENSITIVE.wireName))),
        ignored = normalize(jsonArrayToList(requireArray(lists, PhraseListKind.IGNORED.wireName))),
        cleanup = normalize(jsonArrayToList(requireArray(lists, PhraseListKind.CLEANUP.wireName))),
    )
  }

  private fun validateOptionalHeader(root: JSONObject) {
    if (root.has("schema")) {
      require(root.optString("schema") == SCHEMA) { "Unsupported backup schema" }
    }
    if (root.has("version")) {
      require(root.optInt("version", -1) == VERSION) { "Unsupported backup version" }
    }
  }

  private fun validateRequiredHeader(root: JSONObject) {
    require(root.optString("schema") == SCHEMA) { "Not an OTP Helper phrase backup" }
    require(root.optInt("version", -1) == VERSION) { "Unsupported backup version" }
  }

  private fun requireArray(objectValue: JSONObject, key: String): JSONArray =
      objectValue.optJSONArray(key)
          ?: throw IllegalArgumentException("Backup field '$key' is missing or is not a string array")

  private fun jsonArrayToList(array: JSONArray): List<String> =
      buildList(array.length()) {
        for (index in 0 until array.length()) {
          val value = array.get(index)
          require(value is String) { "Every phrase must be a string" }
          add(value)
        }
      }

  private fun normalize(values: Iterable<String>): List<String> {
    val result = LinkedHashSet<String>()
    for (raw in values) {
      val value = raw.trimEnd('\r', '\n')
      if (value.isBlank()) continue
      require(value.length <= MAX_PHRASE_CHARS) { "A phrase is too long" }
      result += value
      require(result.size <= MAX_PHRASES_PER_LIST) { "The backup contains too many phrases" }
    }
    return result.toList()
  }

  private fun requireOutputSize(text: String): String {
    require(text.length <= MAX_FILE_CHARS) { "The export file is too large" }
    return text
  }
}
