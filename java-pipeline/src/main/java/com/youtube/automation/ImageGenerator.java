package com.youtube.automation;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageGenerator {

    /**
     * Generate images from pre-extracted scene descriptions.
     * This is the preferred method - scenes should already be extracted by ScriptGenerator.
     */
    public List<String> generateImagesFromScenes(List<String> sceneDescriptions, String baseFilename) throws Exception {
        List<String> imagePaths = new ArrayList<>();

        if (sceneDescriptions.isEmpty()) {
            System.out.println("No scene descriptions provided. Using a default prompt.");
            sceneDescriptions = new ArrayList<>();
            sceneDescriptions.add("A beautiful colorful magical garden with sunshine and butterflies");
        }

        // Limit to max 25 scenes (50 images) to stay within free plan stability while supporting longer videos
        if (sceneDescriptions.size() > 25) {
            System.out.println("Trimming scenes from " + sceneDescriptions.size() + " to 25 for better video pacing.");
            sceneDescriptions = sceneDescriptions.subList(0, 25);
        }

        System.out.println("Generating " + sceneDescriptions.size() + " scenes with 2 animation frames each (Total: " + (sceneDescriptions.size() * 2) + " images)...");
        Path clipsDir = Paths.get(Config.getClipsDir());
        if (!Files.exists(clipsDir)) {
            Files.createDirectories(clipsDir);
        }

        for (int i = 0; i < sceneDescriptions.size(); i++) {
            String sceneDesc = sceneDescriptions.get(i);
            String basePrompt = buildImagePrompt(sceneDesc);
            
            // Generate 3 frames per scene for smoother animation
            for (int frame = 0; frame < 3; frame++) {
                String animatedPrompt = basePrompt + ", " + CharacterConfig.MOVEMENT_POSES[frame % CharacterConfig.MOVEMENT_POSES.length];
                String filename = baseFilename + "_scene_" + (i + 1) + "_f" + (frame + 1) + ".jpg";
                Path outputPath = clipsDir.resolve(filename);

                try {
                    // Add a longer delay for free plan stability
                    if (i > 0 || frame > 0) Thread.sleep(3000);
                    
                    // Use a consistent seed for the same scene but different frames to keep background stable
                    long seed = 12345 + i; 
                    generateImage(animatedPrompt, outputPath.toString(), seed);
                    imagePaths.add(outputPath.toString());
                    System.out.println("  ✓ Generated scene " + (i + 1) + " frame " + (frame + 1));
                } catch (Exception e) {
                    System.err.println("  ✗ Failed scene " + (i + 1) + " frame " + (frame + 1) + ": " + e.getMessage());
                }
            }
        }

        return imagePaths;
    }

    /**
     * Legacy method - generates images by extracting scenes from the raw script.
     * Used as fallback when scene descriptions aren't pre-extracted.
     */
    public List<String> generateImagesFromScript(String script, String baseFilename) throws Exception {
        List<String> scenes = new ArrayList<>();
        // Simple extraction for the synced segment format [SCENE: ...]
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?i)\\[SCENE:\\s*([^\\]]+)\\]");
        java.util.regex.Matcher matcher = pattern.matcher(script);
        while (matcher.find()) {
            scenes.add(matcher.group(1).trim());
        }
        
        // If no scenes found, use the whole script as the scene (legacy fallback)
        if (scenes.isEmpty() && !script.isEmpty()) {
            scenes.add(script);
        }
        
        return generateImagesFromScenes(scenes, baseFilename);
    }

    /**
     * Build a complete image generation prompt with character details and art style.
     */
    private String buildImagePrompt(String sceneDescription) {
        return CharacterConfig.getCharacterPromptPrefix() + 
               sceneDescription + ". " + 
               CharacterConfig.CHANNEL_ART_STYLE + 
               ", children's animation, bright cheerful colors, magical atmosphere, high quality render";
    }

    private void generateImage(String prompt, String outputPath, long seed) throws IOException {
        // Using Pollinations.ai for free, no-auth image generation
        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        String urlStr = "https://image.pollinations.ai/prompt/" + encodedPrompt + "?width=1280&height=720&nologo=true&seed=" + seed;
        
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(20000); // 20 seconds
                conn.setReadTimeout(60000);    // 60 seconds for higher res images
                
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
                    return; // Success
                } else {
                    throw new IOException("Server returned code " + responseCode);
                }
            } catch (IOException e) {
                if (attempt == maxRetries) throw e;
                System.err.println("    - Attempt " + attempt + " failed, retrying in 3s... (" + e.getMessage() + ")");
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
