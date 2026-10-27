package com.example.blinktemp

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows Blink's real sign-in page (email, password, PIN) in a WebView and catches the
 * immedia-blink:// redirect that carries the authorization code. The app never sees your password.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var verifier: String
    private lateinit var authUrl: String
    private lateinit var status: TextView
    private var exchanging = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        status = findViewById(R.id.login_status)

        verifier = Pkce.verifier()
        authUrl = BlinkApi.authorizeUrl(Prefs(this).hardwareId, Pkce.challenge(verifier))

        CookieManager.getInstance().removeAllCookies(null)
        val web = findViewById<WebView>(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = BlinkApi.WEB_UA
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                intercept(request.url)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) intercept(request.url)
            }
        }

        // Fallback: if the page stalls after the PIN, re-opening authorize returns the code
        // because the session cookie is already set.
        findViewById<Button>(R.id.btn_continue).setOnClickListener { web.loadUrl(authUrl) }
        web.loadUrl(authUrl)
    }

    private fun intercept(uri: Uri): Boolean {
        if (uri.scheme == "http" || uri.scheme == "https") return false
        val code = uri.getQueryParameter("code")
        if (uri.scheme == BlinkApi.REDIRECT_SCHEME && code != null && !exchanging) {
            exchanging = true
            finishLogin(code)
        }
        return true
    }

    private fun finishLogin(code: String) {
        status.setText(R.string.finishing_sign_in)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { BlinkApi(this@LoginActivity).exchangeCode(code, verifier) }
                Refresher.schedule(applicationContext)
                Snapshots.schedule(applicationContext)
                TempRefresh.schedule(applicationContext)
                Refresher.refresh(applicationContext)
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                exchanging = false
                status.text = getString(R.string.sign_in_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
