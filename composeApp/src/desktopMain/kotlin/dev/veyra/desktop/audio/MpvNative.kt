package dev.veyra.desktop.audio

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.WString
import dev.veyra.desktop.runtime.PreparedDesktopRuntime
import java.nio.charset.StandardCharsets.UTF_8

/** Minimal libmpv 2.x ABI. Supported Veyra runtimes are all 64-bit. */
internal interface MpvNative : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_set_property(handle: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_command(handle: Pointer, args: Pointer): Int
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(handle: Pointer)
    fun mpv_request_log_messages(handle: Pointer, minimumLevel: String): Int
    fun mpv_free(pointer: Pointer)
    fun mpv_terminate_destroy(handle: Pointer)

    companion object {
        private val libraries = mutableMapOf<String, MpvNative>()
        // Keep the Windows directory cookie alive for the entire loaded-library lifetime.
        private val directoryCookies = mutableListOf<Pointer>()
        private val darwinLocale by lazy { Native.load("c", DarwinNumericLocale::class.java) }

        @Synchronized
        fun initializeNumericLocale(platform: String) {
            if (platform.startsWith("macos-")) {
                // libmpv client.h requires LC_NUMERIC=C for mpv_create and its lifetime.
                // Darwin locale.h defines LC_NUMERIC=4. Initialize before each owner.
                check(darwinLocale.setlocale(4, "C") == "C") { "Could not initialize native numeric locale" }
            }
        }

        @Synchronized
        fun load(runtime: PreparedDesktopRuntime): MpvNative {
            libraries[runtime.library.toString()]?.let { return it }
            require(Native.POINTER_SIZE == 8) { "Audio runtime requires a 64-bit JVM" }
            if (runtime.platform.startsWith("windows")) {
                val kernel = Native.load("kernel32", WindowsLibrarySearch::class.java)
                check(kernel.SetDefaultDllDirectories(0x00001000)) { "Could not secure DLL search path" }
                directoryCookies += checkNotNull(kernel.AddDllDirectory(WString(runtime.directory.toString()))) { "Could not register bundled DLL directory" }
            }
            return Native.load(runtime.library.toString(), MpvNative::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")).also {
                libraries[runtime.library.toString()] = it
            }
        }
    }
}

internal interface DarwinNumericLocale : Library {
    fun setlocale(category: Int, locale: String): String?
}

internal interface WindowsLibrarySearch : com.sun.jna.win32.StdCallLibrary {
    fun SetDefaultDllDirectories(flags: Int): Boolean
    fun AddDllDirectory(directory: WString): Pointer?
}

internal fun MpvNative.string(handle: Pointer, name: String): String? {
    val pointer = mpv_get_property_string(handle, name) ?: return null
    return try { pointer.getString(0, "UTF-8") } finally { mpv_free(pointer) }
}

internal fun MpvNative.command(handle: Pointer, vararg args: String): Int =
    mpv_command(handle, StringArray(args, "UTF-8"))

/** Native node array avoids libmpv's comma/escape mini-language for HTTP header values. */
internal fun MpvNative.stringList(handle: Pointer, name: String, values: List<String>): Int {
    require(values.size <= 128)
    val strings = values.map { value ->
        val bytes = value.toByteArray(UTF_8)
        Memory((bytes.size + 1).toLong()).also { it.write(0, bytes, 0, bytes.size); it.setByte(bytes.size.toLong(), 0) }
    }
    val elements = Memory((maxOf(1, values.size) * 16).toLong()).also { it.clear() }
    strings.forEachIndexed { i, value ->
        elements.setPointer(i * 16L, value)
        elements.setInt(i * 16L + 8, 1) // MPV_FORMAT_STRING
    }
    val list = Memory(24).also {
        it.clear(); it.setInt(0, values.size); it.setPointer(8, elements); it.setPointer(16, null)
    }
    val node = Memory(16).also { it.clear(); it.setPointer(0, list); it.setInt(8, 7) } // MPV_FORMAT_NODE_ARRAY
    return mpv_set_property(handle, name, 6, node).also {
        // Keep every allocation reachable until libmpv copied the option synchronously.
        java.lang.ref.Reference.reachabilityFence(strings)
        java.lang.ref.Reference.reachabilityFence(elements)
        java.lang.ref.Reference.reachabilityFence(list)
    }
}
