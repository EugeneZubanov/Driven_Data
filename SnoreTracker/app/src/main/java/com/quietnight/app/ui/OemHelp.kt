package com.quietnight.app.ui

/**
 * Extra steps some phone makers need on top of Android's battery optimisation setting, so the app
 * is not closed in the night. Menu names vary between versions, so the steps stay general.
 */
object OemHelp {
    data class Steps(val brand: String, val steps: List<String>)

    private val generic = listOf(
        "Open Settings › Apps › Quiet Night › Battery.",
        "Choose Unrestricted (or turn off battery optimisation).",
    )

    fun stepsFor(manufacturer: String): Steps = when (manufacturer.trim().lowercase()) {
        "samsung" -> Steps("Samsung", listOf(
            "Settings › Apps › Quiet Night › Battery: choose Unrestricted.",
            "Settings › Battery › Background usage limits: make sure Quiet Night is not in Sleeping or Deep sleeping apps.",
        ))
        "xiaomi", "redmi", "poco" -> Steps("Xiaomi", listOf(
            "Settings › Apps › Manage apps › Quiet Night › Battery saver: choose No restrictions.",
            "In the same screen turn on Autostart.",
            "In recent apps, pull Quiet Night down to lock it so cleaning does not close it.",
        ))
        "huawei", "honor" -> Steps(if (manufacturer.equals("honor", true)) "Honor" else "Huawei", listOf(
            "Settings › Battery › App launch: find Quiet Night and switch it to Manage manually.",
            "Allow Auto-launch, Secondary launch and Run in background.",
        ))
        "oneplus", "oppo", "realme" -> Steps(manufacturer.trim().replaceFirstChar { it.uppercase() }.let { if (it == "Oneplus") "OnePlus" else it }, listOf(
            "Settings › Apps › Quiet Night › Battery usage: allow background activity.",
            "Settings › Battery › More settings: turn off Optimise battery use for Quiet Night if listed.",
            "In recent apps, lock Quiet Night so it is not cleared.",
        ))
        "google" -> Steps("Pixel", listOf(
            "Settings › Apps › Quiet Night › App battery usage: choose Unrestricted.",
            "Nothing else is usually needed on a Pixel.",
        ))
        else -> Steps("your phone", generic)
    }
}
