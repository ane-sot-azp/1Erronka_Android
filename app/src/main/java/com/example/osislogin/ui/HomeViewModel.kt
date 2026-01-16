package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException

data class Mahaia(
    val id: Int,
    val label: String,
    val pertsonaMax: Int,
    val pertsonaMaxRaw: Int,
    val isOccupied: Boolean,
    val erreserbaId: Int?,
    val pertsonaKopurua: Int?
)

data class HomeUiState(
    val tables: List<Mahaia> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val debug: String? = null
)

class HomeViewModel(private val sessionManager: SessionManager) : ViewModel() {

    private val apiBaseUrlLanPrimary = "http://172.16.238.50:5000/api"

    val userEmail = sessionManager.userEmail.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val userName = sessionManager.userName.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    fun loadTables() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, debug = null)
                val result = withContext(Dispatchers.IO) { fetchTablesFromApi() }
                _uiState.value = _uiState.value.copy(isLoading = false, tables = result.tables, debug = result.debug)
            } catch (e: Exception) {
                val isConnectError = e is ConnectException ||
                    e is SocketTimeoutException ||
                    (e.message?.contains("failed to connect", ignoreCase = true) == true)
                val suffix = if (isConnectError) {
                    " (revisa Wi‑Fi/subred, servidor escuchando 0.0.0.0:5000 y firewall)"
                } else {
                    ""
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error al cargar mesas$suffix: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            sessionManager.clearSession()
        }
    }

    private data class TablesFetchResult(val tables: List<Mahaia>, val debug: String)

    private fun fetchTablesFromApi(): TablesFetchResult {
        var lastException: Exception? = null
        var lastDebug = ""

        for (baseUrl in apiBaseUrlCandidates()) {
            val candidateUrls = listOf(
                "$baseUrl/mahaiak",
                "$baseUrl/Mahaiak"
            )

            for (candidateUrl in candidateUrls) {
                try {
                    val url = URL(candidateUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream.bufferedReader().use { it.readText() }

                    if (code !in 200..299) {
                        lastException = IllegalStateException("HTTP $code en $candidateUrl")
                        lastDebug = "url=$candidateUrl code=$code body=${body.take(250)}"
                        continue
                    }

                    val tables = parseTables(body)
                    val sample = tables.take(8).joinToString(separator = ", ") { "${it.label}:${it.pertsonaMaxRaw}->${it.pertsonaMax}" }
                    lastDebug = "url=$candidateUrl code=$code sample=[$sample]"
                    if (tables.isNotEmpty()) return TablesFetchResult(tables = tables, debug = lastDebug)
                } catch (e: Exception) {
                    lastException = IllegalStateException("Fallo conectando a $candidateUrl: ${e.message ?: e.javaClass.simpleName}", e)
                    lastDebug = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        throw lastException ?: IllegalStateException("No se pudo cargar mesas ($lastDebug)")
    }

    private fun parseTables(body: String): List<Mahaia> {
        val root = JSONTokener(body).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                root.optJSONArray("tables")
                    ?: root.optJSONArray("mahaiak")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("result")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }

        val result = ArrayList<Mahaia>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue

            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: (i + 1)
            val pertsonaMaxRaw = when {
                obj.has("pertsona_max") -> obj.optInt("pertsona_max", -1)
                obj.has("pertsonaMax") -> obj.optInt("pertsonaMax", -1)
                obj.has("PertsonaMax") -> obj.optInt("PertsonaMax", -1)
                obj.has("Pertsona_Max") -> obj.optInt("Pertsona_Max", -1)
                else -> -1
            }
            val pertsonaMax = pertsonaMaxRaw.takeIf { it > 0 } ?: 4

            val label = obj.optString(
                "numero",
                obj.optString(
                    "zenbakia",
                    obj.optString(
                        "mahaia",
                        obj.optString(
                            "Mahaia",
                            obj.optString(
                                "id",
                                obj.optString("Id", id.toString())
                            )
                        )
                    )
                )
            ).trim().ifBlank { id.toString() }

            val isOccupied = when {
                obj.has("okupatuta") -> obj.optBoolean("okupatuta")
                obj.has("occupied") -> obj.optBoolean("occupied")
                obj.has("isOccupied") -> obj.optBoolean("isOccupied")
                obj.has("libre") -> !obj.optBoolean("libre")
                obj.has("isFree") -> !obj.optBoolean("isFree")
                else -> false
            }

            val erreserbaIdRaw = when {
                obj.has("erreserbaId") -> obj.optInt("erreserbaId", -1)
                obj.has("erreserba_id") -> obj.optInt("erreserba_id", -1)
                obj.has("ErreserbaId") -> obj.optInt("ErreserbaId", -1)
                else -> -1
            }
            val erreserbaId = erreserbaIdRaw.takeIf { it > 0 }

            val pertsonaKopuruaRaw = when {
                obj.has("pertsonaKopurua") -> obj.optInt("pertsonaKopurua", -1)
                obj.has("pertsona_kopurua") -> obj.optInt("pertsona_kopurua", -1)
                obj.has("PertsonaKopurua") -> obj.optInt("PertsonaKopurua", -1)
                else -> -1
            }
            val pertsonaKopurua = pertsonaKopuruaRaw.takeIf { it > 0 }

            result.add(
                Mahaia(
                    id = id,
                    label = label,
                    pertsonaMax = pertsonaMax,
                    pertsonaMaxRaw = pertsonaMaxRaw,
                    isOccupied = isOccupied,
                    erreserbaId = erreserbaId,
                    pertsonaKopurua = pertsonaKopurua
                )
            )
        }

        return result
    }

    private fun apiBaseUrlCandidates(): List<String> {
        return listOf(apiBaseUrlLanPrimary)
    }
}
