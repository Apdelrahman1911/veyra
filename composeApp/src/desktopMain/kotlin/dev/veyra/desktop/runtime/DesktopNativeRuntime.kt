package dev.veyra.desktop.runtime

import java.net.JarURLConnection
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile

/** Call before touching JNA. Test callers MUST inject a repository-owned data directory. */
data class DesktopRuntimeConfig(val dataDirectory: Path, val silentTestOutput: Boolean = false)

data class PreparedDesktopRuntime(val directory: Path, val library: Path, val caBundle: Path, val platform: String)

/** No PATH/system-mpv fallback, executable download, or global temp/cache writes. */
object DesktopNativeRuntime {
    const val VERSION = "0.5.3"
    private const val CA_HASH = "427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285"
    private val pins = mapOf(
        "linux-x64" to "d1169c752a587f8d96311e773cda057ca8563fc5c8cb5088f299d61c95d0a15a",
        "windows-x64" to "228642a9a2f22ab4e8bf8096d935e1bbc2493310d8a47a4a321aef9378cb6c26",
        "windows-arm64" to "bc6222e61b13b890665c3a019d9058a10cfb7f7a815a20905536b13b6b340229",
        "macos-x64" to "7c9bda187618d851cc258378126d1afdb48202561b476e9aaaab45947406edf0",
        "macos-arm64" to "1ed2def130d751d5c14add0995726670de6426bc4cf868353e963a5c363a656f",
    )

    fun platform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String {
        val family = when {
            os.lowercase(Locale.ROOT).startsWith("windows") -> "windows"
            os.lowercase(Locale.ROOT).startsWith("mac") || os.equals("darwin", true) -> "macos"
            os.equals("linux", true) -> "linux"
            else -> error("Unsupported desktop operating system")
        }
        val cpu = when (arch.lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x64"
            "arm64", "aarch64" -> "arm64"
            else -> error("Unsupported desktop architecture")
        }
        return "$family-$cpu".also { require(it in pins) { "No bundled audio runtime for this architecture" } }
    }

    fun initializeProcessPaths(dataDirectory: Path) {
        val root = dataDirectory.toAbsolutePath().normalize()
        privateDirectory(root)
        val temporary = root.resolve("tmp")
        privateDirectory(temporary)
        val jna = root.resolve("runtime/jna")
        privateDirectory(jna)
        System.setProperty("java.io.tmpdir", temporary.toString())
        System.setProperty("jna.tmpdir", jna.toString())
        System.setProperty("jna.nosys", "true")
    }

