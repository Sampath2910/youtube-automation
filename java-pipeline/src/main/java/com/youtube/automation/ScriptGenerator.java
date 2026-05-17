package com.youtube.automation;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ScriptGenerator {

    public String generateScript(String topic) throws Exception {
        String anthropicKey = Config.get("ANTHROPIC_API_KEY");
        String geminiKey = Config.get("GEMINI_API_KEY");

        // Try Anthropic First
        if (anthropicKey != null && !anthropicKey.isEmpty() && !anthropicKey.contains("your_")) {
            try {
                System.out.println("Attempting script generation with Claude (Anthropic)...");
                return generateWithAnthropic(topic, anthropicKey);
            } catch (Exception e) {
                System.err.println("Anthropic failed: " + e.getMessage());
                if (geminiKey == null || geminiKey.isEmpty() || geminiKey.contains("your_")) {
                    throw e;
                }
                System.out.println("Falling back to Gemini AI...");
            }
        }

        // Try Gemini Fallback
        if (geminiKey != null && !geminiKey.isEmpty() && !geminiKey.contains("your_")) {
            return generateWithGemini(topic, geminiKey);
        }

        throw new IllegalStateException("No valid AI keys found (Anthropic or Gemini). Please check your .env file.");
    }

    private String generateWithGemini(String topic, String apiKey) throws Exception {
        String prompt = buildPrompt(topic);
        
        // 1. Discover available models dynamically
        List<String> discoveredModels = new ArrayList<>();
        System.out.println("Discovering available models for your API key...");
        try {
            URL listUrl = new URL("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey);
            HttpURLConnection listConn = (HttpURLConnection) listUrl.openConnection();
            listConn.setRequestMethod("GET");
            listConn.setConnectTimeout(5000);
            listConn.setReadTimeout(5000);
            
            if (listConn.getResponseCode() == 200) {
                String listResponse = new String(listConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                // Simple regex-based parsing to avoid adding heavy JSON dependencies
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"name\":\\s*\"models/([^\"]+)\"").matcher(listResponse);
                while (m.find()) {
                    String modelName = m.group(1);
                    if (modelName.contains("gemini") && !modelName.contains("vision")) {
                        discoveredModels.add(modelName);
                    }
                }
                System.out.println("Discovered " + discoveredModels.size() + " compatible models: " + discoveredModels);
            } else {
                System.err.println("Note: Could not list models (Code " + listConn.getResponseCode() + ").");
            }
        } catch (Exception e) {
            System.err.println("Discovery failed: " + e.getMessage());
        }

        // 2. Fallback to hardcoded list if discovery failed
        if (discoveredModels.isEmpty()) {
            discoveredModels.addAll(Arrays.asList("gemini-1.5-flash", "gemini-1.5-pro", "gemini-pro", "gemini-1.0-pro"));
        }

        StringBuilder debugInfo = new StringBuilder();
        String[] apiVersions = {"v1beta", "v1"};

        for (String version : apiVersions) {
            for (String model : discoveredModels) {
                try {
                    // Important: The URL should be version/models/modelName
                    String urlStr = String.format("https://generativelanguage.googleapis.com/%s/models/%s:generateContent?key=%s", 
                                    version, model, apiKey);
                    
                    System.out.println("Trying Gemini [" + version + "] with model [" + model + "]...");
                    
                    String requestBody = """
                        {
                          "contents": [{
                            "parts":[{"text": "%s"}]
                          }]
                        }
                        """.formatted(escapeJson(prompt));

                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(10000); // 10 seconds timeout
                    conn.setReadTimeout(30000);    // 30 seconds timeout
                    conn.setDoOutput(true);

                    // Retry logic for network glitches
                    int retries = 2;
                    while (retries >= 0) {
                        try {
                            try (OutputStream os = conn.getOutputStream()) {
                                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                            }
                            break; // Success, exit retry loop
                        } catch (java.io.IOException e) {
                            if (retries == 0) throw e;
                            System.err.println("Network glitch, retrying... (" + retries + " left)");
                            retries--;
                            Thread.sleep(1000);
                        }
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        System.out.println("Successfully generated script with " + model);
                        return extractFromGemini(response);
                    } else {
                        String errorResponse = "";
                        try (java.io.InputStream es = conn.getErrorStream()) {
                            if (es != null) {
                                errorResponse = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                            }
                        }
                        debugInfo.append(String.format("- %s/%s failed: %d (%s)\n", version, model, responseCode, errorResponse));
                    }
                } catch (Exception e) {
                    debugInfo.append(String.format("- %s/%s crashed: %s\n", version, model, e.getMessage()));
                }
            }
        }

        String helpMessage = """
            
            ------------------------------------------------------------
            POSSIBLE FIXES FOR PERSISTENT 404:
            1. Go to https://aistudio.google.com/ and get a NEW API Key.
            2. Your key might be tied to a project without 'Generative Language API' enabled.
            3. Ensure you are not in a restricted region (try a US-based VPN).
            ------------------------------------------------------------
            """;
        throw new RuntimeException("All Gemini attempts failed. " + helpMessage + "\nDiagnostics:\n" + debugInfo.toString());
    }

    private String generateWithAnthropic(String topic, String apiKey) throws Exception {
        String prompt = buildPrompt(topic);
        String requestBody = buildAnthropicBody(prompt);

        URL url = new URL("https://api.anthropic.com/v1/messages");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        // Retry logic for network glitches
        int retries = 2;
        while (retries >= 0) {
            try {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }
                break;
            } catch (java.io.IOException e) {
                if (retries == 0) throw e;
                System.err.println("Anthropic network glitch, retrying... (" + retries + " left)");
                retries--;
                Thread.sleep(1000);
            }
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 201) {
            String errorMsg = "";
            try (java.io.InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    errorMsg = " - " + new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
            throw new RuntimeException("Anthropic API failed with code: " + responseCode + errorMsg);
        }

        String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return extractScriptFromResponse(response);
    }

    private String extractFromGemini(String response) {
        int start = response.indexOf("\"text\": \"");
        if (start == -1) return response;
        start += 9;
        int end = findEndOfString(response, start);
        if (end == -1) return response;
        
        String text = response.substring(start, end);
        return text.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String buildAnthropicBody(String prompt) {
        return """
            {
                "model": "claude-3-haiku-20240307",
                "max_tokens": 4096,
                "messages": [{"role": "user", "content": "%s"}]
            }
            """.formatted(escapeJson(prompt));
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    private String buildPrompt(String topic) {
        return "You are a creative children's story narrator for a YouTube channel like NuNu Tv or Cocomelon. " +
               "Write an engaging, kid-friendly narration in TELUGU language about: " + topic + "\n\n" +
               "CHARACTER: The main character is " + CharacterConfig.MAIN_CHARACTER_NAME + ", who is " + CharacterConfig.MAIN_CHARACTER_DESCRIPTION + ".\n\n" +
               "CRITICAL: YOU MUST SYNC THE VISUALS WITH THE NARRATION.\n" +
               "Format the script into 8-12 distinct segments. Each segment MUST have a scene description in English and the corresponding narration in Telugu.\n\n" +
               "STRICT FORMAT FOR EACH SEGMENT:\n" +
               "[SCENE: English description of what is happening visually. Be very specific to this part of the story.]\n" +
               "[NARRATION: Telugu translation of the story text for this specific scene.]\n\n" +
               "Example:\n" +
               "[SCENE: Sammy is walking into a bright park with a big red ball under his arm, smiling at a blue butterfly.]\n" +
               "[NARRATION: ఒకప్పుడు సామీ ఒక అందమైన పార్కుకు వెళ్ళాడు. అతని దగ్గర ఒక పెద్ద ఎర్రటి బంతి ఉంది.]\n\n" +
               "Requirements:\n" +
               "1. THE NARRATION MUST BE IN TELUGU (తెలుగు).\n" +
               "2. THE SCENE DESCRIPTIONS MUST BE IN ENGLISH.\n" +
               "3. Total story length should be 5-8 minutes (750-1200 words of Telugu narration).\n" +
               "4. Each Telugu [NARRATION] block should be about 2-4 sentences long.\n" +
               "5. Do NOT use any other formatting, asterisks, or labels.";
    }

    /**
     * Extracts synced segments from the script.
     * Each segment contains a scene description and its corresponding narration.
     */
    public static List<StorySegment> extractSegments(String script) {
        List<StorySegment> segments = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?i)\\[SCENE:\\s*([^\\]]+)\\]\\s*\\[NARRATION:\\s*([^\\]]+)\\]", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(script);
        
        while (matcher.find()) {
            String scene = matcher.group(1).trim();
            String narration = matcher.group(2).trim();
            segments.add(new StorySegment(scene, narration));
        }
        
        return segments;
    }

    public static class StorySegment {
        public final String scene;
        public final String narration;
        public StorySegment(String scene, String narration) {
            this.scene = scene;
            this.narration = narration;
        }
    }

    /**
     * Aggressively cleans text for TTS consumption.
     * Removes ALL formatting, leaving only spoken words.
     */
    public static String cleanForTTS(String text) {
        String[] lines = text.split("\n");
        StringBuilder cleaned = new StringBuilder();
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Skip empty lines (but preserve paragraph breaks)
            if (trimmed.isEmpty()) {
                cleaned.append("\n");
                continue;
            }
            
            // Skip lines that are scene/stage directions
            if (trimmed.startsWith("[") || trimmed.startsWith("(SFX") || trimmed.startsWith("(sfx")) continue;
            if (trimmed.matches("^\\[.*\\]$")) continue;
            if (trimmed.matches("^\\(.*\\)$")) continue;
            
            // Skip divider lines
            if (trimmed.matches("^[-=_*]{3,}$")) continue;
            
            // Skip heading lines (### Scene 1, # Title, etc.)
            if (trimmed.matches("^#{1,6}\\s+.*")) continue;
            
            // Skip lines that are purely visual/sound directions
            if (trimmed.startsWith("**Visual:**") || trimmed.startsWith("Visual:")) continue;
            if (trimmed.startsWith("**(SFX") || trimmed.startsWith("(SFX")) continue;
            
            // Process the line
            String processedLine = trimmed;
            
            // Remove character dialog labels: **Sammy:** → "" or **Narrator:** → ""
            // This converts "**Sammy:** Hello!" to "Hello!" 
            processedLine = processedLine.replaceAll("\\*\\*[^*]+:\\*\\*\\s*", "");
            
            // Remove remaining bold/italic markers
            processedLine = processedLine.replaceAll("\\*{1,3}", "");
            
            // Remove inline brackets [Scene 1: ...] 
            processedLine = processedLine.replaceAll("\\[[^\\]]*\\]", "");
            
            // Remove inline parenthetical directions (gasping) (whispering) (SFX: ...)
            processedLine = processedLine.replaceAll("\\([^)]*\\)", "");
            
            // Remove hash marks
            processedLine = processedLine.replaceAll("#", "");
            
            // Remove remaining special formatting characters
            processedLine = processedLine.replaceAll("[_~`]", "");
            
            // Clean up extra whitespace
            processedLine = processedLine.replaceAll("\\s{2,}", " ").trim();
            
            // Only add non-empty lines
            if (!processedLine.isEmpty() && processedLine.length() > 1) {
                cleaned.append(processedLine).append("\n");
            }
        }
        
        // Final cleanup: remove multiple consecutive newlines
        String result = cleaned.toString().replaceAll("\n{3,}", "\n\n").trim();
        return result;
    }

    private String extractScriptFromResponse(String response) {
        int contentStart = response.indexOf("\"content\":[");
        if (contentStart == -1) {
            if (response.contains("\"text\":\"")) {
                return extractJsonText(response);
            }
            return response;
        }

        int textStart = response.indexOf("\"text\":\"", contentStart);
        if (textStart == -1) return response;

        textStart += 8;
        int textEnd = findEndOfString(response, textStart);

        String text = response.substring(textStart, textEnd);
        return text.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String extractJsonText(String response) {
        int textStart = response.indexOf("\"text\":\"");
        if (textStart == -1) return response;

        textStart += 8;
        int textEnd = findEndOfString(response, textStart);

        String text = response.substring(textStart, textEnd);
        return text.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private int findEndOfString(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return s.length();
    }

    public void saveScript(String script, String filename) throws Exception {
        Path outputPath = Paths.get(Config.getScriptsDir(), filename);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, script);
        System.out.println("Script saved to: " + outputPath);
    }
}
