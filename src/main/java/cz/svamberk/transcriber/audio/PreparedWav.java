package cz.svamberk.transcriber.audio;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Reads only RIFF/RF64 chunk headers; does not load a lecture into memory or invoke FFmpeg. */
public final class PreparedWav {
    private PreparedWav() {}

    public static boolean matches(Path audio, double duration) {
        if (!Files.isRegularFile(audio, NOFOLLOW_LINKS)) return false;
        try (var in = new RandomAccessFile(audio.toFile(), "r")) {
            String kind = tag(in);
            if (!kind.equals("RIFF") && !kind.equals("RF64")) return false;
            long riffSize = uint(in);
            if (!tag(in).equals("WAVE")) return false;
            boolean format = false;
            long dataSize64 = -1;
            while (in.getFilePointer() + 8 <= in.length()) {
                String chunk = tag(in);
                long size = uint(in);
                long start = in.getFilePointer();
                if (chunk.equals("ds64") && kind.equals("RF64")) {
                    if (size < 28) return false;
                    riffSize = littleLong(in);
                    dataSize64 = littleLong(in);
                    if (riffSize < 0 || dataSize64 < 0) return false;
                }
                if (chunk.equals("data") && size == 0xffffffffL) size = dataSize64;
                if (size < 0 || size > in.length() - start) return false;
                if (chunk.equals("fmt ")) {
                    if (size < 16) return false;
                    format = ushort(in) == 1 && ushort(in) == 1 && uint(in) == 16000
                            && uint(in) == 32000 && ushort(in) == 2 && ushort(in) == 16;
                }
                if (chunk.equals("data")) {
                    return format && riffSize == in.length() - 8 && size % 2 == 0
                            && Math.abs(size / 32000.0 - duration) <= 1.0 / 16000;
                }
                in.seek(start + size + (size & 1));
            }
        } catch (IOException e) { return false; }
        return false;
    }

    private static String tag(RandomAccessFile in) throws IOException {
        byte[] bytes = new byte[4];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static int ushort(RandomAccessFile in) throws IOException { return Short.toUnsignedInt(Short.reverseBytes(in.readShort())); }
    private static long uint(RandomAccessFile in) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt())); }
    private static long littleLong(RandomAccessFile in) throws IOException { return Long.reverseBytes(in.readLong()); }
}
