package com.example.osis_camareros.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osis_camareros.data.AppDatabase
import com.example.osis_camareros.util.HashingUtil
import com.example.osis_camareros.util.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

class LoginViewModel(
    private val database: AppDatabase,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    /**
     * LOGIN LOCAL (Room) – lo que ya tenías.
     */
    fun loginLocal() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val user = database.userDao().getUserByEmail(_uiState.value.email)

                if (user != null) {
                    val hashingUtil = HashingUtil()
                    if (hashingUtil.verifyPassword(_uiState.value.password, user.password)) {
                        sessionManager.saveUserSession(user.email, user.fullName)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isSuccess = true
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Credenciales inválidas"
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Error desconocido"
                )
            }
        }
    }

    /**
     * LOGIN POR API C# (HTTP POST JSON).
     * Cambia la URL según uses emulador o móvil físico.
     */
    fun loginWithApi() {
        val erabiltzailea = _uiState.value.email
        val pasahitza = _uiState.value.password

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = URL("http://192.168.1.144:5000/api/login") // ajusta IP / 10.0.2.2
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                    val jsonBody =
                        """{ "erabiltzailea": "$erabiltzailea", "pasahitza": "$pasahitza" }"""

                    conn.outputStream.use { os ->
                        os.write(jsonBody.toByteArray(Charsets.UTF_8))
                    }

                    val code = conn.responseCode

                    // Igual que en Java: si 2xx -> inputStream; si no, errorStream
                    val stream = if (code in 200..299) {
                        conn.inputStream
                    } else {
                        conn.errorStream
                    }

                    val body = stream.bufferedReader().use { it.readText() }

                    // Interpretar como en LoginService.login()
                    val status = when {
                        code == 200 -> "OK"
                        body.contains("Erabiltzaile edo pasahitz okerra") -> "BAD_CREDENTIALS"
                        body.contains("Ez duzu baimenik sartzeko") -> "NO_PERMISSION"
                        else -> "ERROR"
                    }

                    status
                }

                when (result) {
                    "OK" -> {
                        // Aquí podrías parsear datos del usuario si tu API los devuelve
                        sessionManager.saveUserSession(erabiltzailea, erabiltzailea)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isSuccess = true
                        )
                    }
                    "BAD_CREDENTIALS" -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Erabiltzaile edo pasahitz okerra"
                        )
                    }
                    "NO_PERMISSION" -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Ez duzu baimenik sartzeko"
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Error de login"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Error de red"
                )
            }
        }
    }


    fun resetSuccess() {
        _uiState.value = _uiState.value.copy(isSuccess = false)
    }
}
