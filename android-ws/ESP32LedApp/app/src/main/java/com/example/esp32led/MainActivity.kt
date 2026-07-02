package com.example.esp32led

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * MainActivity — the single screen for the ESP32 LED Controller Android app.
 *
 * Flow:
 *  1. User signs in with Google → get ID token
 *  2. Send token to backend [SERVER_URL]/api/verify
 *  3. If authorized → enable the toggle button
 *  4. On toggle click → POST on/off command to [SERVER_URL]/api/led
 *  5. Update LED indicator & status text
 *
 * Dependencies (from app/build.gradle):
 *  - OkHttp       — HTTP client for REST API calls
 *  - Gson         — JSON parsing
 *  - play-services-auth — Google Sign-In
 *  - Kotlinx Coroutines — async on IO dispatcher
 */
class MainActivity : AppCompatActivity() {

    // ── Logging ────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "ESP32Led"
    }

    // ── Server configuration ───────────────────────────────────────────────────
    // Override in app/build.gradle → buildConfigField "String", "SERVER_URL", "..."
    private val serverUrl: String
        get() = BuildConfig.SERVER_URL

    // Google OAuth 2.0 Web Client ID (from Google Cloud Console).
    // Same ID used by the web frontend at public/index.html.
    // For Android, pass this as serverClientId to GoogleSignInOptions.
    // CHANGE THIS to your own Web Client ID from:
    //   https://console.cloud.google.com/apis/credentials
    private val googleWebClientId: String
        get() = BuildConfig.GOOGLE_CLIENT_ID

    // ── UI References (view binding) ───────────────────────────────────────────
    private lateinit var signInButton: SignInButton
    private lateinit var toggleBtn: MaterialButton
    private lateinit var ledIndicator: View
    private lateinit var statusText: TextView
    private lateinit var userEmailText: TextView
    private lateinit var messageText: TextView

    // ── State ──────────────────────────────────────────────────────────────────
    private var isLedOn = false              // tracks the UI state
    private var isAuthorized = false          // true after /api/verify succeeds
    private var userEmail: String? = null     // email from verified token
    private var googleSignInClient: GoogleSignInClient? = null

    // ── HTTP Client ────────────────────────────────────────────────────────────
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── Activity Result Launcher for Google Sign-In ────────────────────────────
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            Log.d(TAG, "Google Sign-In successful for: ${account.email}")
            // Obtain the ID token to send to our backend
            val idToken = account.idToken
            if (idToken != null) {
                userEmail = account.email
                verifyWithBackend(idToken)
            } else {
                showError("Failed to get ID token from Google Sign-In")
            }
        } catch (e: ApiException) {
            Log.w(TAG, "Google Sign-In failed: code=${e.statusCode}", e)
            showError("Sign-In failed: ${e.localizedMessage}")
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI elements
        signInButton = findViewById(R.id.signInButton)
        toggleBtn = findViewById(R.id.toggleBtn)
        ledIndicator = findViewById(R.id.ledIndicator)
        statusText = findViewById(R.id.statusText)
        userEmailText = findViewById(R.id.userEmailText)
        messageText = findViewById(R.id.messageText)

        // Configure Google Sign-In
        setupGoogleSignIn()

        // Wire up the toggle button
        toggleBtn.setOnClickListener { onToggleClicked() }

        updateUiForLedState()
    }

    // ── Google Sign-In Setup ───────────────────────────────────────────────────

    private fun setupGoogleSignIn() {
        // Configure sign-in to request the ID token (OpenID Connect).
        // The serverClientId must be a WEB client ID from the Google Cloud Console.
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(googleWebClientId)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Check if the user is already signed in from a previous session
        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastAccount != null && lastAccount.idToken != null) {
            Log.d(TAG, "Already signed in as: ${lastAccount.email}")
            userEmail = lastAccount.email
            // Silently re-verify with the backend (token may have expired)
            verifyWithBackend(lastAccount.idToken!!)
        } else {
            // Show the sign-in button; clicking it triggers the sign-in flow
            signInButton.setOnClickListener { signIn() }
        }
    }

    private fun signIn() {
        val signInIntent = googleSignInClient?.signInIntent
        if (signInIntent != null) {
            signInLauncher.launch(signInIntent)
        } else {
            showError("Google Sign-In not configured")
        }
    }

    // ── Backend API Calls ──────────────────────────────────────────────────────

    /**
     * POST the Google ID token to [serverUrl]/api/verify.
     * The backend checks the token and verifies the user is in the allowed_users sheet.
     */
    private fun verifyWithBackend(idToken: String) {
        showMessage("Verifying identity…", isError = false)
        lifecycleScope.launch {
            val result = performVerify(idToken)
            withContext(Dispatchers.Main) {
                handleVerifyResult(result)
            }
        }
    }

    /**
     * Performs the HTTP POST to /api/verify on a background thread.
     */
    private suspend fun performVerify(idToken: String): VerifyResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/verify")
                    .header("Authorization", "Bearer $idToken")
                    .post("".toRequestBody(jsonMediaType)) // empty body; token is in header
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val bodyString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    // Expected: {"allowed": true, "email": "user@example.com"}
                    val json = gson.fromJson(bodyString, Map::class.java)
                    val allowed = json["allowed"] as? Boolean ?: false
                    val email = json["email"] as? String ?: userEmail
                    Log.d(TAG, "Verify response: allowed=$allowed, email=$email")
                    VerifyResult.Success(allowed, email)
                } else {
                    Log.w(TAG, "Verify failed: HTTP ${response.code} — $bodyString")
                    VerifyResult.Error("Server error: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during verify", e)
                VerifyResult.Error("Network error: ${e.localizedMessage}")
            }
        }
    }

    private fun handleVerifyResult(result: VerifyResult) {
        when (result) {
            is VerifyResult.Success -> {
                if (result.allowed) {
                    isAuthorized = true
                    userEmail = result.email ?: userEmail
                    hideMessage()
                    onAuthorizationGranted()
                } else {
                    isAuthorized = false
                    showError(getString(R.string.status_unauthorized))
                    signOut()
                }
            }
            is VerifyResult.Error -> {
                isAuthorized = false
                showError(result.message)
            }
        }
    }

    /**
     * Called after the user is successfully authorized.
     * Hides the sign-in button, shows the email, enables the toggle.
     */
    private fun onAuthorizationGranted() {
        signInButton.visibility = View.GONE
        userEmailText.text = userEmail ?: "Verified"
        userEmailText.visibility = View.VISIBLE
        toggleBtn.isEnabled = true
        showMessage("Ready to control the LED", isError = false)
        Log.d(TAG, "Authorization granted for $userEmail")
    }

    // ── LED Toggle ─────────────────────────────────────────────────────────────

    private fun onToggleClicked() {
        if (!isAuthorized) {
            showError("Not authorized — please sign in first")
            return
        }

        val command = if (isLedOn) "off" else "on"
        toggleBtn.isEnabled = false
        showMessage("Sending $command command…", isError = false)

        lifecycleScope.launch {
            val result = performToggleCommand(command)
            withContext(Dispatchers.Main) {
                handleToggleResult(result, command)
            }
        }
    }

    /**
     * POST {"command": "on"|"off"} to [serverUrl]/api/led.
     */
    private suspend fun performToggleCommand(command: String): ToggleResult {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = gson.toJson(mapOf("command" to command))
                val requestBody = jsonBody.toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$serverUrl/api/led")
                    .header("Authorization", "Bearer ${getCurrentIdToken()}")
                    .post(requestBody)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val bodyString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    // Expected: {"status": "ok"}
                    Log.d(TAG, "Toggle response: $bodyString")
                    ToggleResult.Success
                } else {
                    Log.w(TAG, "Toggle failed: HTTP ${response.code} — $bodyString")
                    when (response.code) {
                        503 -> ToggleResult.NoESP32
                        401 -> ToggleResult.Unauthorized
                        else -> ToggleResult.Error("HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during toggle", e)
                ToggleResult.Error("Network error: ${e.localizedMessage}")
            }
        }
    }

    private fun handleToggleResult(result: ToggleResult, command: String) {
        toggleBtn.isEnabled = true
        when (result) {
            is ToggleResult.Success -> {
                isLedOn = command == "on"
                updateUiForLedState()
                hideMessage()
            }
            is ToggleResult.NoESP32 -> {
                showError(getString(R.string.status_no_esp32))
            }
            is ToggleResult.Unauthorized -> {
                showError("Session expired — signing out")
                signOut()
            }
            is ToggleResult.Error -> {
                showError("${getString(R.string.status_error_prefix)} ${result.message}")
            }
        }
    }

    /**
     * Retrieves the current ID token. If the token has expired, this may trigger
     * a silent refresh via the GoogleSignInClient.silentSignIn() call.
     *
     * For simplicity, this returns whatever GoogleSignIn.getLastSignedInAccount
     * returns. In production, you'd want to handle token refresh asynchronously.
     */
    private fun getCurrentIdToken(): String? {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        return account?.idToken
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun updateUiForLedState() {
        ledIndicator.setBackgroundResource(
            if (isLedOn) R.drawable.circle_led_on else R.drawable.circle_led_off
        )
        statusText.setText(
            if (isLedOn) R.string.status_led_on else R.string.status_led_off
        )
        toggleBtn.text = getString(
            if (isLedOn) R.string.btn_toggle_off else R.string.btn_toggle_on
        )
        // Tint the button background
        toggleBtn.backgroundTintList = getColorStateList(
            if (isLedOn) R.color.btn_on_bg else R.color.btn_off_bg
        )
    }

    private fun showError(message: String) {
        showMessage(message, isError = true)
    }

    private fun showMessage(message: String, isError: Boolean) {
        messageText.text = message
        messageText.setTextColor(
            if (isError) getColor(android.R.color.holo_red_dark)
            else getColor(android.R.color.darker_gray)
        )
        messageText.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        messageText.visibility = View.GONE
    }

    // ── Sign Out ───────────────────────────────────────────────────────────────

    private fun signOut() {
        isAuthorized = false
        isLedOn = false
        toggleBtn.isEnabled = false
        signInButton.visibility = View.VISIBLE
        userEmailText.visibility = View.GONE
        updateUiForLedState()

        googleSignInClient?.signOut()?.addOnCompleteListener {
            Log.d(TAG, "Signed out from Google")
        }
    }

    // ── Result classes for sealed-class pattern matching ───────────────────────

    private sealed class VerifyResult {
        data class Success(val allowed: Boolean, val email: String?) : VerifyResult()
        data class Error(val message: String) : VerifyResult()
    }

    private sealed class ToggleResult {
        object Success : ToggleResult()
        object NoESP32 : ToggleResult()
        object Unauthorized : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }
}
