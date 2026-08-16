package com.chestlogger.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compact string dictionary table for interning identifiers and dimension keys.
 */
public final class StringTableDictionary {
    private final Map<String, Integer> stringToId = new HashMap<>();
    private final List<String> idToString = new ArrayList<>();

    public synchronized int getOrAssign(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        Integer existing = stringToId.get(value);
        if (existing != null) {
            return existing;
        }
        int newId = idToString.size();
        stringToId.put(value, newId);
        idToString.add(value);
        return newId;
    }

    public synchronized String getString(int id) {
        if (id < 0 || id >= idToString.size()) {
            throw new IndexOutOfBoundsException("Invalid dictionary id: " + id);
        }
        return idToString.get(id);
    }

    public synchronized int size() {
        return idToString.size();
    }

    public synchronized void writeTo(OutputStream out) throws IOException {
        VarIntUtil.writeVarInt(out, idToString.size());
        for (String s : idToString) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            VarIntUtil.writeVarInt(out, bytes.length);
            out.write(bytes);
        }
    }

    public static StringTableDictionary readFrom(InputStream in) throws IOException {
        StringTableDictionary dict = new StringTableDictionary();
        int count = VarIntUtil.readVarInt(in);
        for (int i = 0; i < count; i++) {
            int len = VarIntUtil.readVarInt(in);
            byte[] bytes = in.readNBytes(len);
            String str = new String(bytes, StandardCharsets.UTF_8);
            dict.getOrAssign(str);
        }
        return dict;
    }
}
