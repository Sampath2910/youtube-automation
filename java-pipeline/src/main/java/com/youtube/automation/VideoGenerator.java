package com.youtube.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class VideoGenerator {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Main method to generate actual animated videos from scene descriptions.
     * Uses Pollinations.ai for the starting image, then Luma AI Dream Machine to animate it.
     */
    public List<String> generateVideosFromScenes(List<String> sceneDescriptions, String baseFilename) throws Exception {
        List<String> videoPaths = new ArrayList<>();
        String lumaKey = Config.get("LUMA_API_KEY");

        if (lumaKey == null || lumaKey.isEmpty() || lumaKey.contains("your_")) {
            System.out.println("  [INFO] LUMA_API_KEY missing. Skipping TRUE video generation.");
            return videoPaths; // Return empty list to trigger fallback
        }

        if (sceneDescriptions.isEmpty()) {
            sceneDescriptions.add("A beautiful colorful magical garden with sunshine and butterflies, cinematic lighting, ultra detailed");
        }

        // Scenes are now synced with narration blocks. Do not trim unless strictly necessary for API budget.
        if (sceneDescriptions.size() > 15) {
            System.out.println("Trimming synced scenes from " + sceneDescriptions.size() + " to 15 to stay within API limits.");
            sceneDescriptions = sceneDescriptions.subList(0, 15);
        }

        System.out.println("Generating " + sceneDescriptions.size() + " SYNCED animated video clips using Luma AI...");
        Path clipsDir = Paths.get(Config.getClipsDir());
        Files.createDirectories(clipsDir);

        for (int i = 0; i < sceneDescriptions.size(); i++) {
            String sceneDesc = sceneDescriptions.get(i);
            String filename = baseFilename + "_scene_vid_" + (i + 1) + ".mp4";
            Path outputPath = clipsDir.resolve(filename);

            try {
                System.out.println("\n  🎥 Generating clip " + (i + 1) + "/" + sceneDescriptions.size() + "...");
                
                // 1. Generate the initial frame image URL via Pollinations
                String imagePrompt = CharacterConfig.getCharacterPromptPrefix() + sceneDesc + ". " + CharacterConfig.CHANNEL_ART_STYLE;
                String encodedPrompt = URLEncoder.encode(imagePrompt, StandardCharsets.UTF_8);
                String imageUrl = "https://image.pollinations.ai/prompt/" + encodedPrompt + "?width=1280&height=720&nologo=true&seed=" + System.currentTimeMillis();
                
                // 2. Describe the motion for Luma
                String motionPrompt = "Smooth, high quality 3D animation. " + sceneDesc;
                
                // 3. Call Luma AI to animate the image
                String lumaGenerationId = requestLumaVideo(lumaKey, motionPrompt, imageUrl);
                System.out.println("     - Generation started on Luma AI (ID: " + lumaGenerationId + ")");
                
                // 4. Poll Luma until video is ready
                String videoUrl = pollLumaForCompletion(lumaKey, lumaGenerationId);
                
                // 5. Download the final .mp4
                System.out.println("     - Video ready! Downloading...");
                downloadFile(videoUrl, outputPath.toString());
                
                videoPaths.add(outputPath.toString());
                System.out.println("     ✓ Saved to: " + outputPath.getFileName());

            } catch (Exception e) {
                System.err.println("     ✗ Failed to generate video clip " + (i + 1) + ": " + e.getMessage());
            }
        }

        return videoPaths;
    }

    private String requestLumaVideo(String apiKey, String prompt, String imageUrl) throws Exception {
        URL url = new URL("https://api.lumalabs.ai/dream-machine/v1/generations");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        // Luma API schema: prompt + keyframes.frame0.url
        String jsonBody = String.format("""
            {
                "prompt": "%s",
                "keyframes": {
                    "frame0": {
                        "type": "image",
                        "url": "%s"
                    }
                }
            }
            """, escapeJson(prompt), imageUrl);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200 || responseCode == 201) {
            JsonNode root = mapper.readTree(conn.getInputStream());
            return root.get("id").asText();
        } else {
            String error = "";
            try (InputStream es = conn.getErrorStream()) {
                if (es != null) error = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new RuntimeException("Luma API returned " + responseCode + ": " + error);
        }
    }

    private String pollLumaForCompletion(String apiKey, String generationId) throws Exception {
        URL url = new URL("https://api.lumalabs.ai/dream-machine/v1/generations/" + generationId);
        
        int maxAttempts = 60; // 5 minutes max wait (60 * 5s)
        int attempts = 0;

        while (attempts < maxAttempts) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                JsonNode root = mapper.readTree(conn.getInputStream());
                String state = root.has("state") ? root.get("state").asText() : "";
                
                if ("completed".equalsIgnoreCase(state)) {
                    JsonNode assets = root.get("assets");
                    if (assets != null && assets.has("video")) {
                        return assets.get("video").asText();
                    }
                    throw new RuntimeException("Video completed but no video URL in response.");
                } else if ("failed".equalsIgnoreCase(state)) {
                    String reason = root.has("failure_reason") ? root.get("failure_reason").asText() : "Unknown reason";
                    throw new RuntimeException("Luma generation failed: " + reason);
                }
                
                // Still processing...
                System.out.print(".");
            } else {
                System.out.println(" (Poll error " + responseCode + ") ");
            }
            
            attempts++;
            Thread.sleep(5000); // Wait 5 seconds before polling again
        }
        
        throw new RuntimeException("Timed out waiting for Luma video generation.");
    }

    private void downloadFile(String fileUrl, String outputPath) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputPath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } else {
            throw new IOException("Failed to download video. HTTP Code: " + responseCode);
        }
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", " ");
    }
}
