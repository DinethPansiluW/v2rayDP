package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.automation.VpnAutomationManager
import com.example.data.AppDatabase
import com.example.data.PreferencesRepository

class V2RayApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var preferencesRepository: PreferencesRepository
        private set

    lateinit var automationManager: VpnAutomationManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        preferencesRepository = PreferencesRepository(this)
        automationManager = VpnAutomationManager(this)

        createNotificationChannels()
        automationManager.initialize()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vpnChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_VPN,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active v2rayDP VPN connection and speed statistics"
                setShowBadge(false)
            }

            val automationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_AUTOMATION,
                "Automation Alerts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when VPN connects or disconnects automatically"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.apply {
                createNotificationChannel(vpnChannel)
                createNotificationChannel(automationChannel)
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_VPN = "v2raydp_vpn_status"
        const val NOTIFICATION_CHANNEL_AUTOMATION = "v2raydp_automation"

        lateinit var instance: V2RayApplication
            private set

        fun get(): V2RayApplication = instance
    }
}
