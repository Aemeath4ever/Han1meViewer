package com.yenaly.han1meviewer.ui.screen.login

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.component.appbar.HanimeScaffold
import com.yenaly.han1meviewer.ui.preview.ComponentPreview

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onShowLoginDialog: () -> Unit,
    onOpenQrScanner: () -> Unit,
    webViewFactory: () -> WebView,
    /** missav：不展示原站頁面，只用隱藏的 WebView 建立登入工作階段。 */
    missavMode: Boolean = false,
) {
    val refreshingState = rememberPullToRefreshState()
    HanimeScaffold(
        title = stringResource(R.string.login),
        onBack = onBack,
        actions = {
            // 原生账号登录入口：missav 站内登录依赖 SPA 交互，网页内操作不可靠
            TextButton(onClick = onShowLoginDialog) {
                Text(stringResource(R.string.try_login_here))
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.scan_for_cookies)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_scan_24),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                onClick = onOpenQrScanner,
            )
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = refreshingState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = refreshingState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        ) {
            if (missavMode) {
                // 站內登入走原生彈窗，這裡不該把 missav 原站頁面擺出來當背景。
                // 但仍然需要一個「活著」的 WebView：登入用的 cf_clearance / XSRF-TOKEN
                // 要從它的 CookieManager 取。放在 1dp 處渲染即可，JS 照常執行。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.missav_login_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                    AndroidView(
                        factory = { webViewFactory() },
                        modifier = Modifier.size(1.dp),
                    )
                }
            } else {
                AndroidView(
                    factory = { webViewFactory() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun LoginDialog(
    isLoggingIn: Boolean,
    onDismiss: () -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    errorMessage: String? = null,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.try_login_here)) },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.email)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoggingIn,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoggingIn,
                )
                // 登入失敗的具體原因（HTTP 狀態碼 / 服務端原文），不能只丟一個通用提示
                if (!errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLogin(username, password) },
                enabled = username.isNotBlank() && password.isNotBlank() && !isLoggingIn,
            ) {
                Text(stringResource(R.string.login))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoggingIn) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    val context = LocalContext.current
    ComponentPreview {
        LoginScreen(
            isRefreshing = true,
            onBack = {},
            onRefresh = {},
            onShowLoginDialog = {},
            onOpenQrScanner = {},
            webViewFactory = {
                WebView(context).apply {
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginDialogPreview() {
    ComponentPreview {
        LoginDialog(
            isLoggingIn = false,
            onDismiss = {},
            onLogin = { _, _ -> },
        )
    }
}
