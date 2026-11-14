package dev.pencilstub.mauvelauncher

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform