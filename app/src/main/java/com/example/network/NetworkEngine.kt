package com.example.network

import android.util.Log
import com.example.BuildConfig
import com.example.data.HttpTransaction
import com.example.data.ScanResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

// --- Models for Interception ---
enum class InterceptAction { FORWARD, DROP }

data class InterceptResult(
    val action: InterceptAction,
    val modifiedMethod: String,
    val modifiedUrl: String,
    val modifiedHeaders: List<Pair<String, String>>,
    val modifiedBody: String
)

data class InterceptedRequest(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val method: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val deferredResult: CompletableDeferred<InterceptResult>
)

// --- Payload Options for Fuzzing & Attacks ---
object SecurityPayloads {
    val SQL_INJECTIONS = listOf(
        "' OR '1'='1",
        "1' OR '1'='1' --",
        "' UNION SELECT null, null, null--",
        "admin' --",
        "1; DROP TABLE users; --",
        "' AND 1=2 --",
        "' OR 'x'='x"
    )

    val XSS_PAYLOADS = listOf(
        "<script>alert(1)</script>",
        "\"><script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<svg/onload=alert(1)>",
        "javascript:alert(1)",
        "';alert(1);//"
    )

    val PATH_TRAVERSAL = listOf(
        "../../../../etc/passwd",
        "..%2f..%2f..%2f..%2fetc%2fpasswd",
        "..\\..\\..\\windows\\win.ini",
        "/etc/passwd",
        "../../etc/passwd\u0000"
    )

    val ADMIN_SENSITIVE_PATHS = listOf(
        "robots.txt",
        ".env",
        ".git/config",
        "wp-admin/",
        "admin/",
        "api/v1/",
        "backup.sql",
        "config.json",
        ".env.example",
        "phpinfo.php"
    )
}

// --- Fuzzer Result ---
data class IntruderResult(
    val id: String = UUID.randomUUID().toString(),
    val payloadNum: Int,
    val payload: String,
    val url: String,
    val method: String,
    val statusCode: Int,
    val length: Long,
    val durationMs: Long,
    val matchesSignature: Boolean = false
)

object NetworkEngine {
    private const val TAG = "NetworkEngine"

