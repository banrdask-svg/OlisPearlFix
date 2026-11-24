package com.banan.olispearlfix.persistence;

import com.banan.olispearlfix.binding.Binding;
import com.banan.olispearlfix.binding.BlockPosition;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

public final class BindingStore {
    private static final int MAGIC = 0x4F504631;
    private static final int SCHEMA = 1;
    private static final int MAX_RECORD_BYTES = 8192;
    private final Path directory;
    private final Map<UUID, Binding> committed = new HashMap<>();

    public BindingStore(Path directory) {
        this.directory = directory;
    }

    public List<Binding> load() throws IOException {
        Files.createDirectories(directory);
        List<Binding> result = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".dat")).sorted().toList()) {
                if (Files.size(path) > MAX_RECORD_BYTES) {
                    throw new IOException("Oversized binding record: " + path);
                }
                Binding binding = decode(Files.readAllBytes(path));
                if (!path.getFileName().toString().equals(binding.id() + ".dat")) {
                    throw new IOException("Binding filename does not match UUID: " + path);
                }
                result.add(binding);
            }
        }
        committed.clear();
        result.forEach(binding -> committed.put(binding.id(), binding));
        return result;
    }

    public void save(Binding binding) throws IOException {
        if (binding.equals(committed.get(binding.id()))) {
            return;
        }
        Files.createDirectories(directory);
        Path destination = directory.resolve(binding.id() + ".dat");
        Path temporary = directory.resolve(binding.id() + ".tmp");
        byte[] encoded = encode(binding);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic binding persistence is not supported by " + directory, exception);
        }
        committed.put(binding.id(), binding);
    }

    public void delete(UUID id) throws IOException {
        Files.deleteIfExists(directory.resolve(id + ".dat"));
        committed.remove(id);
    }

    private static byte[] encode(Binding binding) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payload)) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA);
            writeUuid(output, binding.id());
            writeUuid(output, binding.ownerId());
            writeUuid(output, binding.rodId());
            writePosition(output, binding.plate());
            output.writeUTF(binding.worldName());
            output.writeUTF(binding.plateMaterial());
            output.writeInt(binding.signal());
            writeUuid(output, binding.pearlId());
            writePosition(output, binding.pearlPosition());
            writeUuid(output, binding.hookId());
            output.writeDouble(binding.hookX());
            output.writeDouble(binding.hookY());
            output.writeDouble(binding.hookZ());
            output.writeUTF(binding.state().name());
        }
        CRC32 checksum = new CRC32();
        checksum.update(payload.toByteArray());
        try (DataOutputStream output = new DataOutputStream(payload)) {
            output.writeLong(checksum.getValue());
        }
        return payload.toByteArray();
    }

    private static Binding decode(byte[] data) throws IOException {
        if (data.length < 16) {
            throw new IOException("Truncated binding record");
        }
        CRC32 checksum = new CRC32();
        checksum.update(data, 0, data.length - Long.BYTES);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) {
                throw new IOException("Unsupported binding record format");
            }
            Binding binding = new Binding(readUuid(input), readUuid(input), readUuid(input), readPosition(input),
                    input.readUTF(), input.readUTF(), input.readInt(), readUuid(input), readPosition(input),
                    readUuid(input), input.readDouble(), input.readDouble(), input.readDouble(),
                    Binding.State.valueOf(input.readUTF()));
            if (input.readLong() != checksum.getValue() || input.available() != 0) {
                throw new IOException("Binding checksum mismatch or trailing data");
            }
            return binding;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid binding record", exception);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writePosition(DataOutputStream output, BlockPosition position) throws IOException {
        writeUuid(output, position.worldId());
        output.writeInt(position.x());
        output.writeInt(position.y());
        output.writeInt(position.z());
    }

    private static BlockPosition readPosition(DataInputStream input) throws IOException {
        return new BlockPosition(readUuid(input), input.readInt(), input.readInt(), input.readInt());
    }
}
