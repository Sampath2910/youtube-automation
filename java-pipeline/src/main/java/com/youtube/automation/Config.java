package com.youtube.automation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.List;

public class Config {
    private static Properties props = new Properties();

    static {
        loadConfig();
    }

    private static void loadConfig() {
        // Try multiple locations for .env
        Path[] paths = {
            Paths.get(System.getProperty("user.dir"), ".env"),
            Paths.get(System.getProperty("user.dir"), "java-pipeline", ".env"),
            Paths.get(System.getProperty("user.dir"), "..", ".env"),
            Paths.get("C:/Users/akkap/OneDrive/Desktop/youtube automation/.env")
        };

        Path configPath = null;
        for (Path p : paths) {
            if (Files.exists(p)) {
                configPath = p;
                break;
            }
        }

        if (configPath != null) {
            System.out.println("Loading configuration from: " + configPath.toAbsolutePath());
            try {
                List<String> lines = Files.readAllLines(configPath);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int sep = line.indexOf('=');
                    if (sep > 0) {
                        String key = line.substring(0, sep).trim();
                        String value = line.substring(sep + 1).trim();
                        props.setProperty(key, value);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error reading .env file: " + e.getMessage());
            }
        } else {
            System.err.println("Warning: No .env file found in expected locations.");
        }
    }

    public static String get(String key) {
        String value = props.getProperty(key, System.getenv(key));
        if (value == null) return null;
        
        value = value.trim();
        // Remove surrounding quotes if present
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        } else if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }
        
        return value.trim();
    }

    public static String getApiKey() {
        String anthropic = get("ANTHROPIC_API_KEY");
        if (anthropic != null && !anthropic.isEmpty() && !anthropic.contains("your_")) return anthropic;
        return get("GEMINI_API_KEY");
    }

    public static String getLumaApiKey() {
        return get("LUMA_API_KEY");
    }

    public static String getProjectDir() {
        return "C:/Users/akkap/OneDrive/Desktop/youtube automation";
    }

    public static String getScriptsDir() {
        return getProjectDir() + "/scripts";
    }

    public static String getOutputDir() {
        return getProjectDir() + "/output";
    }

    public static String getVoiceoverDir() {
        return getProjectDir() + "/voiceovers";
    }

    public static String getClipsDir() {
        return getProjectDir() + "/clips";
    }
}
