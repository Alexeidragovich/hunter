package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.HttpTransaction
import com.example.data.ScanResult
import com.example.network.IntruderResult
import com.example.network.NetworkEngine
import com.example.ui.theme.*
import com.example.viewmodel.BurpViewModel
import com.example.viewmodel.RepeaterTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

enum class BurpTab(val title: String, val icon: ImageVector) {
    DASHBOARD("الرئيسية", Icons.Default.Dashboard),
    BROWSER("المستعرض", Icons.Default.Language),
    INTERCEPT("الاعتراض", Icons.Default.PauseCircle),
    HISTORY("التاريخ", Icons.Default.History),
    REPEATER("الطلب المكرر", Icons.Default.SendAndArchive),
    INTRUDER("المهاجم", Icons.Default.FlashOn),
    SCANNER("الفاحص", Icons.Default.Security),
    AICOPILOT("التدقيق بالذكاء", Icons.Default.Psychology),
    UTILITIES("الأدوات", Icons.Default.Build)
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun BurpUiContent(viewModel: BurpViewModel) {
    var activeTab by remember { mutableStateOf(BurpTab.DASHBOARD) }
    var selectedTransactionForDetail by remember { mutableStateOf<HttpTransaction?>(null) }

    // Intercept flows
    val interceptCount by viewModel.interceptQueue.collectAsState()
    val isCurrentlyIntercepting by viewModel.isInterceptEnabled.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Horizontal scrollable bottom bar for all our advanced pentest utilities
            ScrollableTabRow(
                selectedTabIndex = activeTab.ordinal,
                containerColor = CardBackgroundDark,
                contentColor = BurpOrange,
                edgePadding = 12.dp,
                divider = { HorizontalDivider(color = BorderDark) }
            ) {
                BurpTab.values().forEach { tab ->
                    Tab(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        text = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1
                            )
                        },
                        icon = {
                            BadgedBox(badge = {
                                if (tab == BurpTab.INTERCEPT && interceptCount.isNotEmpty()) {
                                    Badge(containerColor = SoftRed) {
                                        Text(
                                            text = interceptCount.size.toString(),
                                            color = Color.White,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    tint = if (activeTab == tab) BurpOrange else TextSecondaryDark
                                )
                            }
                        },
                        selectedContentColor = BurpOrange,
                        unselectedContentColor = TextSecondaryDark
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Top Panel: Interceptor Status and Scope Setting
            TopBannerController(
                isIntercepting = isCurrentlyIntercepting,
                onToggle = { viewModel.toggleIntercept() },
                pendingCount = interceptCount.size
            )

            HorizontalDivider(color = BorderDark, thickness = 1.dp)

            // 2. Active Tab Screen Selection
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "tab_animation"
                ) { target ->
                    when (target) {
                        BurpTab.DASHBOARD -> DashboardScreen(
                            viewModel = viewModel,
                            onNavigateToTab = { activeTab = it }
                        )
                        BurpTab.BROWSER -> WebBrowserScreen(viewModel = viewModel)
                        BurpTab.INTERCEPT -> InterceptionScreen(viewModel = viewModel)
                        BurpTab.HISTORY -> HistoryScreen(
                            viewModel = viewModel,
                            onSelectTransaction = { selectedTransactionForDetail = it }
                        )
                        BurpTab.REPEATER -> RepeaterScreen(viewModel = viewModel)
                        BurpTab.INTRUDER -> IntruderScreen(viewModel = viewModel)
                        BurpTab.SCANNER -> ScannerScreen(viewModel = viewModel)
                        BurpTab.AICOPILOT -> AiCopilotScreen(viewModel = viewModel)
                        BurpTab.UTILITIES -> UtilitiesScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    // Overlay details dialog for individual HTTP transactions
    selectedTransactionForDetail?.let { transaction ->
        TransactionDetailDialog(
            transaction = transaction,
            onDismiss = { selectedTransactionForDetail = null },
            onSendToRepeater = {
                viewModel.addRepeaterTab(transaction)
                activeTab = BurpTab.REPEATER
                selectedTransactionForDetail = null
            },
            onSendToAi = {
                viewModel.sendToAiAudit(transaction)
                activeTab = BurpTab.AICOPILOT
                selectedTransactionForDetail = null
            }
        )
    }
}

// --- TOP BANNER CONTROLLER ---
@Composable
fun TopBannerController(
    isIntercepting: Boolean,
    onToggle: () -> Unit,
    pendingCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardHeaderDark)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isIntercepting) SoftGreen else SoftRed)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "الاعتراض (Intercept) : " + if (isIntercepting) "مشتغل [مفعل]" else "مغلق [تمرير مباشر]",
                color = TextPrimaryDark,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isIntercepting && pendingCount > 0) {
                Box(
                    modifier = Modifier
                        .background(SoftRed, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$pendingCount طلب معلّق",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Switch(
                checked = isIntercepting,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("intercept_toggle"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BurpOrange,
                    uncheckedThumbColor = Color.LightGray,
                    uncheckedTrackColor = Color.DarkGray
                )
            )
        }
    }
}

// --- 1. DASHBOARD SCREEN ---
@Composable
fun DashboardScreen(viewModel: BurpViewModel, onNavigateToTab: (BurpTab) -> Unit) {
    val transactionList by viewModel.proxyTransactions.collectAsState()
    val scanResultsList by viewModel.scanResults.collectAsState()
    val isCurrentlyScanning by viewModel.isScanning.collectAsState()

    // Calculate vulnerabilities count
    val criticalCount = scanResultsList.count { it.severity.equals("Critical", ignoreCase = true) }
    val highCount = scanResultsList.count { it.severity.equals("High", ignoreCase = true) }
    val mediumCount = scanResultsList.count { it.severity.equals("Medium", ignoreCase = true) }
    val lowCount = scanResultsList.count { it.severity.equals("Low", ignoreCase = true) }
    val infoCount = scanResultsList.count { it.severity.equals("Info", ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Identity Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackgroundDark, RoundedCornerShape(12.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Logo",
                    tint = BurpOrange,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "BurpSuite Mobile (بوابة اختبار الاختراق)",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "أداة متقدمة لمسح وتدقيق ثغرات الويب، وتحليل طلبات HTTP وفحصها برمجياً في الوقت الفعلي ومساعدة الذكاء الاصطناعي.",
                    color = TextSecondaryDark,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }

        // Live stats cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "سجل الترافيك", color = TextSecondaryDark, fontSize = 11.sp)
                        Text(
                            text = "${transactionList.size}",
                            color = BurpOrange,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "الثغرات المكتشفة", color = TextSecondaryDark, fontSize = 11.sp)
                        Text(
                            text = "${scanResultsList.size}",
                            color = if (scanResultsList.isNotEmpty()) SoftRed else SoftGreen,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Vulnerability Severity Meter
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "عداد الثغرات حسب الخطورة",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SeverityCountBadge(title = "حرجة", count = criticalCount, color = SoftPurple)
                        SeverityCountBadge(title = "عالية", count = highCount, color = SoftRed)
                        SeverityCountBadge(title = "متوسطة", count = mediumCount, color = BurpOrange)
                        SeverityCountBadge(title = "منخفضة", count = lowCount, color = SoftYellow)
                        SeverityCountBadge(title = "معلومة", count = infoCount, color = SoftBlue)
                    }
                }
            }
        }

        // Feature Quick Launch Nodes
        item {
            Text(
                text = "الوصول السريع للأدوات",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickLaunchRow(
                    title = "مستعرض الويب الآمن (المحول)",
                    sub = "زيارة المواقع واعتراض طلباتها برمجياً بشكل آمن وتلقائي.",
                    icon = Icons.Default.Language,
                    onClick = { onNavigateToTab(BurpTab.BROWSER) }
                )

                QuickLaunchRow(
                    title = "فاحص الثغرات التلقائي",
                    sub = "مسح SQL Injection، ثغرات XSS، وتكشّف الملفات الحساسة.",
                    icon = Icons.Default.Security,
                    onClick = { onNavigateToTab(BurpTab.SCANNER) }
                )

                QuickLaunchRow(
                    title = "مكرر الطلبات (Repeater)",
                    sub = "تعديل محتوى الطلبات يدوياً وإرسالها وتحليل الاستجابات بدقة.",
                    icon = Icons.Default.SendAndArchive,
                    onClick = { onNavigateToTab(BurpTab.REPEATER) }
                )

                QuickLaunchRow(
                    title = "الاعتراض (Interceptor)",
                    sub = "تحليل وتعديل الطلبات المعلقة قبل أن تغادر هاتفك.",
                    icon = Icons.Default.PauseCircle,
                    onClick = { onNavigateToTab(BurpTab.INTERCEPT) }
                )
            }
        }
    }
}

