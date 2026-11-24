package com.caochaoxing.android.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.caochaoxing.android.ui.theme.CxPalette
import org.json.JSONObject

private val legacyMath = Regex("""[A-Za-zΑ-ω0-9)\]}]\s*[_^]\s*(?:\{[^{}\r\n]+\}|[A-Za-zΑ-ω0-9])""")
private const val EXPLANATION_ASSET = "file:///android_asset/ai-math/answer.html"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AiMathExplanation(text: String) {
    val hasFormula = text.contains("\\(") || text.contains("\\[") || text.count { it == '$' } >= 2 || legacyMath.containsMatchIn(text)
    if (!hasFormula || text.length > 12_000) {
        Text(text)
        return
    }

    val density = LocalDensity.current
    val fontSize = with(density) { MaterialTheme.typography.bodyLarge.fontSize.toPx() / this.density }
    val color = "#" + (CxPalette.ink.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        key(text, maxWidth, color, fontSize) {
            var measuredHeight by remember { mutableIntStateOf(0) }
            Column {
                if (measuredHeight == 0) Text(text)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(measuredHeight.coerceAtLeast(1).dp),
                    factory = { context ->
                        WebView(context).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            isVerticalScrollBarEnabled = false
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = true
                            settings.textZoom = 100
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (url != EXPLANATION_ASSET) return
                                    val safeText = JSONObject.quote(text).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                                    view.evaluateJavascript("showExplanation($safeText,'$color',$fontSize)") { height ->
                                        height.toIntOrNull()?.takeIf { it > 0 }?.let { measuredHeight = it + 2 }
                                    }
                                }
                            }
                            loadUrl(EXPLANATION_ASSET)
                        }
                    },
                    onRelease = { view -> view.stopLoading(); view.destroy() },
                )
            }
        }
    }
}
