package com.mcsync.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Strips the singleplayer {@code Data -> Player} NBT compound tag from {@code level.dat} (and
 * {@code level.dat_old}) before/after world sync.
 *
 * <h3>Why this is essential in Single-JVM Integrated Host mode:</h3>
 * <p>When a world is opened in Singleplayer / IntegratedServer, Minecraft saves the hosting player's
 * inventory, position and dimensions both in {@code playerdata/<UUID>.dat} AND inside {@code level.dat -> Data.Player}.
 * When another friend downloads the world and opens it as the next Singleplayer Host, Minecraft
 * prioritizes {@code Data.Player} over {@code playerdata/<UUID>.dat}, causing the new Host to spawn
 * with the previous Host's inventory! Removing {@code Data.Player} forces Minecraft to load every
 * player — including the Host — strictly from {@code playerdata/<UUID>.dat}.</p>
 */
public final class LevelDatCleaner {

    private static final Logger log = LoggerFactory.getLogger(LevelDatCleaner.class);

    private static final byte TAG_End = 0;
    private static final byte TAG_Byte = 1;
    private static final byte TAG_Short = 2;
    private static final byte TAG_Int = 3;
    private static final byte TAG_Long = 4;
    private static final byte TAG_Float = 5;
    private static final byte TAG_Double = 6;
    private static final byte TAG_Byte_Array = 7;
    private static final byte TAG_String = 8;
    private static final byte TAG_List = 9;
    private static final byte TAG_Compound = 10;
    private static final byte TAG_Int_Array = 11;
    private static final byte TAG_Long_Array = 12;

    private LevelDatCleaner() {
    }

    /** Cleans {@code level.dat} and {@code level.dat_old} in {@code worldDir} if they are valid GZip NBT files. */
    public static boolean cleanWorldDir(Path worldDir) {
        if (worldDir == null || !Files.isDirectory(worldDir)) return false;
        boolean modified = false;
        for (String name : new String[]{"level.dat", "level.dat_old"}) {
            Path f = worldDir.resolve(name);
            if (Files.isRegularFile(f)) {
                try {
                    if (stripDataPlayerTag(f)) {
                        modified = true;
                    }
                } catch (Exception e) {
                    log.debug("Ignorata pulizia NBT su {} (non è un NBT GZip standard): {}", name, e.getMessage());
                }
            }
        }
        if (modified) {
            log.info("Rimosso tag NBT 'Data.Player' da level.dat in {} (fix UUID inventari Single-JVM)", worldDir.getFileName());
        }
        return modified;
    }

    /**
     * Removes {@code Data.Player} from a GZip-compressed NBT file if present.
     *
     * @return true if the file was modified
     */
    public static boolean stripDataPlayerTag(Path levelDat) throws IOException {
        byte[] raw = Files.readAllBytes(levelDat);
        if (raw.length < 4 || (raw[0] != (byte) 0x1f || raw[1] != (byte) 0x8b)) {
            return false; // Not a GZip NBT file (e.g. plain-text test fixture)
        }
        byte[] uncompressed;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(raw)))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            uncompressed = bos.toByteArray();
        }

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream(uncompressed.length);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(uncompressed));
        DataOutputStream dos = new DataOutputStream(outBuf);

        byte rootType = dis.readByte();
        if (rootType != TAG_Compound) return false;
        String rootName = readString(dis);
        dos.writeByte(rootType);
        writeString(dos, rootName);

        boolean removed = filterCompound(dis, dos, "");
        if (!removed) return false;

        Path tmp = levelDat.resolveSibling(levelDat.getFileName() + ".nbt.tmp");
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tmp));
             GZIPOutputStream gz = new GZIPOutputStream(fos)) {
            gz.write(outBuf.toByteArray());
        }
        Files.move(tmp, levelDat, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static boolean filterCompound(DataInputStream in, DataOutputStream out, String path) throws IOException {
        boolean removed = false;
        while (true) {
            byte type = in.readByte();
            if (type == TAG_End) {
                out.writeByte(TAG_End);
                break;
            }
            String name = readString(in);
            String fullPath = path.isEmpty() ? name : path + "." + name;
            if ("Data.Player".equals(fullPath) && type == TAG_Compound) {
                skipPayload(in, type);
                removed = true;
                continue;
            }
            out.writeByte(type);
            writeString(out, name);
            if (type == TAG_Compound && "Data".equals(fullPath)) {
                if (filterCompound(in, out, fullPath)) {
                    removed = true;
                }
            } else {
                copyPayload(in, out, type);
            }
        }
        return removed;
    }

    private static void copyPayload(DataInputStream in, DataOutputStream out, byte type) throws IOException {
        switch (type) {
            case TAG_Byte -> out.writeByte(in.readByte());
            case TAG_Short -> out.writeShort(in.readShort());
            case TAG_Int -> out.writeInt(in.readInt());
            case TAG_Long -> out.writeLong(in.readLong());
            case TAG_Float -> out.writeFloat(in.readFloat());
            case TAG_Double -> out.writeDouble(in.readDouble());
            case TAG_Byte_Array -> {
                int len = in.readInt();
                out.writeInt(len);
                byte[] b = in.readNBytes(len);
                out.write(b);
            }
            case TAG_String -> writeString(out, readString(in));
            case TAG_List -> {
                byte elemType = in.readByte();
                int len = in.readInt();
                out.writeByte(elemType);
                out.writeInt(len);
                for (int i = 0; i < len; i++) {
                    copyPayload(in, out, elemType);
                }
            }
            case TAG_Compound -> {
                while (true) {
                    byte t = in.readByte();
                    out.writeByte(t);
                    if (t == TAG_End) break;
                    writeString(out, readString(in));
                    copyPayload(in, out, t);
                }
            }
            case TAG_Int_Array -> {
                int len = in.readInt();
                out.writeInt(len);
                for (int i = 0; i < len; i++) out.writeInt(in.readInt());
            }
            case TAG_Long_Array -> {
                int len = in.readInt();
                out.writeInt(len);
                for (int i = 0; i < len; i++) out.writeLong(in.readLong());
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    private static void skipPayload(DataInputStream in, byte type) throws IOException {
        ByteArrayOutputStream discard = new ByteArrayOutputStream();
        copyPayload(in, new DataOutputStream(discard), type);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = in.readNBytes(len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
