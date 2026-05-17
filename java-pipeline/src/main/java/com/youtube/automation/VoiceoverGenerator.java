package com.youtube.automation;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class VoiceoverGenerator {

    private String elevenLabsApiKey;
    private String elevenLabsVoiceId;

    public VoiceoverGenerator() {
        this.elevenLabsApiKey = Config.get("ELEVENLABS_API_KEY");
        this.elevenLabsVoiceId = Config.get("ELEVENLABS_VOICE_ID");
    }

    public String generate(String script, String outputFile) throws Exception {
        // CRITICAL: Clean the script before sending to any TTS engine
        String cleanText = ScriptGenerator.cleanForTTS(script);
        System.out.println("Cleaned narration for TTS (" + cleanText.length() + " chars, original was " + script.length() + " chars)");
        
        if (cleanText.isEmpty()) {
            throw new RuntimeException("After cleaning, narration text is empty! Check script format.");
        }

        if (elevenLabsApiKey != null && !elevenLabsApiKey.isEmpty() && !elevenLabsApiKey.contains("your_")) {
            try {
                System.out.println("Attempting ElevenLabs TTS...");
                return generateWithElevenLabs(cleanText, outputFile);
            } catch (Exception e) {
                System.err.println("ElevenLabs failed, falling back to Edge-TTS: " + e.getMessage());
            }
        }
        System.out.println("Using Edge-TTS (Free Alternative)...");
        return generateWithEdgeTTS(cleanText, outputFile);
    }

    public String generateWithElevenLabs(String cleanText, String outputFile) throws Exception {
        Path outputPath = Paths.get(Config.getVoiceoverDir(), outputFile);
        Files.createDirectories(outputPath.getParent());

        String voiceId = (elevenLabsVoiceId != null && !elevenLabsVoiceId.isEmpty())
            ? elevenLabsVoiceId : "21m00Tcm4TlvDq8ikWAM";

        String url = "https://api.elevenlabs.io/v1/text-to-speech/" + voiceId;

        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "audio/mpeg");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("xi-api-key", elevenLabsApiKey);
        conn.setDoOutput(true);

        String requestBody = String.format("""
            {
                "text": "%s",
                "model_id": "eleven_monolingual_v1",
                "voice_settings": {
                    "stability": 0.5,
                    "similarity_boost": 0.75
                }
            }
            """, escapeJson(cleanText));

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("ElevenLabs API failed with code: " + responseCode);
        }

        try (java.io.InputStream is = conn.getInputStream();
             java.io.FileOutputStream fos = new java.io.FileOutputStream(outputPath.toFile())) {
            is.transferTo(fos);
        }

        return outputPath.toString();
    }

    public String generateWithEdgeTTS(String cleanText, String outputFile) throws Exception {
        Path outputPath = Paths.get(Config.getVoiceoverDir(), outputFile);
        Files.createDirectories(outputPath.getParent());

        // Create a temporary file for the clean script text
        Path tempScript = Files.createTempFile("voice_script_", ".txt");
        Files.writeString(tempScript, cleanText);

        try {
            // Use Telugu voice for Edge-TTS
            String edgeTtsPath = "C:\\Users\\akkap\\AppData\\Roaming\\Python\\Python312\\Scripts\\edge-tts.exe";
            ProcessBuilder pb = new ProcessBuilder(
                edgeTtsPath,
                "--voice", "te-IN-ShrutiNeural",
                "--file", tempScript.toString(),
                "--write-media", outputPath.toString()
            );
            pb.inheritIO();
            
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Edge-TTS command failed with exit code: " + exitCode + ". Make sure 'pip install edge-tts' was run.");
            }

            return outputPath.toString();
        } finally {
            Files.deleteIfExists(tempScript);
        }
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
