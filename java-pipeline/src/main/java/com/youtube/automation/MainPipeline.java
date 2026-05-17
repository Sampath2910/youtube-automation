package com.youtube.automation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class MainPipeline {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -jar youtube-automation.jar \"Your video topic\"");
            System.out.println("Example: java -jar youtube-automation.jar \"A story about a friendly dragon who loves to bake cookies\"");
            return;
        }

        String topic = args[0];
        System.out.println("\n========================================");
        System.out.println("   YouTube Automation Pipeline (Java)  ");
        System.out.println("========================================");
        System.out.println("Topic: " + topic);
        System.out.println("Started: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("========================================\n");

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String baseFilename = "video_" + timestamp;

            Config config = new Config();
            ScriptGenerator scriptGen = new ScriptGenerator();
            VoiceoverGenerator voiceGen = new VoiceoverGenerator();
            ImageGenerator imageGen = new ImageGenerator();
            VideoGenerator videoGen = new VideoGenerator();
            VideoAssembler assembler = new VideoAssembler();
            YouTubeUploader uploader = new YouTubeUploader();

            // ===== STEP 1: Generate Script =====
            System.out.println("[STEP 1/5] Generating script with AI...");
            String script = scriptGen.generateScript(topic);
            String scriptFile = baseFilename + "_script.txt";
            scriptGen.saveScript(script, scriptFile);
            System.out.println("✓ Script generated successfully!\n");

            // ===== STEP 2: Extract segments and generate synced audio/video =====
            System.out.println("[STEP 2/5] Generating synced audio and video segments...");
            List<ScriptGenerator.StorySegment> segments = ScriptGenerator.extractSegments(script);
            System.out.println("  Found " + segments.size() + " synced segments");

            List<String> clipPaths = new ArrayList<>();
            List<String> voiceoverPaths = new ArrayList<>();

            int fallbackSegments = 0;
            for (int i = 0; i < segments.size(); i++) {
                ScriptGenerator.StorySegment segment = segments.get(i);
                String segmentBaseName = baseFilename + "_seg_" + (i + 1);
                System.out.println("\n  --- Processing Segment " + (i + 1) + "/" + segments.size() + " ---");

                // 1. Always generate voiceover first — this is the primary content and must never be skipped
                String voiceoverFile = segmentBaseName + "_voice.mp3";
                String segmentVoicePath = voiceGen.generate(segment.narration, voiceoverFile);
                double audioDuration = assembler.getAudioDuration(segmentVoicePath);

                // 2. Try to generate a video clip for this segment
                List<String> sceneDesc = new ArrayList<>();
                sceneDesc.add(segment.scene);

                // Try Luma AI first (if key exists)
                List<String> generatedClips = videoGen.generateVideosFromScenes(sceneDesc, segmentBaseName);

                // Fallback to FREE Image-to-Animation if Luma failed or key is missing
                // Retry up to 3 times with exponential backoff before giving up
                if (generatedClips.isEmpty()) {
                    int[] retryDelaysSeconds = {0, 5, 10, 20}; // 4 attempts: immediate + 3 retries
                    for (int attempt = 0; attempt < retryDelaysSeconds.length; attempt++) {
                        if (retryDelaysSeconds[attempt] > 0) {
                            System.out.println("  [Retry " + attempt + "/3] Image generation failed. Waiting "
                                + retryDelaysSeconds[attempt] + "s before retrying segment " + (i + 1) + "...");
                            try { Thread.sleep(retryDelaysSeconds[attempt] * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        } else {
                            System.out.println("  Using FREE Image-to-Animation system for Segment " + (i + 1));
                        }
                        generatedClips = imageGen.generateImagesFromScript("[SCENE: " + segment.scene + "]", segmentBaseName);
                        if (!generatedClips.isEmpty()) {
                            System.out.println("  ✓ Image generation succeeded on attempt " + (attempt + 1) + " for segment " + (i + 1));
                            break;
                        }
                    }
                }

                // 3. Build the synced video clip — use a plain background if all image generation failed
                String syncedClip = segmentBaseName + "_synced.mp4";
                Path syncedClipPath = Paths.get(Config.getOutputDir(), syncedClip);

                if (generatedClips.isEmpty()) {
                    // Image generation failed — create a solid background clip of the exact audio duration
                    // This preserves the voiceover so NO narration is lost from the final video
                    System.err.println("  [WARN] Image generation failed for segment " + (i + 1)
                        + " — using plain background fallback clip.");
                    assembler.createFallbackClip(audioDuration, syncedClipPath);
                    fallbackSegments++;
                } else {
                    assembler.createVideoFromClips(generatedClips, syncedClipPath, audioDuration);
                }

                // Both lists always grow together — counts will always match
                clipPaths.add(syncedClipPath.toString());
                voiceoverPaths.add(segmentVoicePath);
            }

            System.out.println("\n  Segments processed: " + clipPaths.size() + "/" + segments.size()
                + (fallbackSegments > 0 ? " (" + fallbackSegments + " used plain background fallback)" : " (all images generated successfully)"));

            // ===== STEP 3: Concatenate all synced segments =====
            System.out.println("\n[STEP 3/5] Merging synced segments into final video...");
            String outputFile = baseFilename + ".mp4";
            Path finalOutputPath = Paths.get(Config.getOutputDir(), outputFile);
            
            // Create a final narration file for subtitles
            StringBuilder fullNarration = new StringBuilder();
            for (ScriptGenerator.StorySegment seg : segments) {
                fullNarration.append(seg.narration).append("\n\n");
            }

            // Combine all synced clips and audio
            String finalVideo = assembler.mergeSyncedSegments(clipPaths, voiceoverPaths, finalOutputPath.toString(), fullNarration.toString());
            System.out.println("✓ Video assembled: " + finalVideo + "\n");
            
            // Cleanup: remove temporary synced segment files
            for (String p : clipPaths) Files.deleteIfExists(Paths.get(p));
            for (String p : voiceoverPaths) Files.deleteIfExists(Paths.get(p));

            // ===== STEP 5: Upload to YouTube =====
            System.out.println("[STEP 5/5] Uploading to YouTube...");
            String videoUrl;
            try {
                String title = extractTitle(script, topic);
                String description = extractDescription(script);
                String[] tags = extractTags(topic);
                videoUrl = uploader.uploadVideo(finalVideo, title, description, tags);
            } catch (Exception e) {
                System.err.println("YouTube upload failed: " + e.getMessage());
                System.err.println("Video saved locally at: " + finalVideo);
                videoUrl = finalVideo;
            }

            System.out.println("\n========================================");
            System.out.println("         ✓ PIPELINE COMPLETE ✓         ");
            System.out.println("========================================");
            System.out.println("Final Video: " + videoUrl);
            System.out.println("Completed: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            System.out.println("========================================\n");

        } catch (Exception e) {
            System.err.println("\n!!! PIPELINE FAILED !!!");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String createPlaceholderAudio(String baseFilename) throws Exception {
        Path outputPath = Paths.get(Config.getVoiceoverDir(), baseFilename + "_voiceover.mp3");
        Files.createDirectories(outputPath.getParent());

        // Use full path to ffmpeg if configured, else default to "ffmpeg"
        String ffmpegPath = Config.get("FFMPEG_PATH");
        if (ffmpegPath == null || ffmpegPath.isEmpty()) ffmpegPath = "ffmpeg";

        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-y",
            "-f", "lavfi",
            "-i", "anullsrc=r=44100:cl=stereo:d=5",
            "-acodec", "libmp3lame",
            "-q:a", "2",
            outputPath.toString()
        );
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Could not create placeholder audio");
        }

        return outputPath.toString();
    }

    private static String extractTitle(String script, String fallback) {
        // Try to extract a title from the narration section or first line
        String[] lines = script.split("\n");
        for (String line : lines) {
            line = line.trim();
            // Skip format markers
            if (line.startsWith("===")) continue;
            if (line.isEmpty()) continue;
            
            if (line.length() > 10 && !line.startsWith("[") && !line.startsWith("(")) {
                String title = line.length() > 100 ? line.substring(0, 100) : line;
                title = title.replaceAll("[^a-zA-Z0-9\\s\\-\\:]", "");
                return title.trim();
            }
        }
        return fallback.length() > 100 ? fallback.substring(0, 100) : fallback;
    }

    private static String extractDescription(String script) {
        StringBuilder desc = new StringBuilder();
        desc.append("📚 Thank you for watching!\n\n");
        desc.append("Subscribe for more amazing stories!\n\n");
        desc.append("This video was created using AI automation.\n\n");
        desc.append("© All rights reserved\n");
        return desc.toString();
    }

    private static String[] extractTags(String topic) {
        List<String> tags = new ArrayList<>();
        tags.add("kids");
        tags.add("children");
        tags.add("stories");
        tags.add("animation");
        tags.add("automation");

        String[] words = topic.split("\\s+");
        for (String word : words) {
            word = word.replaceAll("[^a-zA-Z]", "");
            if (word.length() > 3 && !tags.contains(word.toLowerCase())) {
                tags.add(word.toLowerCase());
            }
        }

        return tags.toArray(new String[0]);
    }
}