    @Synchronized
    fun prepare(config: DesktopRuntimeConfig): PreparedDesktopRuntime {
        initializeProcessPaths(config.dataDirectory)
        val target = platform()
        val classLoader = DesktopNativeRuntime::class.java.classLoader
        val manifestName = "mpv-natives-$target.txt"
        val manifestUrl = classLoader.getResource(manifestName)
            ?: error("Bundled audio runtime is missing ($target)")
        require(manifestUrl.protocol == "jar") { "Native runtime must remain in its verified original JAR" }
        val connection = manifestUrl.openConnection() as JarURLConnection
        connection.useCaches = false
        require(connection.jarFileURL.protocol == "file") { "Native runtime must be installed locally" }
        val archive = Path.of(connection.jarFileURL.toURI())
        require(sha256(archive) == pins.getValue(target)) { "Bundled audio runtime integrity check failed" }

        val parent = config.dataDirectory.toAbsolutePath().normalize().resolve("runtime/mpv/$VERSION")
        privateDirectory(parent)
        val destination = parent.resolve(target)
        val lockPath = parent.resolve("$target.lock")
        require(!Files.isSymbolicLink(lockPath)) { "Unsafe runtime lock path" }
        FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
            channel.lock().use {
                JarFile(archive.toFile()).use { jar ->
                    val names = jar.getInputStream(jar.getJarEntry(manifestName)).bufferedReader().use { it.readLines() }
                        .map(String::trim).filter(String::isNotEmpty)
                    require(names.isNotEmpty() && names.distinct().size == names.size)
                    require(names.all(::safeNativeFilename)) { "Invalid native library manifest" }
                    val validExisting = Files.isDirectory(destination, NOFOLLOW_LINKS) && names.all { name ->
                        val file = destination.resolve(name)
                        Files.isRegularFile(file, NOFOLLOW_LINKS) && jar.getInputStream(jar.getJarEntry(name)).use { input ->
                            sha256(input) == sha256(file)
                        }
                    }
                    if (!validExisting) {
                        // Never overwrite possibly loaded/corrupted code. Leave it for explicit recovery.
                        require(!Files.exists(destination, NOFOLLOW_LINKS)) { "Audio runtime is damaged; remove the Veyra runtime cache and restart" }
                        val staging = Files.createTempDirectory(parent, ".$target-")
                        privateDirectory(staging)
                        try {
                            names.forEach { name ->
                                val entry = jar.getJarEntry(name) ?: error("Incomplete native archive")
                                require(!entry.isDirectory && entry.size in 1..150_000_000)
                                val output = staging.resolve(name)
                                jar.getInputStream(entry).use { input -> Files.copy(input, output) }
                                setPrivatePermissions(output, executable = false)
                            }
                            Files.move(staging, destination, ATOMIC_MOVE)
                        } finally {
                            if (Files.exists(staging, NOFOLLOW_LINKS)) {
                                Files.list(staging).use { files -> files.forEach(Files::deleteIfExists) }
                                Files.deleteIfExists(staging)
                            }
                        }
                    }
                    val libraryName = when {
                        target.startsWith("linux") -> "libmpv.so.2"
                        target.startsWith("macos") -> listOf("libmpv.2.dylib", "libmpv.dylib").firstOrNull { it in names }
                        else -> names.singleOrNull { it.matches(Regex("(?i)(lib)?mpv(-[0-9]+)?\\.dll")) }
                    } ?: error("Audio runtime has no recognized libmpv library")
                    require(libraryName in names)
                    return PreparedDesktopRuntime(destination, destination.resolve(libraryName), prepareCaBundle(parent), target)
                }
            }
        }
    }

    internal fun safeNativeFilename(name: String): Boolean =
        name.length in 1..180 && name.matches(Regex("[A-Za-z0-9_.+-]+")) && !name.startsWith('.') &&
            (name.contains(".so") || name.endsWith(".dll", true) || name.endsWith(".dylib"))

    private fun prepareCaBundle(parent: Path): Path {
        val bytes = DesktopNativeRuntime::class.java.getResourceAsStream("/dev/veyra/desktop/runtime/ca-bundle.crt")
            ?.use { it.readBytes() } ?: error("Bundled TLS certificate roots are missing")
        require(sha256(bytes) == CA_HASH) { "TLS certificate roots failed integrity verification" }
        val destination = parent.resolve("mozilla-$CA_HASH.crt")
        if (!Files.exists(destination, NOFOLLOW_LINKS)) {
            val stage = Files.createTempFile(parent, ".ca-", ".part")
            try {
                Files.write(stage, bytes)
                Files.move(stage, destination, ATOMIC_MOVE)
            } finally { Files.deleteIfExists(stage) }
        }
        require(Files.isRegularFile(destination, NOFOLLOW_LINKS) && sha256(destination) == CA_HASH)
        return destination
    }

    fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        sha256(input)
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun privateDirectory(path: Path) {
        DesktopProcessContext.rejectSymbolicAncestors(path)
        Files.createDirectories(path)
        require(Files.isDirectory(path, NOFOLLOW_LINKS))
        setPrivatePermissions(path, executable = true)
    }

    private fun setPrivatePermissions(path: Path, executable: Boolean) {
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (executable) "rwx------" else "rw-------"))
        }
    }
}
