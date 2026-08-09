package com.sheyito.economicmaster.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sheyito.economicmaster.EconomicMaster;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonFileUtil {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonFileUtil() {
    }

    /**
     * Loads {@code path} as JSON of type {@code type}. If the file does not exist yet,
     * {@code defaultSupplier} is written to disk and returned instead, so every config/data
     * file is auto-generated on first use.
     */
    public static <T> T loadOrCreate(Path path, Class<T> type, java.util.function.Supplier<T> defaultSupplier) {
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    T value = GSON.fromJson(reader, type);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (IOException | com.google.gson.JsonParseException e) {
            EconomicMaster.LOGGER.error("No se pudo leer {}, se regenerara con valores por defecto.", path, e);
        }

        T value = defaultSupplier.get();
        save(path, value);
        return value;
    }

    public static void save(Path path, Object value) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
        } catch (IOException e) {
            EconomicMaster.LOGGER.error("No se pudo guardar {}", path, e);
        }
    }
}
