package com.example.ui

object SocialLinks {
    const val GITHUB_LABEL = "GitHub"
    const val GITHUB_HANDLE = "DinethPansiluW"
    const val GITHUB_URL = "https://github.com/DinethPansiluW"

    const val FACEBOOK_LABEL = "Facebook"
    const val FACEBOOK_HANDLE = "DinethPansiluW"
    const val FACEBOOK_URL = "https://www.facebook.com/DinethPansiluW"

    // WhatsApp: username does not work in WhatsApp links. Use https://wa.me/<PHONE_NUMBER>
    // with the phone number in international format and digits only (e.g., "94771234567").
    const val WHATSAPP_LABEL = "WhatsApp"
    const val WHATSAPP_HANDLE = "DinethPansiluW"
    const val WHATSAPP_NUMBER = ""
    val WHATSAPP_URL: String? = if (WHATSAPP_NUMBER.isNotBlank()) {
        "https://wa.me/${WHATSAPP_NUMBER.filter { it.isDigit() }}"
    } else {
        null
    }

    const val TELEGRAM_LABEL = "Telegram"
    const val TELEGRAM_HANDLE = "DinethPansilu"
    const val TELEGRAM_URL = "https://t.me/DinethPansilu"

    const val INSTAGRAM_LABEL = "Instagram"
    const val INSTAGRAM_HANDLE = "DinethPansiluW"
    const val INSTAGRAM_URL = "https://www.instagram.com/DinethPansiluW"
}