    // Custom non-redirect OkHttpClient to intercept responses and manually inspect headers
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false) // Disable redirect so we can audit 302/301 responses
        .followSslRedirects(false)
        .build()

    // OkHttpClient with redirects for general browsing
    private val browsingClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // OkHttpClient for Gemini API (Long timeouts)
    private val geminiClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Intercept States
    private val _isInterceptEnabled = MutableStateFlow(false)
    val isInterceptEnabled = _isInterceptEnabled.asStateFlow()

    private val _interceptQueue = MutableStateFlow<List<InterceptedRequest>>(emptyList())
    val interceptQueue = _interceptQueue.asStateFlow()

    fun setInterceptEnabled(enabled: Boolean) {
        _isInterceptEnabled.value = enabled
        if (!enabled) {
            // Drop everything in intercept queue
            val queueCopy = _interceptQueue.value
            _interceptQueue.value = emptyList()
            for (req in queueCopy) {
                req.deferredResult.complete(
                    InterceptResult(
                        InterceptAction.DROP,
                        req.method,
                        req.url,
                        req.headers,
                        req.body
                    )
                )
            }
        }
    }

    fun forwardInterceptedRequest(id: String, method: String, url: String, headers: List<Pair<String, String>>, body: String) {
        val list = _interceptQueue.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            val req = list.removeAt(index)
            _interceptQueue.value = list
            req.deferredResult.complete(
                InterceptResult(
                    InterceptAction.FORWARD,
                    method,
                    url,
                    headers,
                    body
                )
            )
        }
    }

    fun dropInterceptedRequest(id: String) {
        val list = _interceptQueue.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            val req = list.removeAt(index)
            _interceptQueue.value = list
            req.deferredResult.complete(
                InterceptResult(
                    InterceptAction.DROP,
                    req.method,
                    req.url,
                    req.headers,
                    ""
                )
            )
        }
    }

    // Main interceptable execution wrapper
    suspend fun executeRequest(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String,
        isFromBrowser: Boolean = false
    ): HttpTransaction = withContext(Dispatchers.IO) {
        var activeMethod = method
        var activeUrl = url
        var activeHeaders = headers
        var activeBody = body

        var resolvedToForward = true

        // 1. Interception Phase
        if (_isInterceptEnabled.value) {
            val deferred = CompletableDeferred<InterceptResult>()
            val intercepted = InterceptedRequest(
                url = url,
                method = method,
                headers = headers,
                body = body,
                deferredResult = deferred
            )

            // Add to flow list
            _interceptQueue.value = _interceptQueue.value + intercepted

            // Wait for user interaction
            val result = deferred.await()
            if (result.action == InterceptAction.DROP) {
                resolvedToForward = false
            } else {
                activeMethod = result.modifiedMethod
                activeUrl = result.modifiedUrl
                activeHeaders = result.modifiedHeaders
                activeBody = result.modifiedBody
            }
        }

        if (!resolvedToForward) {
            // Request was dropped
            return@withContext HttpTransaction(
                url = activeUrl,
                method = activeMethod,
                requestHeaders = activeHeaders.joinToString("\n") { "${it.first}: ${it.second}" },
                requestBody = activeBody,
                responseCode = 0,
                responseHeaders = "Status: Dropped by User",
                responseBody = "Request Intercepted and Dropped.",
                durationMs = 0L,
                severityRating = "Info",
                vulnDetails = "Transaction Intercepted & Dropped"
            )
        }

        // 2. Network Transmission Phase
        val startTime = System.currentTimeMillis()
        var respCode = 0
        var respHeadersStr = ""
        var respBodyStr = ""

        try {
            val reqBuilder = Request.Builder().url(activeUrl)

            // Set Headers
            var hasUserAgent = false
            activeHeaders.forEach { (k, v) ->
                if (k.isNotBlank()) {
                    reqBuilder.addHeader(k, v)
                    if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                }
            }
            if (!hasUserAgent) {
                reqBuilder.addHeader("User-Agent", "BurpSuiteMobile/1.0 (Android Security Pentester)")
            }

            // Set Method & Body
            if (activeMethod.equals("GET", ignoreCase = true) || activeMethod.equals("HEAD", ignoreCase = true)) {
                reqBuilder.method(activeMethod, null)
            } else {
                val mediaType = activeHeaders.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
                    ?: "application/x-www-form-urlencoded"
                reqBuilder.method(activeMethod, activeBody.toRequestBody(mediaType.toMediaTypeOrNull()))
            }

            // Use non-redirect client by default, or browser client if browsing
            val activeClient = if (isFromBrowser) browsingClient else client
            val networkCall = activeClient.newCall(reqBuilder.build())

            val rawResponse: Response = networkCall.execute()
            respCode = rawResponse.code
            val duration = System.currentTimeMillis() - startTime

            // Format Response Headers
            val headersMap = rawResponse.headers
            respHeadersStr = buildString {
                append("HTTP/1.1 ").append(rawResponse.code).append(" ").append(rawResponse.message).append("\n")
                headersMap.forEach { pair ->
                    append(pair.first).append(": ").append(pair.second).append("\n")
                }
            }

            // Extract Response Body
            respBodyStr = rawResponse.body?.string() ?: ""

            // Close stream safely
            rawResponse.close()

            // Analyze simple vulnerability ratings (on the fly)
            val vulnAnalysis = analyzeVulnerabilitiesOnCompletedRequest(activeUrl, respCode, respHeadersStr, respBodyStr)

            return@withContext HttpTransaction(
                url = activeUrl,
                method = activeMethod,
                requestHeaders = activeHeaders.joinToString("\n") { "${it.first}: ${it.second}" },
                requestBody = activeBody,
                responseCode = respCode,
                responseHeaders = respHeadersStr,
                responseBody = respBodyStr,
                durationMs = duration,
                timestamp = System.currentTimeMillis(),
                severityRating = vulnAnalysis.first,
                vulnDetails = vulnAnalysis.second
            )

        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ", e)
            val duration = System.currentTimeMillis() - startTime
            return@withContext HttpTransaction(
                url = activeUrl,
                method = activeMethod,
                requestHeaders = activeHeaders.joinToString("\n") { "${it.first}: ${it.second}" },
                requestBody = activeBody,
                responseCode = 500,
                responseHeaders = "Status: Connection Failed\nError: ${e.message}",
                responseBody = "Exception: ${e.localizedMessage}\nCheck target server status or local routing rules.",
                durationMs = duration,
                severityRating = "Info",
                vulnDetails = "Connection Error"
            )
        }
    }

    // Pre-analyze completed transaction for common items
    private fun analyzeVulnerabilitiesOnCompletedRequest(
        url: String,
        code: Int,
        headers: String,
        body: String
    ): Pair<String, String> {
        val problems = mutableListOf<String>()
        var highestSeverity = "Safe"

        // 1. Missing Security Headers
        if (!headers.contains("Content-Security-Policy", ignoreCase = true)) {
            problems.add("Missing Content-Security-Policy (CSP)")
            highestSeverity = "Low"
        }
        if (!headers.contains("X-Frame-Options", ignoreCase = true)) {
            problems.add("Missing X-Frame-Options (Clickjumping Hazard)")
            highestSeverity = "Low"
        }
        if (!headers.contains("X-Content-Type-Options", ignoreCase = true)) {
            problems.add("Missing X-Content-Type-Options")
            highestSeverity = "Low"
        }

        // 2. Secure Flag checking on Sensitive Cookies
        if (headers.contains("Set-Cookie", ignoreCase = true)) {
            val lines = headers.split("\n")
            for (line in lines) {
                if (line.contains("Set-Cookie", ignoreCase = true)) {
                    if (!line.contains("secure", ignoreCase = true) && url.startsWith("https", ignoreCase = true)) {
                        problems.add("Cookie missing 'Secure' attribute")
                        if (highestSeverity == "Safe" || highestSeverity == "Low") highestSeverity = "Medium"
                    }
                    if (!line.contains("HttpOnly", ignoreCase = true)) {
                        problems.add("Cookie missing 'HttpOnly' attribute (XSS payload readable)")
                        if (highestSeverity == "Safe" || highestSeverity == "Low") highestSeverity = "Medium"
                    }
                }
            }
        }

        // 3. Cleartext Transmission
        if (url.startsWith("http://", ignoreCase = true)) {
            problems.add("Insecure Cleartext Transmission (unencrypted HTTP)")
            if (highestSeverity != "High" && highestSeverity != "Critical") highestSeverity = "Medium"
        }

        // 4. Server Signature details Exposure
        if (headers.contains("Server: ", ignoreCase = true) || headers.contains("X-Powered-By: ", ignoreCase = true)) {
            problems.add("Verbose server technology banners (Information Leakage)")
        }

        // 5. Database errors in Response (indicates potential SQL Injection)
        val sqlErrors = listOf(
            "SQL syntax", "mysql_fetch", "sqlite_error", "ORA-", "PostgreSQL query failed", "DriverException", "Microsoft OLE DB"
        )
        for (pattern in sqlErrors) {
            if (body.contains(pattern, ignoreCase = true)) {
                problems.add("Active Database Error detected in response body (SQL Injection Vulnerability)")
                highestSeverity = "High"
            }
        }

        return if (problems.isEmpty()) {
            Pair("Safe", "No anomalies or security issues detected.")
        } else {
            Pair(highestSeverity, problems.joinToString("; "))
        }
    }

    // --- Active Vulnerability Scanner Engine ---
    suspend fun runAutomatedScan(targetUrl: String, onStepProgress: (String, Int) -> Unit): List<ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScanResult>()
        val parsedUrl = try { URL(targetUrl) } catch (e: Exception) { return@withContext emptyList() }
        val rootScope = "${parsedUrl.protocol}://${parsedUrl.host}${if (parsedUrl.port != -1) ":" + parsedUrl.port else ""}/"

        // Step 1: Base Target Inspection
        onStepProgress("البدء: فحص الاستجابة الأساسية وهيكل بروتوكول HTTP...", 10)
        val baseTrans = executeRequest(rootScope, "GET", emptyList(), "")
        if (baseTrans.responseCode == 0) {
            results.add(
                ScanResult(
                    targetUrl = targetUrl,
                    vulnType = "الهدف غير مستجيب / خطأ اتصال",
                    severity = "Info",
                    evidence = "Status 0 / Exception",
                    description = "الخادم المستهدف لا يستجيب للطلبات الأساسية. يرجى مراجعة إعدادات الشبكة.",
                    remediation = "تأكد من كتابة رابط الموقع بشكل صحيح وأن الخادم مفتوح للاستقبال."
                )
            )
            return@withContext results
        }

        // Check SSL on Base
        if (rootScope.startsWith("http://", ignoreCase = true)) {
            results.add(
                ScanResult(
                    targetUrl = targetUrl,
                    vulnType = "عرضة للتنصت والاعتراض (بروتوكول HTTP غير آمن)",
                    severity = "Medium",
                    evidence = rootScope,
                    description = "يتم نقل جميع البيانات عبر الشبكة بنص واضح غير مشفر، مما يسهل على المهاجمين في الشبكة المحلية التجسس أو تنفيذ هجمات الرجل في المنتصف (MitM).",
                    remediation = "قم بتثبيت شهادة SSL/TLS وفرض تشفير HTTPS باستخدام التوجيه (301 Redirect) وملفات HSTS."
                )
            )
        }

        // Check Headers of base reply
        onStepProgress("جاري فحص هيدرات الأمان في الاستجابة (Security Headers)...", 30)
        val hStr = baseTrans.responseHeaders
        if (!hStr.contains("Content-Security-Policy", ignoreCase = true)) {
            results.add(
                ScanResult(
                    targetUrl = targetUrl,
                    vulnType = "غياب ترويسة الحماية الممتدة (Content-Security-Policy)",
                    severity = "Low",
                    evidence = "Content-Security-Policy: Missing",
                    description = "ترويسة CSP تمنع تحميل الموارد غير المصرح بها وتحد بشكل جوهري من هجمات XSS وحقن المحتوى.",
                    remediation = "قم بتهيئة ترويسة Content-Security-Policy للسماح بالموارد والسكربتات من مصادر موثوقة فقط."
                )
            )
        }
        if (!hStr.contains("X-Frame-Options", ignoreCase = true)) {
            results.add(
                ScanResult(
                    targetUrl = targetUrl,
                    vulnType = "غياب حماية الخطف بضغطات الفأرة (X-Frame-Options)",
                    severity = "Low",
                    evidence = "X-Frame-Options: Missing",
                    description = "قد يسمح هذا للمهاجم بدمج موقعك داخل<iframe> في صفحة خبيثة وخداع الزوار بالضغط على روابط خفية (Clickjacking).",
                    remediation = "أضف ترويسة 'X-Frame-Options: SAMEORIGIN' أو 'DENY' في إعدادات الخادم لمنع التأطير الخارجي."
                )
            )
        }
        if (!hStr.contains("Strict-Transport-Security", ignoreCase = true) && rootScope.startsWith("https")) {
            results.add(
                ScanResult(
                    targetUrl = targetUrl,
                    vulnType = "غياب ترويسة الأمان HSTS",
                    severity = "Low",
                    evidence = "Strict-Transport-Security: Missing",
                    description = "ترويسة Strict-Transport-Security تجبر المتصفح على استخدام الاتصال الآمن والمشفر دائماً.",
                    remediation = "قم بإضافة ترويسة Strict-Transport-Security مع مدة زمنية كافية (مثال: max-age=63072000)."
                )
            )
        }

        // Step 2: Sensitive Paths Exposure check
        onStepProgress("جاري مسح المسارات الحساسة والملفات المكشوفة...", 50)
        val pathsToCheck = SecurityPayloads.ADMIN_SENSITIVE_PATHS
        for (i in pathsToCheck.indices) {
            val path = pathsToCheck[i]
            val fullPathUrl = "$rootScope$path"
            val pathTrans = executeRequest(fullPathUrl, "GET", emptyList(), "")
            if (pathTrans.responseCode == 200) {
                // Determine severity from file types
                val (severity, desc, rem) = when {
                    path == ".env" || path == ".env.example" -> Triple(
                        "Critical",
                        "ملف إعدادات النظام الحساس للغاية (.env) مكشوف للعامة. يحتوي على كلمات مرور وملفات اتصال بقواعد البيانات ومفاتيح الـ APIs.",
                        "قم بتغيير ملفات الصلاحية فوراً، وانقل ملفات الإعدادات والـ environment variables خارج مجلد الويب العام (public_html)."
                    )
                    path.startsWith(".git") -> Triple(
                        "High",
                        "مجلد تكوين أداة Git مكشوف بالكامل. مما يتيح للمخترق تحميل الكود المصدري للتطبيق ومعرفة سجل التغييرات وبيانات المطورين.",
                        "قم بحظر الوصول لمجلد .git في ملفات تهيئة الخادم (nginx.conf أو .htaccess)."
                    )
                    path == "config.json" || path == "backup.sql" -> Triple(
                        "High",
                        "ملف نسخ احتياطي لقواعد البيانات أو ملف تهيئة معروض لمجهولين، مما يعرض بيانات المستخدمين ومصادر النظام لخطر الاختراق.",
                        "احذف الملف فوراً من الخادم أو احفظه خارج مسار دليل الويب."
                    )
                    path == "robots.txt" -> Triple(
                        "Info",
                        "ملف robots.txt متاح ومكشوف. يحتوي على استثناءات الزحف لمحركات البحث، وهو أمر طبيعي ولكن يستخدمه الهكر لتحديد مسارات لوحات التحكم.",
                        "تأكد من عدم وضع أي مسارات فائقة السرية أو مجلدات حساسة داخل ملف robots.txt العام."
                    )
                    path == "phpinfo.php" -> Triple(
                        "Medium",
                        "ملف استعلام إعدادات PHP متاح للجميع، مما يسرب تفاصيل معالج السيرفر والإصدارات المفتوحة والمكتبات الفعالة.",
                        "احذف ملف phpinfo.php المتروك في خادم الإنتاج."
                    )
                    else -> Triple(
                        "Medium",
                        "تم اكتشاف مسار حسّاس متاح للاستعراض العام ($path).",
                        "قم بفرض قيود حماية وتوثيق (Authentication) للوصول إلى هذا المسار واستخدم حظر بروتوكول HTTP للإداريين فقط."
                    )
                }

                results.add(
                    ScanResult(
                        targetUrl = targetUrl,
                        vulnType = "مسار مكشوف: /$path",
                        severity = severity,
                        evidence = "HTTP/1.1 200 OK at $fullPathUrl",
                        description = desc,
                        remediation = rem
                    )
                )
            }
        }

        // Step 3: SQL Injection (SQLi) Active Probing on parameter inputs
        onStepProgress("جاري إجراء فحص الحقن لثغرات قواعد البيانات (SQL Injection)...", 75)
        val sqlProbes = listOf("id=1", "q=test", "user=admin")
        // Build url parameter variants if targetUrl has parameters, or synthesize one for active validation
        val baseWithParams = if (targetUrl.contains("?")) targetUrl else "$rootScope?id=1"
        for (i in SecurityPayloads.SQL_INJECTIONS.indices) {
            val payload = SecurityPayloads.SQL_INJECTIONS[i]
            // We parameterize the URL
            val injectedUrl = if (baseWithParams.contains("id=")) {
                baseWithParams.replace("id=1", "id=1${payload}")
            } else {
                "$baseWithParams$payload"
            }

            val sqliTrans = executeRequest(injectedUrl, "GET", emptyList(), "")
            val sBody = sqliTrans.responseBody
            val sqlErrors = listOf(
                "SQL syntax", "mysql_fetch", "sqlite_error", "ORA-", "PostgreSQL query failed", "DriverException", "Microsoft OLE DB"
            )
            for (pattern in sqlErrors) {
                if (sBody.contains(pattern, ignoreCase = true)) {
                    results.add(
                        ScanResult(
                            targetUrl = targetUrl,
                            vulnType = "ثغرة حقن SQL برمجية (SQL Injection)",
                            severity = "Critical",
                            evidence = "Injected: $injectedUrl\nTrigger Error Signature: '$pattern'",
                            description = "التطبيق يقوم بتمرير مدخلات المستخدم مباشرة إلى محرك قواعد البيانات دون استخدام استعلامات مجهزة (Prepared Statements)، مما يتيح للمخترق قراءة وتعديل قاعدة البيانات بالكامل أو تجاوز حواجز الدخول.",
                            remediation = "استخدم دائماً Parameterized Queries أو استعلامات مجهزة (Prepared Statements) في لغة البرمجة التي تشغل التطبيق، وتجنب بناء عبارات SQL ديناميكية عبر دمج النصوص."
                        )
                    )
                    break // Found SQLi
                }
            }
        }

        // Step 4: Reflected Cross-Site Scripting (XSS) Active Probing
        onStepProgress("جاري اختبار ثغرات حقن المتصفح (Cross-Site Scripting)...", 90)
        // Check if the payload is reflected back unaltered
        val xssPayload = "<script>alert('BurpSuiteXSS')</script>"
        val xssUrl = if (baseWithParams.contains("q=")) {
            baseWithParams.replace("q=test", "q=${xssPayload}")
        } else if (baseWithParams.contains("?")) {
            "$baseWithParams&q=$xssPayload"
        } else {
            "$rootScope?q=$xssPayload"
        }

        val xssTrans = executeRequest(xssUrl, "GET", emptyList(), "")
        if (xssTrans.responseBody.contains(xssPayload)) {
            results.add(
                ScanResult(
                    targetUrl = targetUrl,
                    vulnType = "حقن سكربتات المتصفح المنعكسة (Reflected XSS)",
                    severity = "High",
                    evidence = "Payload: $xssPayload\nFound Reflected in Body.",
                    description = "المدخلات المرسلة في رابط الطلب تنعكس وتظهر مباشرة في استجابة الصفحة دون تنظيف أو تشفير (HTML Escaping). هذا يتيح للمخترقين كتابة أكواد جافا سكريبت خبيثة تُنفذ تلقائياً في متصفح الضحية.",
                    remediation = "قم بتمرير جميع المدخلات الديناميكية قبل عرضها في المتصفح عبر دالة معالجة النصوص (HTML entity encoding) أو استخدم إطارات عمل مجهزة افتراضياً بالتنظيف التلقائي."
                )
            )
        }

        onStepProgress("اكتمل الفحص بنجاح!", 100)
        return@withContext results
    }

    // --- Intruder Attack Runner ---
    suspend fun runIntruderAttack(
        targetUrl: String, // String containing §PAYLOAD§ token
        method: String,
        headers: List<Pair<String, String>>,
        body: String, // String containing §PAYLOAD§ token
        payloads: List<String>,
        onResultReceived: (IntruderResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        for (i in payloads.indices) {
            val pl = payloads[i]
            val actualUrl = targetUrl.replace("§PAYLOAD§", pl)
            val actualBody = body.replace("§PAYLOAD§", pl)
            val actualHeaders = headers.map { (k, v) -> Pair(k, v.replace("§PAYLOAD§", pl)) }

            val startTime = System.currentTimeMillis()
            var resCode = 0
            var length = 0L

            try {
                val reqBuilder = Request.Builder().url(actualUrl)
                actualHeaders.forEach { (k, v) ->
                    if (k.isNotBlank()) reqBuilder.addHeader(k, v)
                }

                if (method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)) {
                    reqBuilder.method(method, null)
                } else {
                    val mediaType = actualHeaders.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
                        ?: "application/x-www-form-urlencoded"
                    reqBuilder.method(method, actualBody.toRequestBody(mediaType.toMediaTypeOrNull()))
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    resCode = response.code
                    val respBytes = response.body?.bytes()
                    length = respBytes?.size?.toLong() ?: 0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "Intruder failed for payload: $pl", e)
                resCode = 0
                length = 0L
            }

            val duration = System.currentTimeMillis() - startTime
            val intruderItem = IntruderResult(
                payloadNum = i + 1,
                payload = pl,
                url = actualUrl,
                method = method,
                statusCode = resCode,
                length = length,
                durationMs = duration
            )

            withContext(Dispatchers.Main) {
                onResultReceived(intruderItem)
            }
        }
    }

    // --- Direct Gemini API Integration for Security Audit (Option B) ---
    suspend fun askGeminiForSecurityAudit(
        requestHeaders: String,
        requestBody: String,
        responseHeaders: String,
        responseBody: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey == "MY_GEMINI_API_KEY" || apiKey.isBlank()) {
            return@withContext "عذراً! مفتاح ذكاء اصطناعي (GEMINI_API_KEY) غير متاح حالياً في إعدادات التطبيق الخاصة بك (Secrets panel). يرجى تكوين المفتاح لإتاحة عمليات التدقيق الأمنية المتقدمة."
        }

        val prompt = """
            You are an expert penetration tester and Web Security Analyst (similar to Burp Suite security engine).
            Analyze the following real-time HTTP transaction and identify any vulnerabilities, threat risks, architectural exposures, or headers misconfigurations.
            Provide your response exclusively in HTML-free, markdown formatting, written in professional Arabic.
            Structure your report into:
            1. 🔍 الملخص الأمني والتقييم السريع للخطورة (Critical, High, Medium, Low, Secure)
            2. ⚠️ التهديدات والأنشطة المشبوهة المكتشفة (تفصيلياً مع تحديد السبب)
            3. 🛠️ كود الإثبات أو تكتيك الهجوم المتوقع (Attack Concept / PoC)
            4. ✅ إجراءات المعالجة والتامين الموصى بها (Remediation)
            
            HTTP TRANSACTION DETAILS:
            --- REQUEST ---
            Headers: $requestHeaders
            Body: $requestBody
            
            --- RESPONSE ---
            Headers: $responseHeaders
            Body: ${if (responseBody.length > 3000) responseBody.take(3000) + "\n...[Truncated for length]" else responseBody}
        """.trimIndent()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val jsonRequest = JSONObject().apply {
            val contentsArr = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val partsArr = JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    }
                    put("parts", partsArr)
                }
                put(contentObj)
            }
            put("contents", contentsArr)
        }

        try {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val reqBody = jsonRequest.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .post(reqBody)
                .build()

            geminiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errDetails = response.body?.string() ?: ""
                    Log.e(TAG, "Gemini failed: Code ${response.code}, details: $errDetails")
                    return@withContext "فشل الاتصال بـ Gemini API: رمز الاستجابة ${response.code}. تفاصيل الخطأ: $errDetails"
                }

                val bodyStr = response.body?.string() ?: ""
                val jsonObj = JSONObject(bodyStr)
                val candidates = jsonObj.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val first = candidates.getJSONObject(0)
                    val contentObj = first.getJSONObject("content")
                    val parts = contentObj.getJSONArray("parts")
                    if (parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).getString("text")
                    }
                }
                return@withContext "لم يُرجع نموذج الذكاء الاصطناعي أي نتيجة تدقيق متوقعة."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call exception", e)
            return@withContext "خطأ أثناء محاولة المعالجة عبر الذكاء الاصطناعي: ${e.localizedMessage}"
        }
    }
}
