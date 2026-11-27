import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Marks existing UTF-8 names without recompressing files or losing Unix executable permissions. */
public class NormalizeZipUtf8 {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one source ZIP path");
        Path archive = Path.of(args[0]);
        byte[] data = Files.readAllBytes(archive);
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int at = data.length - 22; at >= Math.max(0, data.length - 65_557); at--) {
            if (bytes.getInt(at) == 0x06054b50 && at + 22 + u16(bytes, at + 20) == data.length) {
                end = at;
                break;
            }
        }
        if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0
                || u16(bytes, end + 8) != u16(bytes, end + 10)
                || u16(bytes, end + 10) == 65_535) {
            throw new IllegalArgumentException("Expected a non-split, non-ZIP64 source archive");
        }
        int position = Math.toIntExact(Integer.toUnsignedLong(bytes.getInt(end + 16)));
        int count = u16(bytes, end + 10);
        for (int index = 0; index < count; index++) {
            if (position < 0 || position > end - 46 || bytes.getInt(position) != 0x02014b50) {
                throw new IllegalArgumentException("Invalid ZIP central directory");
            }
            int nameLength = u16(bytes, position + 28);
            int extraLength = u16(bytes, position + 30);
            int commentLength = u16(bytes, position + 32);
            int next = Math.addExact(position, 46 + nameLength + extraLength + commentLength);
            if (next > end) throw new IllegalArgumentException("Invalid ZIP entry length");
            // Fail rather than relabel non-UTF-8 bytes as UTF-8.
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data, position + 46, nameLength));
            StandardCharsets.UTF_8.newDecoder().decode(
                    ByteBuffer.wrap(data, position + 46 + nameLength + extraLength, commentLength));
            int local = Math.toIntExact(Integer.toUnsignedLong(bytes.getInt(position + 42)));
            if (local < 0 || local > position - 30 || bytes.getInt(local) != 0x04034b50
                    || u16(bytes, local + 26) != nameLength || local + 30 + nameLength > position
                    || !Arrays.equals(data, local + 30, local + 30 + nameLength,
                            data, position + 46, position + 46 + nameLength)) {
                throw new IllegalArgumentException("ZIP local and central filenames differ");
            }
            bytes.putShort(position + 8, (short) (u16(bytes, position + 8) | 0x0800));
            bytes.putShort(local + 6, (short) (u16(bytes, local + 6) | 0x0800));
            position = next;
        }
        Files.write(archive, data);
        System.out.println("已标记 ZIP UTF-8 文件名：" + count + " 个条目");
    }

    private static int u16(ByteBuffer bytes, int offset) {
        return Short.toUnsignedInt(bytes.getShort(offset));
    }
}
