package com.kaveenk.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class EnvReader {

    private static EnvReader instance;
    private final Map<String, String> envMap;

    private EnvReader(String envFilePath) {
        envMap = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(envFilePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    envMap.put(key, value);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static EnvReader getInstance() {
        if (instance == null) {
            instance = new EnvReader("./.env");
        }
        return instance;
    }

    public String getEnv(String key) {
        return envMap.get(key);
    }

}
