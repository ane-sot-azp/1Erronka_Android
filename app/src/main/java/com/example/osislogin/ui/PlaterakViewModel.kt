package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

data class Platera(
    val id: Int,
    val kategoriakId: Int,
    val name: String,
    val price: Double,
    val stock: Int
)

data class KomandaItem(
    val id: Int,
    val platerakId: Int,
    val fakturakId: Int,
    val kopurua: Int,
    val oharrak: String?
)

data class PlaterakUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val kategoriId: Int = 0,
    val fakturaId: Int = 0,
    val platerak: List<Platera> = emptyList(),
    val komandakByPlateraId: Map<Int, KomandaItem> = emptyMap()
)

class PlaterakViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.2.101:5000/api"
    private val apiBaseCandidates = listOf(apiBaseUrlLanPrimary)

    private val _uiState = MutableStateFlow(PlaterakUiState())
    val uiState: StateFlow<PlaterakUiState> = _uiState
    private var autoRefreshJob: Job? = null

    private fun readNullableString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key)) continue
            if (obj.isNull(key)) return null
            val value = obj.optString(key, "")
            val cleaned = value.trim()
            if (cleaned.isEmpty()) return null
            if (cleaned.equals("null", ignoreCase = true)) return null
            return cleaned
        }
        return null
    }

    fun load(fakturaId: Int, kategoriId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, fakturaId = fakturaId, kategoriId = kategoriId)
                val platerak = withContext(Dispatchers.IO) { fetchPlaterak(kategoriId) }
                val komandak = withContext(Dispatchers.IO) { fetchKomandak(fakturaId) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    platerak = platerak,
                    komandakByPlateraId = komandak.associateBy { it.platerakId }
                )
                startAutoRefresh(fakturaId = fakturaId, kategoriId = kategoriId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun startAutoRefresh(fakturaId: Int, kategoriId: Int) {
        autoRefreshJob?.cancel()
        autoRefreshJob =
            viewModelScope.launch {
                while (isActive) {
                    try {
                        val (platerak, komandak) =
                            withContext(Dispatchers.IO) {
                                fetchPlaterak(kategoriId) to fetchKomandak(fakturaId)
                            }

                        _uiState.value =
                            _uiState.value.copy(
                                platerak = platerak,
                                komandakByPlateraId = komandak.associateBy { it.platerakId },
                                error = null
                            )
                    } catch (_: Exception) {
                    }
                    delay(2000)
                }
            }
    }

    fun changeQuantity(plateraId: Int, delta: Int) {
        val fakturaId = _uiState.value.fakturaId
        if (fakturaId <= 0) return

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { upsertDelta(fakturaId, plateraId, delta) }
                val current = _uiState.value.komandakByPlateraId.toMutableMap()
                if (result.deleted) {
                    current.remove(plateraId)
                } else {
                    val komanda = result.komanda
                    current[plateraId] = komanda
                }
                val updatedPlaterak =
                    result.stock?.let { stock ->
                        _uiState.value.platerak.map { p ->
                            if (p.id == plateraId) p.copy(stock = stock) else p
                        }
                    } ?: _uiState.value.platerak

                _uiState.value =
                    _uiState.value.copy(
                        komandakByPlateraId = current,
                        platerak = updatedPlaterak,
                        error = null
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun updateNote(plateraId: Int, note: String) {
        val komanda = _uiState.value.komandakByPlateraId[plateraId] ?: return
        viewModelScope.launch {
            try {
                val cleanedNote = note.trim().takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }.orEmpty()
                withContext(Dispatchers.IO) { putOharrak(komanda = komanda, oharrak = cleanedNote) }

                val current = _uiState.value.komandakByPlateraId.toMutableMap()
                current[plateraId] = komanda.copy(oharrak = cleanedNote.ifBlank { null })
                _uiState.value = _uiState.value.copy(komandakByPlateraId = current, error = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fetchPlaterak(kategoriId: Int): List<Platera> {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Platerak/kategoria/$kategoriId",
                    "$base/platerak/kategoria/$kategoriId",
                    "$base/platerak?kategoriakId=$kategoriId",
                    "$base/Platerak?kategoriakId=$kategoriId"
                )
            }

        var okBody: String? = null
        for (candidateUrl in candidates) {
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
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            okBody = body
            break
        }

        val finalBody = okBody ?: throw IllegalStateException("No se pudo cargar /platerak ($lastError)")

        val root = JSONTokener(finalBody).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("platerak") ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<Platera>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            val kategoriakId = obj.optInt("kategoriakId", obj.optInt("KategoriakId", -1))
            val name = obj.optString("izena", obj.optString("Izena", ""))
            val price = obj.optDouble("prezioa", obj.optDouble("Prezioa", 0.0))
            val stock = obj.optInt("stock", obj.optInt("Stock", 0))
            if (id > 0) {
                result.add(Platera(id = id, kategoriakId = kategoriakId, name = name, price = price, stock = stock))
            }
        }

        return result
    }

    private fun fetchKomandak(fakturaId: Int): List<KomandaItem> {
        var lastError: String? = null
        var sawNotFound = false
        var sawOtherError = false
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/komandak/faktura/$fakturaId/items",
                    "$base/Komandak/faktura/$fakturaId/items",
                    "$base/Komandak/faktura/$fakturaId",
                    "$base/komandak/faktura/$fakturaId"
                )
            }

        var okBody: String? = null
        for (candidateUrl in candidates) {
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
            if (code == 404) {
                sawNotFound = true
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            if (code !in 200..299) {
                sawOtherError = true
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            okBody = body
            break
        }

        val finalBody = okBody ?: run {
            if (sawNotFound && !sawOtherError) return emptyList()
            throw IllegalStateException("No se pudo cargar /komandak/faktura ($lastError)")
        }

        val root = JSONTokener(finalBody).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("komandak") ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<KomandaItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            val platerakId =
                obj.optInt("platerakId", obj.optInt("PlaterakId", -1)).takeIf { it > 0 }
                    ?: run {
                        val pObj = obj.optJSONObject("platerak") ?: obj.optJSONObject("Platerak")
                        pObj?.optInt("id", pObj.optInt("Id", -1))
                    }
                    ?: -1
            val fakturakId =
                obj.optInt("fakturakId", obj.optInt("FakturakId", -1)).takeIf { it > 0 }
                    ?: run {
                        val fObj = obj.optJSONObject("faktura") ?: obj.optJSONObject("Faktura")
                        fObj?.optInt("id", fObj.optInt("Id", -1))
                    }
                    ?: -1
            val kopurua = obj.optInt("kopurua", obj.optInt("Kopurua", 0))
            val oharrak = readNullableString(obj, "oharrak", "Oharrak")
            if (id > 0 && platerakId > 0) {
                result.add(
                    KomandaItem(
                        id = id,
                        platerakId = platerakId,
                        fakturakId = fakturakId,
                        kopurua = kopurua,
                        oharrak = oharrak
                    )
                )
            }
        }

        return result
    }

    private data class DeltaResult(val deleted: Boolean, val komanda: KomandaItem, val stock: Int?)

    private fun upsertDelta(fakturaId: Int, platerakId: Int, delta: Int): DeltaResult {
        val current = fetchKomandak(fakturaId)
        val existing = current.firstOrNull { it.platerakId == platerakId }
        val existingQty = existing?.kopurua ?: 0
        val newQty = existingQty + delta

        if (newQty <= 0) {
            if (existing != null) {
                deleteKomanda(existing.id)
            }
            val stock = fetchPlateraStock(platerakId)
            return DeltaResult(
                deleted = true,
                komanda = existing ?: KomandaItem(id = -1, platerakId = platerakId, fakturakId = fakturaId, kopurua = 0, oharrak = null),
                stock = stock
            )
        }

        if (existing != null) {
            updateKomandaQuantity(komandaId = existing.id, kopurua = newQty)
        } else {
            postKomanda(fakturaId = fakturaId, platerakId = platerakId, kopurua = newQty)
        }

        val updatedList = fetchKomandak(fakturaId)
        val found = updatedList.firstOrNull { it.platerakId == platerakId }
            ?: throw IllegalStateException("No se pudo refrescar la comanda tras actualizar")
        val stock = fetchPlateraStock(platerakId)

        return DeltaResult(
            deleted = false,
            komanda = found,
            stock = stock
        )
    }

    private fun deleteKomanda(komandaId: Int) {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Komandak/$komandaId",
                    "$base/komandak/$komandaId"
                )
            }

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("No se pudo borrar la comanda ($lastError)")
    }

    private fun postKomanda(fakturaId: Int, platerakId: Int, kopurua: Int) {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Komandak",
                    "$base/komandak"
                )
            }

        val payload =
            JSONObject()
                .put("fakturakId", fakturaId)
                .put("platerakId", platerakId)
                .put("kopurua", kopurua)
                .toString()

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("No se pudo crear la comanda ($lastError)")
    }

    private fun updateKomandaQuantity(komandaId: Int, kopurua: Int) {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/komandak/$komandaId/quantity",
                    "$base/Komandak/$komandaId/quantity"
                )
            }

        val payload = JSONObject().put("kopurua", kopurua).toString()

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("No se pudo actualizar la cantidad ($lastError)")
    }

    private fun fetchPlateraStock(platerakId: Int): Int? {
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Platerak/$platerakId",
                    "$base/platerak/$platerakId"
                )
            }

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) continue
            val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()
            val obj = parsed as? JSONObject ?: return null
            return obj.optInt("stock", obj.optInt("Stock", Int.MIN_VALUE)).takeIf { it != Int.MIN_VALUE }
        }
        return null
    }

    private fun putOharrak(komanda: KomandaItem, oharrak: String) {
        var lastError: String? = null
        val legacyCandidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/komandak/${komanda.id}/oharrak",
                    "$base/Komandak/${komanda.id}/oharrak"
                )
            }

        val legacyPayload =
            JSONObject()
                .put("oharrak", oharrak.trim().takeUnless { it.isEmpty() } ?: JSONObject.NULL)
                .toString()
        for (candidateUrl in legacyCandidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            conn.outputStream.use { it.write(legacyPayload.toByteArray()) }

            val code = conn.responseCode
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("No se pudo guardar la nota ($lastError)")
    }
}
