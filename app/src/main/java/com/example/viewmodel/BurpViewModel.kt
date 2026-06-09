package com.example.viewmodel

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BurpApplication
import com.example.data.HttpTransaction
import com.example.data.ScanResult
import com.example.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

data class RepeaterTab(
    val id: Int,
    val name: String,
    val url: String = "https://httpbin.org/get",
    val method: String = "GET",
    val headers: List<Pair<String, String>> = listOf(
        Pair("User-Agent", "BurpSuiteMobile/1.0"),
        Pair("Accept", "*/*")
    ),
    val body: String = "",
    val responseCode: Int = 0,
    val responseHeaders: String = "",
    val responseBody: String = "",
    val isLoading: Boolean = false,
    val durationMs: Long = 0L
)

class BurpViewModel(application: Application) : AndroidViewModel(application) {
    private val appDao = BurpApplication.database.appDao()

    // --- Proxy Interceptor Flows ---
    val isInterceptEnabled = NetworkEngine.isInterceptEnabled
    val interceptQueue = NetworkEngine.interceptQueue

    // Proxy transaction log
    val proxyTransactions = appDao.getAllTransactions()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Scanner Flows ---
    val scanResults = appDao.getAllScanResults()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress = _scanProgress.asStateFlow()

    private val _scanProgressLog = MutableStateFlow<List<String>>(emptyList())
    val scanProgressLog = _scanProgressLog.asStateFlow()

    var scannerTargetUrl = MutableStateFlow("https://httpbin.org")

    // --- Intruder States ---
    var intruderTargetUrl = MutableStateFlow("https://httpbin.org/get?id=§PAYLOAD§")
    var intruderMethod = MutableStateFlow("GET")
    var intruderHeaders = MutableStateFlow(listOf(Pair("User-Agent", "BurpSuiteMobile/1.0")))
    var intruderBody = MutableStateFlow("")
    var intruderPayloadClassification = MutableStateFlow("SQL Injection") // SQL Injection, XSS, Path Traversal, Custom
    var intruderCustomList = MutableStateFlow("admin\nroot\nbypass\ntest")
    
    private val _isIntruderRunning = MutableStateFlow(false)
    val isIntruderRunning = _isIntruderRunning.asStateFlow()

    private val _intruderItems = MutableStateFlow<List<IntruderResult>>(emptyList())
    val intruderItems = _intruderItems.asStateFlow()

    // --- Repeater States ---
    private val _repeaterTabs = MutableStateFlow<List<RepeaterTab>>(
        listOf(
            RepeaterTab(id = 1, name = "طلب مكرر 1"),
            RepeaterTab(id = 2, name = "طلب مكرر 2")
        )
    )
    val repeaterTabs = _repeaterTabs.asStateFlow()

    private val _activeRepeaterTabId = MutableStateFlow(1)
    val activeRepeaterTabId = _activeRepeaterTabId.asStateFlow()

    // --- AI Co-pilot States ---
    private val _aiReport = MutableStateFlow<String?>(null)
    val aiReport = _aiReport.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading = _isAiLoading.asStateFlow()

    private val _aiSelectedTransaction = MutableStateFlow<HttpTransaction?>(null)
    val aiSelectedTransaction = _aiSelectedTransaction.asStateFlow()

    // --- Tool Utilities States ---
    var toolInput = MutableStateFlow("")
    private val _toolOutput = MutableStateFlow("")
    val toolOutput = _toolOutput.asStateFlow()

    init {
        // Clear queue initially
        NetworkEngine.setInterceptEnabled(false)
    }

    // --- Proxy Actions ---
    fun toggleIntercept() {
        viewModelScope.launch {
            val nextState = !isInterceptEnabled.value
            NetworkEngine.setInterceptEnabled(nextState)
        }
    }

    fun forwardInterceptedRequest(id: String, method: String, url: String, headersStr: String, body: String) {
        viewModelScope.launch {
            val headersList = parseHeadersString(headersStr)
            NetworkEngine.forwardInterceptedRequest(id, method, url, headersList, body)
        }
    }

    fun dropInterceptedRequest(id: String) {
        viewModelScope.launch {
            NetworkEngine.dropInterceptedRequest(id)
        }
    }

    // Helper to log requests completed in the app (WebView, manual tools, etc.)
    fun recordTransaction(transaction: HttpTransaction) {
        viewModelScope.launch(Dispatchers.IO) {
            appDao.insertTransaction(transaction)
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            appDao.clearTransactions()
        }
    }