@Composable
fun SeverityCountBadge(title: String, count: Int, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color(0xFF222530), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .width(50.dp)
    ) {
        Text(text = title, color = TextSecondaryDark, fontSize = 10.sp)
        Text(text = "$count", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun QuickLaunchRow(title: String, sub: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackgroundDark, RoundedCornerShape(8.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BurpOrange,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(text = sub, color = TextSecondaryDark, fontSize = 11.sp)
        }
        Icon(
            imageVector = Icons.Default.ArrowForwardIos,
            contentDescription = null,
            tint = TextMutedDark,
            modifier = Modifier.size(14.dp)
        )
    }
}

// --- 2. PROXY BROWSER SCREEN (Integrated WebView Client) ---
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(viewModel: BurpViewModel) {
    var urlInput by remember { mutableStateOf("https://httpbin.org/get") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var loadingProgress by remember { mutableStateOf(0) }
    var isWebLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isInterceptOn by viewModel.isInterceptEnabled.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Navigation bar for address input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardHeaderDark)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { webViewInstance?.goBack() }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            IconButton(
                onClick = { webViewInstance?.reload() }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
            }

            TextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("browser_url_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = TextSecondaryDark,
                    focusedContainerColor = CardBackgroundDark,
                    unfocusedContainerColor = CardBackgroundDark,
                    focusedBorderColor = BurpOrange,
                    unfocusedBorderColor = BorderDark
                ),
                shape = RoundedCornerShape(6.dp),
                placeholder = { Text("أدخل رابط الموقع للبدء بالفحص المباشر", fontSize = 11.sp, color = TextMutedDark) },
                singleLine = true
            )

            Spacer(modifier = Modifier.width(6.dp))

            Button(
                onClick = {
                    var target = urlInput.trim()
                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                        target = "https://$target"
                        urlInput = target
                    }
                    webViewInstance?.loadUrl(target)
                },
                colors = ButtonDefaults.buttonColors(containerColor = BurpOrange),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text("تصفح", fontSize = 12.sp, color = Color.White)
            }
        }

        // Web progress indicator
        if (isWebLoading) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = BurpOrange,
                trackColor = Color.DarkGray
            )
        }

        // Browser notice indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isInterceptOn) Color(0xFF3B2712) else Color(0xFF1E293B))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isInterceptOn) Icons.Default.PauseCircle else Icons.Default.Info,
                contentDescription = null,
                tint = if (isInterceptOn) BurpOrange else SoftBlue,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isInterceptOn)
                    "وضع الاعتراض فعال! طلبات WebView الأساسية سوف تظهر في تبيوب 'الاعتراض' لتفويضها."
                else
                    "المستعرض جاهز لتسجيل الطلبات. تصفح أي موقع وسيتم تسجيل المرور في 'ترافييك التاريخ'.",
                color = Color.White,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }

        // WebView view mapping
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isWebLoading = true
                            url?.let { urlInput = it }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isWebLoading = false
                        }

                        // THE MAGIC INTERCEPTOR MECHANISM ON WEBVIEW
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request == null) return null
                            val urlStr = request.url.toString()
                            val isMainFrame = request.isForMainFrame

                            // We only intercept main document resources, scripts, or API requests for high performance
                            if (isMainFrame || urlStr.endsWith(".php") || urlStr.contains("/api/")) {
                                
                                val transaction = runBlocking {
                                    NetworkEngine.executeRequest(
                                        url = urlStr,
                                        method = request.method,
                                        headers = request.requestHeaders.map { Pair(it.key, it.value) },
                                        body = "",
                                        isFromBrowser = true
                                    )
                                }

                                // If dropped by user
                                if (transaction.responseCode == 0) {
                                    return WebResourceResponse(
                                        "text/html", "UTF-8",
                                        ByteArrayInputStream("<h1>Request dropped by Interceptor</h1>".toByteArray())
                                    )
                                }

                                // Record this transaction in VM
                                CoroutineScope(Dispatchers.Main).launch {
                                    viewModel.recordTransaction(transaction)
                                }

                                val responseBytes = transaction.responseBody.toByteArray(Charsets.UTF_8)
                                val responseStream = ByteArrayInputStream(responseBytes)

                                var mimeType = "text/html"
                                val ctHeader = transaction.responseHeaders.lines()
                                    .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                                if (ctHeader != null) {
                                    mimeType = ctHeader.substringAfter(":").substringBefore(";").trim()
                                }

                                return WebResourceResponse(
                                    mimeType,
                                    "UTF-8",
                                    transaction.responseCode,
                                    "OK",
                                    transaction.responseHeaders.lines()
                                        .filter { it.contains(":") }
                                        .associate {
                                            val parts = it.split(":", limit = 2)
                                            parts[0].trim() to parts[1].trim()
                                        },
                                    responseStream
                                )
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            loadingProgress = newProgress
                        }
                    }

                    loadUrl("https://httpbin.org/get")
                    webViewInstance = this
                }
            },
            update = {
                webViewInstance = it
            }
        )
    }
}

