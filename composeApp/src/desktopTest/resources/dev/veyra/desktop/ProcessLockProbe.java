import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Fresh-process kernel-lock probe; no Veyra in-memory registry can mask a lost native lock. */
class ProcessLockProbe {
    public static void main(String[] arguments) throws Exception {
        int status;
        try (FileChannel channel = FileChannel.open(Path.of(arguments[0]),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            status = lock == null ? 3 : 0;
            if (lock != null) lock.release();
        }
        System.exit(status);
    }
}
