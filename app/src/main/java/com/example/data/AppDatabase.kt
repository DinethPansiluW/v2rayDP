package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.AppLog
import com.example.model.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [ServerProfile::class, AppLog::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun appLogDao(): AppLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "v2raydp_database.db"
                )
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Pre-populate with initial demonstration VLESS & VMess profiles
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getInstance(context).serverProfileDao()

                            val vlessProfile = ServerProfile(
                                name = "Cloudflare VLESS CDN Gateway",
                                protocol = "vless",
                                address = "104.16.132.229",
                                port = 443,
                                uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
                                alterId = 0,
                                security = "none",
                                network = "ws",
                                headerType = "none",
                                host = "vless-edge.v2raydp.internal",
                                path = "/vless-stream",
                                tls = "tls",
                                sni = "vless-edge.v2raydp.internal",
                                alpn = "h2,http/1.1",
                                isSelected = true,
                                pingLatencyMs = 38,
                                lastPingTimestamp = System.currentTimeMillis()
                            )

                            val vmessProfile = ServerProfile(
                                name = "US VMess High-Speed Node",
                                protocol = "vmess",
                                address = "198.51.100.45",
                                port = 443,
                                uuid = "a1b2c3d4-e5f6-7890-1234-56789abcdef0",
                                alterId = 0,
                                security = "auto",
                                network = "ws",
                                headerType = "none",
                                host = "vmess.v2raydp.net",
                                path = "/v2ray-stream",
                                tls = "tls",
                                sni = "vmess.v2raydp.net",
                                alpn = "h2,http/1.1",
                                isSelected = false,
                                pingLatencyMs = 95,
                                lastPingTimestamp = System.currentTimeMillis()
                            )

                            dao.insertProfile(vlessProfile)
                            dao.insertProfile(vmessProfile)
                        }
                    }
                })
                .build().also { INSTANCE = it }
            }
        }
    }
}