    fun deleteTransaction(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            appDao.deleteTransaction(id)
        }
    }

    // --- Automated Scanning ---
    fun startTargetSecurityScan() {
        val target = scannerTargetUrl.value.trim()
        if (target.isBlank()) return

        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0
            _scanProgressLog.value = listOf("بدء تهيئة أجهزة مسح الثغرات لـ: $target...")
            appDao.clearScanResults()

            val foundScans = NetworkEngine.runAutomatedScan(target) { step, percentage ->
                _scanProgress.value = percentage
                _scanProgressLog.value = _scanProgressLog.value + step
            }

            // Save results to database so UI gets them reactively
            withContext(Dispatchers.IO) {
                for (res in foundScans) {
                    appDao.insertScanResult(res)
                    
                    // Also record an HTTP history event of the audit trace
                    appDao.insertTransaction(
                        HttpTransaction(
                            url = res.targetUrl,
                            method = "SCAN",
                            requestHeaders = "Host: ${target}\nScanType: Active Probe",
                            requestBody = "Payload Trigger: ${res.evidence}",
                            responseCode = 200,
                            responseHeaders = "Security-Audit-Impact: ${res.severity}",
                            responseBody = "Finding: ${res.vulnType}\nDescription: ${res.description}",
                            durationMs = 0L,
                            severityRating = res.severity,
                            vulnDetails = "Vulnerablity Discovered during Automated Security Scan: ${res.vulnType}"
                        )
                    )
                }
            }

            _isScanning.value = false
            _scanProgressLog.value = _scanProgressLog.value + "اكتمل المسح! تم العثور على (${foundScans.size}) ثغرات محتملة."
        }
    }

    fun clearScanStats() {
        viewModelScope.launch(Dispatchers.IO) {
            appDao.clearScanResults()
            _scanProgressLog.value = emptyList()
            _scanProgress.value = 0
        }
    }

    // --- Repeater Tab Operations ---
    fun changeActiveRepeaterTab(id: Int) {
        _activeRepeaterTabId.value = id
    }

    fun addRepeaterTab(fromTransaction: HttpTransaction? = null) {
        val nextId = (_repeaterTabs.value.maxOfOrNull { it.id } ?: 0) + 1
        var newTab = RepeaterTab(id = nextId, name = "طلب مكرر $nextId")
        if (fromTransaction != null) {
            newTab = newTab.copy(
                url = fromTransaction.url,
                method = fromTransaction.method,
                headers = parseHeadersString(fromTransaction.requestHeaders),
                body = fromTransaction.requestBody
            )
        }
        _repeaterTabs.value = _repeaterTabs.value + newTab
        _activeRepeaterTabId.value = nextId
    }

    fun removeRepeaterTab(id: Int) {
        if (_repeaterTabs.value.size <= 1) return // Keep at least one
        val list = _repeaterTabs.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list.removeAt(index)
            _repeaterTabs.value = list
            if (_activeRepeaterTabId.value == id) {
                _activeRepeaterTabId.value = list.first().id
            }
        }
    }

    fun updateActiveTabDetails(url: String, method: String, headersStr: String, body: String) {
        val activeId = _activeRepeaterTabId.value
        val parsedHeaders = parseHeadersString(headersStr)
        _repeaterTabs.value = _repeaterTabs.value.map {
            if (it.id == activeId) {
                it.copy(url = url, method = method, headers = parsedHeaders, body = body)
            } else it
        }
    }

    fun executeRepeaterRequest() {
        val activeId = _activeRepeaterTabId.value
        val currentTab = _repeaterTabs.value.find { it.id == activeId } ?: return

        // Set Loading
        _repeaterTabs.value = _repeaterTabs.value.map {
            if (it.id == activeId) it.copy(isLoading = true) else it
        }

        viewModelScope.launch {
            val transResult = NetworkEngine.executeRequest(
                url = currentTab.url,
                method = currentTab.method,
                headers = currentTab.headers,
                body = currentTab.body
            )

            // Save to Proxy Transaction Log so we see it in History
            recordTransaction(transResult)

            _repeaterTabs.value = _repeaterTabs.value.map {
                if (it.id == activeId) {
                    it.copy(
                        isLoading = false,
                        responseCode = transResult.responseCode,
                        responseHeaders = transResult.responseHeaders,
                        responseBody = transResult.responseBody,
                        durationMs = transResult.durationMs
                    )
                } else it
            }
        }
    }

    // --- Intruder Operations ---
    fun runIntruderFuzzing() {
        val target = intruderTargetUrl.value.trim()
        if (target.isBlank() || !target.contains("§PAYLOAD§")) {
            _intruderItems.value = listOf(
                IntruderResult(
                    payloadNum = 0,
                    payload = "Error",
                    url = target,
                    method = intruderMethod.value,
                    statusCode = 0,
                    length = 0,
                    durationMs = 0,
                    matchesSignature = true
                )
            )
            return
        }

        val method = intruderMethod.value
        val headers = intruderHeaders.value
        val body = intruderBody.value
        val pType = intruderPayloadClassification.value

        // Resolve payload lists
        val payloads = when (pType) {
            "SQL Injection" -> SecurityPayloads.SQL_INJECTIONS
            "XSS" -> SecurityPayloads.XSS_PAYLOADS
            "Path Traversal" -> SecurityPayloads.PATH_TRAVERSAL
            else -> intruderCustomList.value.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        }

        viewModelScope.launch {
            _isIntruderRunning.value = true
            _intruderItems.value = emptyList()

            NetworkEngine.runIntruderAttack(target, method, headers, body, payloads) { singleResult ->
                _intruderItems.value = _intruderItems.value + singleResult
                
                // Track into general logs as well
                recordTransaction(
                    HttpTransaction(
                        url = singleResult.url,
                        method = singleResult.method,
                        requestHeaders = "Host: Fuzzer-Intruder\nPayload: ${singleResult.payload}",
                        requestBody = if (body.contains("§PAYLOAD§")) body.replace("§PAYLOAD§", singleResult.payload) else body,
                        responseCode = singleResult.statusCode,
                        responseHeaders = "Content-Length: ${singleResult.length}\nDuration: ${singleResult.durationMs}ms",
                        responseBody = "[Intruder Fuzz Output - Truncated for system memory]",
                        durationMs = singleResult.durationMs,
                        severityRating = "Info",
                        vulnDetails = "Fuzzer Probe: Payload '${singleResult.payload}' completed"
                    )
                )
            }

            _isIntruderRunning.value = false
        }
    }

    // --- AI Audit Co-Pilot ---
    fun sendToAiAudit(transaction: HttpTransaction) {
        _aiSelectedTransaction.value = transaction
        _aiReport.value = null
        _isAiLoading.value = true

        viewModelScope.launch {
            val report = NetworkEngine.askGeminiForSecurityAudit(
                requestHeaders = transaction.requestHeaders,
                requestBody = transaction.requestBody,
                responseHeaders = transaction.responseHeaders,
                responseBody = transaction.responseBody
            )
            _aiReport.value = report
            _isAiLoading.value = false
        }
    }

    // --- Encoding / Decoder Work ---
    fun runDecoderAction(type: String) {
        val input = toolInput.value
        if (input.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            val out = when (type) {
                "URL Encode" -> try { URLEncoder.encode(input, StandardCharsets.UTF_8.toString()) } catch (e: Exception) { "Error: ${e.message}" }
                "URL Decode" -> try { URLDecoder.decode(input, StandardCharsets.UTF_8.toString()) } catch (e: Exception) { "Error: ${e.message}" }
                "Base64 Encode" -> try { Base64.encodeToString(input.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) } catch (e: Exception) { "Error: ${e.message}" }
                "Base64 Decode" -> try { String(Base64.decode(input, Base64.DEFAULT), StandardCharsets.UTF_8) } catch (e: Exception) { "Error: ${e.message}" }
                "Hex Encode" -> try { input.toByteArray(StandardCharsets.UTF_8).joinToString("") { String.format("%02x", it) } } catch (e: Exception) { "Error: ${e.message}" }
                "MD5 Hash" -> try { hashString(input, "MD5") } catch (e: Exception) { "Error: ${e.message}" }
                "SHA-256 Hash" -> try { hashString(input, "SHA-256") } catch (e: Exception) { "Error: ${e.message}" }
                else -> input
            }
            _toolOutput.value = out
        }
    }

    private fun hashString(input: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val bytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    // --- Formatting Helper Parsers ---
    fun parseHeadersString(headersStr: String): List<Pair<String, String>> {
        if (headersStr.isBlank()) return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        val lines = headersStr.split("\n")
        for (line in lines) {
            val idx = line.indexOf(":")
            if (idx != -1) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) {
                    list.add(Pair(key, value))
                }
            } else if (line.isNotBlank()) {
                list.add(Pair(line.trim(), ""))
            }
        }
        return list
    }
}