// --- 3. INTERCEPTION QUEEN SCREEN ---
@Composable
fun InterceptionScreen(viewModel: BurpViewModel) {
    val pendingQueue by viewModel.interceptQueue.collectAsState()
    val isInterceptOn by viewModel.isInterceptEnabled.collectAsState()

    if (!isInterceptOn) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = null,
                tint = TextMutedDark,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "جهاز الاعتراض مغلق حالياً",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "قم بتشغيل 'الاعتراض' من الشريط العلوي لحبس والتقاط طلبات الـ HTTP والتلاعب بها يدوياً قبل إرسالها للإنترنت.",
                color = TextSecondaryDark,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    if (pendingQueue.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = BurpOrange, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "بانتظار التقاط طلبات جديدة...",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "التقط طلبات بالمتصفح أو أداة مسح الثغرات. سيتم حجز الطلب هنا لإدخال تعديلات حقن أكواد الاختراق الفعلي.",
                color = TextSecondaryDark,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    // Displays the first item in the queue (FIFO)
    val intercepted = pendingQueue.first()

    // Working copy states of headers, body and url
    var currentMethod by remember(intercepted.id) { mutableStateOf(intercepted.method) }
    var currentUrl by remember(intercepted.id) { mutableStateOf(intercepted.url) }
    var currentHeadersStr by remember(intercepted.id) {
        val str = intercepted.headers.joinToString("\n") { "${it.first}: ${it.second}" }
        mutableStateOf(str)
    }
    var currentBody by remember(intercepted.id) { mutableStateOf(intercepted.body) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "طلب ملتقط معلق [معالجة في الوقت الحقيقي]",
            color = BurpOrange,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        // Request URL & Method Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var expandedMethod by remember { mutableStateOf(false) }
            Box {
                Button(
                    onClick = { expandedMethod = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CardBackgroundDark),
                    border = BorderStroke(1.dp, BorderDark),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(text = currentMethod, color = BurpOrange, fontSize = 12.sp)
                }

                DropdownMenu(
                    expanded = expandedMethod,
                    onDismissRequest = { expandedMethod = false },
                    modifier = Modifier.background(CardBackgroundDark)
                ) {
                    listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { m ->
                        DropdownMenuItem(
                            text = { Text(text = m, color = Color.White) },
                            onClick = {
                                currentMethod = m
                                expandedMethod = false
                            }
                        )
                    }
                }
            }

            TextField(
                value = currentUrl,
                onValueChange = { currentUrl = it },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    focusedBorderColor = BurpOrange,
                    unfocusedBorderColor = BorderDark,
                    focusedContainerColor = CardBackgroundDark,
                    unfocusedContainerColor = CardBackgroundDark
                )
            )
        }

        // Headers Input Area
        Text(text = "الترويسات (HTTP Headers - سطر بسطر):", color = TextSecondaryDark, fontSize = 12.sp)
        TextField(
            value = currentHeadersStr,
            onValueChange = { currentHeadersStr = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                focusedBorderColor = BurpOrange,
                unfocusedBorderColor = BorderDark,
                focusedContainerColor = CardBackgroundDark,
                unfocusedContainerColor = CardBackgroundDark
            )
        )

        // Body Input Area
        Text(text = "محتوى الطلب (Payload Body):", color = TextSecondaryDark, fontSize = 12.sp)
        TextField(
            value = currentBody,
            onValueChange = { currentBody = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                focusedBorderColor = BurpOrange,
                unfocusedBorderColor = BorderDark,
                focusedContainerColor = CardBackgroundDark,
                unfocusedContainerColor = CardBackgroundDark
            ),
            placeholder = { Text("محتوى الطلب فارغ...", fontSize = 11.sp, color = TextMutedDark) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Intercept Actions Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    viewModel.forwardInterceptedRequest(
                        id = intercepted.id,
                        method = currentMethod,
                        url = currentUrl,
                        headersStr = currentHeadersStr,
                        body = currentBody
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("intercept_forward"),
                colors = ButtonDefaults.buttonColors(containerColor = SoftGreen),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("تمرير (Forward)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Button(
                onClick = { viewModel.dropInterceptedRequest(intercepted.id) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("intercept_drop"),
                colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("إسقاط (Drop)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

// --- 4. HISTORY TRAFFIC LIST ---
@Composable
fun HistoryScreen(
    viewModel: BurpViewModel,
    onSelectTransaction: (HttpTransaction) -> Unit
) {
    val transactionList by viewModel.proxyTransactions.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val filteredList = transactionList.filter {
        it.url.contains(searchQuery, ignoreCase = true) ||
                it.responseCode.toString().contains(searchQuery) ||
                it.method.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                placeholder = { Text("بحث باسم النطاق، دالة الطلب، أو الكود...", fontSize = 11.sp, color = TextMutedDark) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    focusedBorderColor = BurpOrange,
                    unfocusedBorderColor = BorderDark,
                    focusedContainerColor = CardBackgroundDark,
                    unfocusedContainerColor = CardBackgroundDark
                ),
                shape = RoundedCornerShape(6.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMutedDark) },
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { viewModel.clearLogHistory() },
                modifier = Modifier
                    .background(CardBackgroundDark, RoundedCornerShape(6.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
                    .size(48.dp)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = SoftRed)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredList.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = TextMutedDark, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "مكتبة المحفوظات فارغة", color = TextSecondaryDark, fontSize = 13.sp)
                Text(text = "سيظهر هنا أي استعلام HTTP تعبر السيرفر الخاص بجمارك المحمول.", color = TextMutedDark, fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredList) { item ->
                    TransactionItemRow(item = item, onClick = { onSelectTransaction(item) })
                }
            }
        }
    }
}

@Composable
fun TransactionItemRow(item: HttpTransaction, onClick: () -> Unit) {
    val methodColor = when (item.method.uppercase()) {
        "GET" -> MethodGet
        "POST" -> MethodPost
        "PUT" -> MethodPut
        "DELETE" -> MethodDelete
        "SCAN" -> MethodScan
        else -> BurpOrange
    }

    val codeColor = when (item.responseCode) {
        in 200..299 -> SoftGreen
        in 300..399 -> SoftYellow
        in 400..499 -> SoftRed
        in 500..599 -> Color(0xFFC0392B)
        else -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackgroundDark, RoundedCornerShape(8.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Method chip
        Box(
            modifier = Modifier
                .width(55.dp)
                .background(methodColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .border(1.dp, methodColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.method,
                color = methodColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // URL details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.url,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${item.durationMs}ms",
                    color = TextMutedDark,
                    fontSize = 11.sp
                )
                if (item.severityRating != "Safe") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                when (item.severityRating) {
                                    "Critical" -> SoftPurple.copy(alpha = 0.2f)
                                    "High" -> SoftRed.copy(alpha = 0.2f)
                                    "Medium" -> BurpOrange.copy(alpha = 0.2f)
                                    else -> SoftYellow.copy(alpha = 0.2f)
                                },
                                shape = RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "ثغرة (${item.severityRating})",
                            color = when (item.severityRating) {
                                "Critical" -> SoftPurple
                                "High" -> SoftRed
                                "Medium" -> BurpOrange
                                else -> SoftYellow
                            },
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Status code badge
        Box(
            modifier = Modifier
                .background(codeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .border(1.dp, codeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (item.responseCode == 0) "DROP" else item.responseCode.toString(),
                color = codeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// --- 5. REPEATER SCREEN ---
@Composable
fun RepeaterScreen(viewModel: BurpViewModel) {
    val tabList by viewModel.repeaterTabs.collectAsState()
    val activeTabId by viewModel.activeRepeaterTabId.collectAsState()

    val currentTab = tabList.find { it.id == activeTabId } ?: tabList.first()

    var editingUrl by remember(currentTab.id) { mutableStateOf(currentTab.url) }
    var editingMethod by remember(currentTab.id) { mutableStateOf(currentTab.method) }
    var editingHeaders by remember(currentTab.id) {
        val str = currentTab.headers.joinToString("\n") { "${it.first}: ${it.second}" }
        mutableStateOf(str)
    }
    var editingBody by remember(currentTab.id) { mutableStateOf(currentTab.body) }

    var responseTabSelect by remember { mutableStateOf(0) } // 0 = Body, 1 = Headers

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Tab Headers Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabList.forEach { rTab ->
                val isActive = rTab.id == activeTabId
                Row(
                    modifier = Modifier
                        .background(
                            if (isActive) BurpOrange.copy(alpha = 0.15f) else CardBackgroundDark,
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            if (isActive) BurpOrange else BorderDark,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { viewModel.changeActiveRepeaterTab(rTab.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rTab.name,
                        color = if (isActive) BurpOrange else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (tabList.size > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMutedDark,
                            modifier = Modifier
                                .size(12.dp)
                                .clickable { viewModel.removeRepeaterTab(rTab.id) }
                        )
                    }
                }
            }

            // Plus Tab button
            IconButton(
                onClick = { viewModel.addRepeaterTab() },
                modifier = Modifier
                    .background(CardBackgroundDark, RoundedCornerShape(6.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
                    .size(34.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add tab", tint = Color.LightGray)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Working Body panel containing parameters and payload fields (Dual view: REQUEST | RESPONSE)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Column 1: REQUEST CRAFTING
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "رأس الطلب (HTTP Request)", color = BurpOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                // Method Select & URL
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    var mExpanded by remember { mutableStateOf(false) }
                    Box {
                        Button(
                            onClick = { mExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CardBackgroundDark),
                            border = BorderStroke(1.dp, BorderDark),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Text(text = editingMethod, color = BurpOrange, fontSize = 10.sp)
                        }
                        DropdownMenu(
                            expanded = mExpanded,
                            onDismissRequest = { mExpanded = false },
                            modifier = Modifier.background(CardBackgroundDark)
                        ) {
                            listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(text = m, color = Color.White) },
                                    onClick = {
                                        editingMethod = m
                                        mExpanded = false
                                        viewModel.updateActiveTabDetails(editingUrl, editingMethod, editingHeaders, editingBody)
                                    }
                                )
                            }
                        }
                    }

                    TextField(
                        value = editingUrl,
                        onValueChange = {
                            editingUrl = it
                            viewModel.updateActiveTabDetails(editingUrl, editingMethod, editingHeaders, editingBody)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            focusedBorderColor = BurpOrange,
                            unfocusedBorderColor = BorderDark,
                            focusedContainerColor = CardBackgroundDark,
                            unfocusedContainerColor = CardBackgroundDark
                        )
                    )
                }

                Text(text = "الترويسات (Headers):", color = TextSecondaryDark, fontSize = 11.sp)
                TextField(
                    value = editingHeaders,
                    onValueChange = {
                        editingHeaders = it
                        viewModel.updateActiveTabDetails(editingUrl, editingMethod, editingHeaders, editingBody)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = BurpOrange,
                        unfocusedBorderColor = BorderDark,
                        focusedContainerColor = CardBackgroundDark,
                        unfocusedContainerColor = CardBackgroundDark
                    )
                )

                Text(text = "محتوى الـ Body Payload:", color = TextSecondaryDark, fontSize = 11.sp)
                TextField(
                    value = editingBody,
                    onValueChange = {
                        editingBody = it
                        viewModel.updateActiveTabDetails(editingUrl, editingMethod, editingHeaders, editingBody)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = BurpOrange,
                        unfocusedBorderColor = BorderDark,
                        focusedContainerColor = CardBackgroundDark,
                        unfocusedContainerColor = CardBackgroundDark
                    ),
                    placeholder = { Text("محتوى الطلب (مخصص للـ POST/PUT)", fontSize = 10.sp, color = TextMutedDark) }
                )

                Button(
                    onClick = { viewModel.executeRepeaterRequest() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("repeater_send_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = BurpOrange),
                    shape = RoundedCornerShape(6.dp),
                    enabled = !currentTab.isLoading
                ) {
                    if (currentTab.isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إرسال الطلب (Send)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // Column 2: RESPONSE METRICS
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "نص الاستجابة (HTTP Response) " + if (currentTab.durationMs > 0) "[${currentTab.durationMs}ms]" else "",
                    color = SoftGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Custom Response switcher tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { responseTabSelect = 0 },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (responseTabSelect == 0) Color(0xFF2E3440) else CardBackgroundDark
                        ),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("المحتوى (Body)", fontSize = 10.sp, color = if (responseTabSelect == 0) BurpOrange else Color.LightGray)
                    }

                    Button(
                        onClick = { responseTabSelect = 1 },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (responseTabSelect == 1) Color(0xFF2E3440) else CardBackgroundDark
                        ),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("الترويسات (Headers)", fontSize = 10.sp, color = if (responseTabSelect == 1) BurpOrange else Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // View Box
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CardBackgroundDark, RoundedCornerShape(6.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    if (currentTab.isLoading) {
                        CircularProgressIndicator(
                            color = BurpOrange,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center)
                        )
                    } else {
                        val displayContent = if (responseTabSelect == 0) {
                            currentTab.responseBody.ifBlank { "بانتظار إرسال الاستعلام..." }
                        } else {
                            currentTab.responseHeaders.ifBlank { "بانتظار إرسال الاستعلام..." }
                        }

                        Text(
                            text = displayContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (currentTab.responseCode != 0) Color.LightGray else TextMutedDark
                        )
                    }
                }
            }
        }
    }
}

// --- 6. INTRUDER (FUZZER) SCREEN ---
@Composable
fun IntruderScreen(viewModel: BurpViewModel) {
    val targetUrl by viewModel.intruderTargetUrl.collectAsState()
    val method by viewModel.intruderMethod.collectAsState()
    val payloadType by viewModel.intruderPayloadClassification.collectAsState()
    val customPayloadList by viewModel.intruderCustomList.collectAsState()
    val isRunning by viewModel.isIntruderRunning.collectAsState()
    val intruderItems by viewModel.intruderItems.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "مهاجم ثغرات وحقن الأكواد الذكي (Intruder)",
                color = BurpOrange,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Configuration Target Block
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تهيئة الاستهداف والرمز البديل §PAYLOAD§:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    // Method & Url Input
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        var isMExp by remember { mutableStateOf(false) }
                        Box {
                            Button(
                                onClick = { isMExp = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222530)),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(method, color = BurpOrange, fontSize = 11.sp)
                            }
                            DropdownMenu(expanded = isMExp, onDismissRequest = { isMExp = false }, modifier = Modifier.background(CardBackgroundDark)) {
                                listOf("GET", "POST", "PUT", "DELETE").forEach { m ->
                                    DropdownMenuItem(text = { Text(m, color = Color.White) }, onClick = { viewModel.intruderMethod.value = m; isMExp = false })
                                }
                            }
                        }

                        TextField(
                            value = targetUrl,
                            onValueChange = { viewModel.intruderTargetUrl.value = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, focusedBorderColor = BurpOrange, unfocusedBorderColor = BorderDark
                            )
                        )
                    }

                    Text("حدد تصنيف القاموس (Payload Type):", color = TextSecondaryDark, fontSize = 11.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("SQL Injection", "XSS", "Path Traversal", "Custom").forEach { typeName ->
                            val isChosen = payloadType == typeName
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (isChosen) BurpOrange.copy(alpha = 0.2f) else Color(0xFF222630), RoundedCornerShape(4.dp))
                                    .border(1.dp, if (isChosen) BurpOrange else BorderDark, RoundedCornerShape(4.dp))
                                    .clickable { viewModel.intruderPayloadClassification.value = typeName }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(typeName, color = if (isChosen) BurpOrange else Color.LightGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (payloadType == "Custom") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("القاموس اليدوي (كلمة بكل سطر):", color = TextSecondaryDark, fontSize = 11.sp)
                        TextField(
                            value = customPayloadList,
                            onValueChange = { viewModel.intruderCustomList.value = it },
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, focusedBorderColor = BurpOrange, unfocusedBorderColor = BorderDark
                            )
                        )
                    }

                    Button(
                        onClick = { viewModel.runIntruderFuzzing() },
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BurpOrange)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.FlashOn, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تشغيل الفحص السريع (Run Intruder)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Live Results Block title
        item {
            Text("نتايج الحقن الجارية (${intruderItems.size}):", color = SoftGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        // Headers of the result Table
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardHeaderDark, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("#", color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.width(20.dp), fontWeight = FontWeight.Bold)
                Text("المدخل (Payload)", color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold)
                Text("كود HTTP", color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("طول الرد", color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("المدة (ms)", color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }
        }

        if (intruderItems.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackgroundDark)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("لم يبدأ الفحص بعد.", color = TextMutedDark, fontSize = 11.sp)
                }
            }
        } else {
            items(intruderItems) { res ->
                val rowBg = if (res.statusCode == 200) Color(0xFF1E281F) else CardBackgroundDark
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .border(0.5.dp, BorderDark)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${res.payloadNum}", color = TextMutedDark, fontSize = 11.sp, modifier = Modifier.width(20.dp))
                    Text(
                        res.payload,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${res.statusCode}",
                        color = if (res.statusCode == 200) SoftGreen else SoftRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${res.length}B", color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${res.durationMs}", color = BurpOrange, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// --- 7. AUTOMATED SCANNER SCREEN ---
@Composable
fun ScannerScreen(viewModel: BurpViewModel) {
    val targetUrl by viewModel.scannerTargetUrl.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val progress by viewModel.scanProgress.collectAsState()
    val progressLogs by viewModel.scanProgressLog.collectAsState()
    val scanResultsList by viewModel.scanResults.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "فاحص الثغرات البرمجي التلقائي (Security Scanner)",
                color = BurpOrange,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Trigger Configuration Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل موقع الهدف للفحص الشامل (Scope Target SQLi/XSS/Files):", color = Color.White, fontSize = 11.sp)

                    TextField(
                        value = targetUrl,
                        onValueChange = { viewModel.scannerTargetUrl.value = it },
                        modifier = Modifier.fillMaxWidth().testTag("scanner_target_input"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, focusedBorderColor = BurpOrange, unfocusedBorderColor = BorderDark
                        ),
                        placeholder = { Text("مثال: https://example.com") }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.startTargetSecurityScan() },
                            enabled = !isScanning,
                            modifier = Modifier.weight(1f).height(44.dp).testTag("start_scan_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = BurpOrange)
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("بدء فحص الثغرات", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = { viewModel.clearScanStats() },
                            modifier = Modifier.height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E3440))
                        ) {
                            Text("تصفير المحفوظات", color = Color.White)
                        }
                    }
                }
            }
        }

        // Progress bar container
        if (isScanning || progress > 0) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardHeaderDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("تقدم الفحص التلقائي:", color = Color.White, fontSize = 11.sp)
                            Text("$progress%", color = BurpOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = BurpOrange)

                        Spacer(modifier = Modifier.height(10.dp))

                        // Box showing last 3 logs
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .background(CardBackgroundDark, RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        ) {
                            val lastLogs = progressLogs.takeLast(2).joinToString("\n")
                            Text(text = lastLogs, color = SoftGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Scan Results
        item {
            Text("الثغرات ونقاط الضعف المكتشفة (${scanResultsList.size}):", color = SoftRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        if (scanResultsList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackgroundDark, RoundedCornerShape(8.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("لا يوجد ثغرات مسجّلة حالياً للهدف الحاضر.", color = TextMutedDark, fontSize = 11.sp)
                }
            }
        } else {
            items(scanResultsList) { result ->
                ScanResultCard(result = result)
            }
        }
    }
}

@Composable
fun ScanResultCard(result: ScanResult) {
    val severityColor = when (result.severity.uppercase()) {
        "CRITICAL" -> SoftPurple
        "HIGH" -> SoftRed
        "MEDIUM" -> BurpOrange
        "LOW" -> SoftYellow
        else -> SoftBlue
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(result.vulnType, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .background(severityColor.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(result.severity, color = severityColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text("دليل الحقن / المسار المكتشف:", color = TextSecondaryDark, fontSize = 10.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF222630), RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Text(result.evidence, color = SoftGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }

            Text(result.description, color = TextSecondaryDark, fontSize = 11.sp, lineHeight = 16.sp)

            Spacer(modifier = Modifier.height(2.dp))
            HorizontalDivider(color = BorderDark)

            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Build, contentDescription = null, tint = SoftGreen, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text("خطة العلاج والمكافحة (Remediation):", color = SoftGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(result.remediation, color = TextSecondaryDark, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

// --- 8. AI COPILOT AUDIT SCREEN ---
@Composable
fun AiCopilotScreen(viewModel: BurpViewModel) {
    val selectedTrans by viewModel.aiSelectedTransaction.collectAsState()
    val reportContent by viewModel.aiReport.collectAsState()
    val isAiLoading by viewModel.isAiLoading.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "المساعد الأمني المتقدم لفك الثغرات (AI Security Audit)",
            color = BurpOrange,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
            border = BorderStroke(1.dp, BorderDark)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الأستعلام والمسار الحالي المرسل للتدقيق بالذكاء:", color = Color.White, fontSize = 11.sp)

                if (selectedTrans == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardHeaderDark, RoundedCornerShape(4.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لم تقم بتحديد صفقة حزمة HTTP بعد للتأكيد بالذكاء.\nقم بزيارة تبيوب 'التاريخ (History)' واضغط مطولاً على أي حزمة ثم اختر 'تحليل بالذكاء الاصطناعي'.",
                            color = TextMutedDark,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(MethodGet.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(selectedTrans!!.method, color = MethodGet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            selectedTrans!!.url,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = { viewModel.sendToAiAudit(selectedTrans!!) },
                        enabled = !isAiLoading,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BurpOrange)
                    ) {
                        if (isAiLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إعادة تشغيل التدقيق عبر فلاتر نموذج الذكاء الاصطناعي", color = Color.White)
                        }
                    }
                }
            }
        }

        // Report Screen view
        if (isAiLoading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = BurpOrange)
                Spacer(modifier = Modifier.height(12.dp))
                Text("جاري تمرير حزمة الـ HTTP لمرشحات ذكاء مجمع الكلاود الخاص بجوجل ودمجه بالـ AI...", color = TextSecondaryDark, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        } else if (reportContent != null) {
            Text("تقرير التدقيق والتحكيم الأمني بالذكاء الاصطناعي:", color = SoftGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Simulating markdown headings renderer simply
                    val lines = reportContent!!.lines()
                    for (line in lines) {
                        val trimmed = line.trim()
                        when {
                            trimmed.startsWith("#") -> {
                                val depth = trimmed.takeWhile { it == '#' }.length
                                val content = trimmed.substring(depth).trim()
                                Text(
                                    text = content,
                                    fontSize = if (depth == 1) 18.sp else 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BurpOrange,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            trimmed.startsWith("-") || trimmed.startsWith("*") -> {
                                val content = trimmed.substring(1).trim()
                                Row(modifier = Modifier.padding(start = 8.dp)) {
                                    Text("• ", color = BurpOrange, fontWeight = FontWeight.Bold)
                                    Text(content, color = TextPrimaryDark, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                            trimmed.startsWith("```") -> {
                                // Skip code tag lines
                            }
                            else -> {
                                if (trimmed.isNotEmpty()) {
                                    Text(
                                        text = trimmed,
                                        color = TextPrimaryDark,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- 9. UTILITIES SCREEN (Encoder, Decoder, MD5/SHA) ---
@Composable
fun UtilitiesScreen(viewModel: BurpViewModel) {
    val userInput by viewModel.toolInput.collectAsState()
    val outputResult by viewModel.toolOutput.collectAsState()

    val clip = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "محوّل الأكواد الرقمي وأجهزة التشفير (Digital Decoder Toolkit)",
                color = BurpOrange,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Input card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل المدخل (النص أو الرموز):", color = Color.White, fontSize = 12.sp)

                    TextField(
                        value = userInput,
                        onValueChange = { viewModel.toolInput.value = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp).testTag("toolkit_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, focusedBorderColor = BurpOrange, unfocusedBorderColor = BorderDark
                        ),
                        placeholder = { Text("أدخل الأكواد أو الـ Payload هنا للترجمة والتشفير...") }
                    )

                    // Encoding Options grid
                    Text("تحديد عملية التحويل:", color = TextSecondaryDark, fontSize = 11.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TransformButton(title = "URL Encode", onClick = { viewModel.runDecoderAction("URL Encode") }, modifier = Modifier.weight(1f))
                            TransformButton(title = "URL Decode", onClick = { viewModel.runDecoderAction("URL Decode") }, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TransformButton(title = "Base64 Encode", onClick = { viewModel.runDecoderAction("Base64 Encode") }, modifier = Modifier.weight(1f))
                            TransformButton(title = "Base64 Decode", onClick = { viewModel.runDecoderAction("Base64 Decode") }, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TransformButton(title = "Hex String", onClick = { viewModel.runDecoderAction("Hex Encode") }, modifier = Modifier.weight(1f))
                            TransformButton(title = "MD5 Hash", onClick = { viewModel.runDecoderAction("MD5 Hash") }, modifier = Modifier.weight(1f))
                            TransformButton(title = "SHA-256 Hash", onClick = { viewModel.runDecoderAction("SHA-256 Hash") }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Output Result card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackgroundDark),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("النتيجة المخرجة:", color = SoftGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                        IconButton(
                            onClick = { clip.setText(AnnotatedString(outputResult)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "CopyOutput", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp)
                            .background(Color(0xFF222630), RoundedCornerShape(4.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = outputResult.ifEmpty { "لا توجد نتيجة لمعالجتها حالياً." },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (outputResult.isNotEmpty()) Color.White else TextMutedDark
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TransformButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222530)),
        modifier = modifier.height(34.dp),
        border = BorderStroke(1.dp, BorderDark),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(title, fontSize = 9.sp, color = BurpOrange, fontWeight = FontWeight.Bold)
    }
}

// --- OVERLAY DIALOG FOR TRANSACTION DETAIL INSPECTOR ---
@Composable
fun TransactionDetailDialog(
    transaction: HttpTransaction,
    onDismiss: () -> Unit,
    onSendToRepeater: () -> Unit,
    onSendToAi: () -> Unit
) {
    var detailTabSelect by remember { mutableStateOf(0) } // 0 = Request, 1 = Response

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSendToRepeater,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = BurpOrange)
                ) {
                    Text("إرسال للـ Repeater", fontSize = 11.sp)
                }

                Button(
                    onClick = onSendToAi,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SoftPurple)
                ) {
                    Text("تحليل بالذكاء الاصطناعي", fontSize = 11.sp)
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E3440))
                ) {
                    Text("إغلاق", fontSize = 11.sp)
                }
            }
        },
        title = {
            Column {
                Text("محلل الحزمة (HTTP Packet Inspector)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(transaction.url, fontSize = 10.sp, color = TextMutedDark, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
            ) {
                // Selector Row for Request/Response
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { detailTabSelect = 0 },
                        modifier = Modifier.weight(1f).height(34.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (detailTabSelect == 0) Color(0xFF222630) else CardBackgroundDark),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("الطلب (Request)", fontSize = 11.sp, color = if (detailTabSelect == 0) BurpOrange else Color.LightGray)
                    }

                    Button(
                        onClick = { detailTabSelect = 1 },
                        modifier = Modifier.weight(1f).height(34.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (detailTabSelect == 1) Color(0xFF222630) else CardBackgroundDark),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("الاستجابة (Response)", fontSize = 11.sp, color = if (detailTabSelect == 1) BurpOrange else Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable details section
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(CardBackgroundDark, RoundedCornerShape(6.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    val codeContent = if (detailTabSelect == 0) {
                        buildString {
                            append(transaction.method).append(" ").append(transaction.url).append("\n\n")
                            append(transaction.requestHeaders).append("\n\n")
                            append(transaction.requestBody)
                        }
                    } else {
                        buildString {
                            append("STATUS: ").append(transaction.responseCode).append("\n\n")
                            append(transaction.responseHeaders).append("\n\n")
                            append(transaction.responseBody)
                        }
                    }

                    Text(text = codeContent, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White)
                }
            }
        },
        containerColor = Color(0xFF13151D),
        shape = RoundedCornerShape(12.dp)
    )
}
