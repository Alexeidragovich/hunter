package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "http_transactions")
data class HttpTransaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val method: String,
    val requestHeaders: String, // format: "Header1: Value1\nHeader2: Value2"
    val requestBody: String,
    val responseCode: Int,
    val responseHeaders: String, // format: "Header1: Value1\nHeader2: Value2"
    val responseBody: String,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val severityRating: String = "Info", // Safe, Info, Low, Medium, High, Critical
    val vulnDetails: String = "",
    val isFavorite: Boolean = false
)

@Entity(tableName = "scan_results")
data class ScanResult(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val targetUrl: String,
    val timestamp: Long = System.currentTimeMillis(),
    val vulnType: String, // e.g. SQL Injection, Missing Security Header, Env File Exposed
    val severity: String, // Info, Low, Medium, High, Critical
    val evidence: String, // Specific payload or matching header line
    val description: String,
    val remediation: String
)

@Dao
interface AppDao {
    @Query("SELECT * FROM http_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<HttpTransaction>>

    @Query("SELECT * FROM http_transactions WHERE id = :id")
    suspend fun getTransactionById(id: Int): HttpTransaction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: HttpTransaction): Long

    @Query("DELETE FROM http_transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Int)

    @Query("DELETE FROM http_transactions")
    suspend fun clearTransactions()

    @Query("SELECT * FROM scan_results ORDER BY timestamp DESC")
    fun getAllScanResults(): Flow<List<ScanResult>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanResult(result: ScanResult): Long

    @Query("DELETE FROM scan_results")
    suspend fun clearScanResults()
}

@Database(entities = [HttpTransaction::class, ScanResult::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
}
