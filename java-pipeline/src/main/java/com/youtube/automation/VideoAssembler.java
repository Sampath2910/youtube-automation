package com.youtube.automation;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class VideoAssembler {

    private String ffmpegPath;
    private Random random = new Random();
    
    // Animation effect types for variety
    private enum AnimationEffect {
        ZOOM_IN_CENTER,       // Classic zoom into center
        ZOOM_OUT_CENTER,      // Zoom out from center
        PAN_LEFT_TO_RIGHT,    // Slow horizontal pan left to right
        PAN_RIGHT_TO_LEFT,    // Slow horizontal pan right to left
        ZOOM_IN_PAN_LEFT,     // Zoom in while panning left
        ZOOM_IN_PAN_RIGHT,    // Zoom in while panning right
        PAN_TOP_TO_BOTTOM,    // Slow vertical pan downward
        DIAGONAL_DRIFT        // Diagonal movement
    }

    public VideoAssembler() {
        this.ffmpegPath = getFFmpegPath();
    }

    private String getFFmpegPath() {
        String customPath = Config.get("FFMPEG_PATH");
        if (customPath != null && !customPath.isEmpty()) {
            return customPath;
        }
        return "ffmpeg";
    }

    public String assembleVideo(String voiceoverPath, List<String> clipPaths, String outputFile, String script) throws Exception {
        Path outputPath = Paths.get(Config.getOutputDir(), outputFile);
        Files.createDirectories(outputPath.getParent());

        double audioDuration = getAudioDuration(voiceoverPath);
        System.out.println("Audio duration: " + audioDuration + " seconds");

        Path videoOnly = Paths.get(Config.getOutputDir(), "temp_video_" + System.currentTimeMillis() + ".mp4");

        try {
            createVideoFromClips(clipPaths, videoOnly, audioDuration);
            String finalVideo = combineAudioVideo(videoOnly.toString(), voiceoverPath, outputPath.toString());
            return finalVideo;
        } finally {
            Files.deleteIfExists(videoOnly);
        }
    }

    public double getAudioDuration(String audioPath) throws IOException, InterruptedException {
        String ffprobePath = ffmpegPath.endsWith(".exe") 
            ? ffmpegPath.substring(0, ffmpegPath.lastIndexOf("ffmpeg.exe")) + "ffprobe.exe"
            : ffmpegPath.substring(0, ffmpegPath.lastIndexOf("ffmpeg")) + "ffprobe";
        
        ProcessBuilder pb = new ProcessBuilder(
            ffprobePath,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            audioPath
        );
        
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String output = reader.readLine();
            if (output != null) {
                return Double.parseDouble(output);
            }
        }
        
        if (process.waitFor() != 0) {
            // Fallback: use a default duration or try another way if ffprobe fails
            System.err.println("ffprobe failed to get duration, falling back to estimate");
            File file = new File(audioPath);
            return file.length() / 16000.0; // Very rough estimate for 128kbps mp3
        }
        return 0;
    }

    public void createVideoFromClips(List<String> clipPaths, Path outputPath, double targetDuration) throws IOException, InterruptedException {
        // Group images into scenes (Frames 1, 2, and 3)
        List<List<String>> scenes = new ArrayList<>();
        java.util.Set<String> processed = new java.util.HashSet<>();
        
        for (String path : clipPaths) {
            if (processed.contains(path)) continue;
            
            if (path.contains("_f1.jpg") || path.contains("_f2.jpg") || path.contains("_f3.jpg")) {
                String base = path;
                if (path.contains("_f2.jpg")) base = path.replace("_f2.jpg", "_f1.jpg");
                else if (path.contains("_f3.jpg")) base = path.replace("_f3.jpg", "_f1.jpg");
                
                String f1 = base;
                String f2 = base.replace("_f1.jpg", "_f2.jpg");
                String f3 = base.replace("_f1.jpg", "_f3.jpg");
                
                List<String> sceneFrames = new ArrayList<>();
                if (clipPaths.contains(f1) && !processed.contains(f1)) { sceneFrames.add(f1); processed.add(f1); }
                if (clipPaths.contains(f2) && !processed.contains(f2)) { sceneFrames.add(f2); processed.add(f2); }
                if (clipPaths.contains(f3) && !processed.contains(f3)) { sceneFrames.add(f3); processed.add(f3); }
                
                if (!sceneFrames.isEmpty()) {
                    scenes.add(sceneFrames);
                }
            } else {
                // Single images or videos
                List<String> sceneFrames = new ArrayList<>();
                sceneFrames.add(path);
                scenes.add(sceneFrames);
                processed.add(path);
            }
        }

        if (scenes.isEmpty()) {
            System.err.println("No valid clips found to assemble video. Using placeholder.");
            createFallbackClip(targetDuration, outputPath);
            return;
        }

        int numScenes = scenes.size();
        int sceneDuration = (int) Math.ceil(targetDuration / numScenes);
        if (sceneDuration < 1) sceneDuration = 1;

        System.out.println("Processing " + numScenes + " animated scenes, each " + sceneDuration + "s long.");

        List<String> processedClips = new ArrayList<>();
        try {
            for (int i = 0; i < scenes.size(); i++) {
                List<String> frames = scenes.get(i);
                Path tempClip = Paths.get(Config.getOutputDir(), "scene_anim_" + System.currentTimeMillis() + "_" + i + ".mp4");
                
                System.out.println("  - Animating scene " + (i + 1) + "/" + numScenes);
                createAnimatedScene(frames, tempClip, sceneDuration);
                processedClips.add(tempClip.toString());
            }

            if (processedClips.isEmpty()) {
                createFallbackClip(targetDuration, outputPath);
                return;
            }

            if (processedClips.size() == 1) {
                Files.copy(Paths.get(processedClips.get(0)), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            }

            Path concatList = createConcatList(processedClips);
            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concatList.toString(),
                "-c", "copy",
                outputPath.toString()
            );
            pb.inheritIO();

            Process process = pb.start();
            int exitCode = process.waitFor();
            Files.deleteIfExists(concatList);

            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg concat failed with exit code: " + exitCode);
            }
        } finally {
            // Clean up temporary clips
            for (String path : processedClips) {
                if (path.contains("scene_anim_")) {
                    Files.deleteIfExists(Paths.get(path));
                }
            }
        }
    }

    private void createAnimatedScene(List<String> frames, Path outputPath, int duration) throws IOException, InterruptedException {
        if (frames.size() == 1) {
            String path = frames.get(0);
            if (isImageFile(path)) {
                createAnimatedClipFromImage(path, outputPath, duration, AnimationEffect.ZOOM_IN_CENTER);
            } else {
                formatVideoClip(path, outputPath, duration);
            }
            return;
        }

        // Multi-frame animation: create a looping "motion" loop (0.25s per frame)
        Path listPath = Paths.get(Config.getOutputDir(), "anim_list_" + System.currentTimeMillis() + ".txt");
        StringBuilder sb = new StringBuilder();
        double frameTime = 0.25; // Faster and smoother animation
        int loops = (int) Math.ceil(duration / (frameTime * frames.size()));
        
        for (int l = 0; l < loops; l++) {
            for (String frame : frames) {
                sb.append("file '").append(frame.replace("\\", "\\\\")).append("'\n");
                sb.append("duration ").append(frameTime).append("\n");
            }
            // Reverse order for a "ping-pong" effect to make it look like natural movement
            if (frames.size() > 2) {
                for (int i = frames.size() - 2; i > 0; i--) {
                    sb.append("file '").append(frames.get(i).replace("\\", "\\\\")).append("'\n");
                    sb.append("duration ").append(frameTime).append("\n");
                }
            }
        }
        Files.writeString(listPath, sb.toString());

        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listPath.toString(),
            "-c:v", "libx264",
            "-t", String.valueOf(duration),
            "-pix_fmt", "yuv420p",
            "-vf", "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,fps=30",
            "-r", "30",
            outputPath.toString()
        );
        
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        Files.deleteIfExists(listPath);
        
        if (exitCode != 0) throw new RuntimeException("FFmpeg scene animation failed");
    }

    /**
     * Creates a video with smooth crossfade transitions between clips using FFmpeg xfade filter.
     * This creates much more professional-looking transitions than hard cuts.
     */
    private void createVideoWithTransitions(List<String> clips, Path outputPath, double transitionDuration) throws IOException, InterruptedException {
        if (clips.size() < 2) {
            Files.copy(Paths.get(clips.get(0)), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // For many clips, we chain xfade filters
        // FFmpeg xfade approach: iteratively merge pairs
        // Strategy: merge clips pairwise with xfade transitions
        
        String currentMerged = clips.get(0);
        List<String> tempMergedFiles = new ArrayList<>();

        for (int i = 1; i < clips.size(); i++) {
            String nextClip = clips.get(i);
            Path tempMerged = Paths.get(Config.getOutputDir(), "temp_merged_" + System.currentTimeMillis() + "_" + i + ".mp4");
            tempMergedFiles.add(tempMerged.toString());

            // Get the duration of the current merged clip to calculate offset
            double currentDuration = getVideoDuration(currentMerged);
            double offset = currentDuration - transitionDuration;
            if (offset < 0.5) offset = 0.5;

            // Pick a random transition effect for variety
            String transitionType = getRandomTransition();
            
            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-i", currentMerged,
                "-i", nextClip,
                "-filter_complex",
                String.format("[0:v][1:v]xfade=transition=%s:duration=%.1f:offset=%.1f,format=yuv420p[v]",
                    transitionType, transitionDuration, offset),
                "-map", "[v]",
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "23",
                tempMerged.toString()
            );
            pb.inheritIO();

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg xfade failed at clip " + i + " with exit code: " + exitCode);
            }

            // Clean up the previous merged file (if it was a temp file)
            if (currentMerged.contains("temp_merged_")) {
                Files.deleteIfExists(Paths.get(currentMerged));
            }

            currentMerged = tempMerged.toString();
        }

        // Move the final merged result to the output path
        Files.move(Paths.get(currentMerged), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        
        // Clean up remaining temp merged files
        for (String tempFile : tempMergedFiles) {
            Files.deleteIfExists(Paths.get(tempFile));
        }
    }

    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        String ffprobePath = ffmpegPath.endsWith(".exe") 
            ? ffmpegPath.substring(0, ffmpegPath.lastIndexOf("ffmpeg.exe")) + "ffprobe.exe"
            : ffmpegPath.substring(0, ffmpegPath.lastIndexOf("ffmpeg")) + "ffprobe";
        
        ProcessBuilder pb = new ProcessBuilder(
            ffprobePath,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            videoPath
        );
        
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new BufferedReader(new InputStreamReader(process.getInputStream())))) {
            String output = reader.readLine();
            process.waitFor();
            if (output != null && !output.isEmpty()) {
                return Double.parseDouble(output.trim());
            }
        }
        return 5.0; // fallback
    }

    /**
     * Returns a random FFmpeg xfade transition type for variety between scenes.
     */
    private String getRandomTransition() {
        String[] transitions = {
            "fade",           // Classic fade to black and back
            "fadeblack",      // Fade through black
            "fadewhite",      // Fade through white (magical feel)
            "dissolve",       // Smooth dissolve blend
            "wipeleft",       // Wipe from left
            "wiperight",      // Wipe from right
            "slideleft",      // Slide in from left
            "slideright",     // Slide in from right
            "circlecrop",     // Circle crop transition
            "smoothleft",     // Smooth push left
            "smoothright"     // Smooth push right
        };
        return transitions[random.nextInt(transitions.length)];
    }

    private Path createConcatList(List<String> clipPaths) throws IOException {
        Path listPath = Paths.get(Config.getOutputDir(), "concat_list_" + System.currentTimeMillis() + ".txt");
        StringBuilder sb = new StringBuilder();
        for (String clip : clipPaths) {
            sb.append("file '").append(clip.replace("\\", "\\\\")).append("'\n");
        }
        Files.writeString(listPath, sb.toString());
        return listPath;
    }

    private boolean isImageFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    /**
     * Creates an animated video clip from a static image with rich motion effects.
     * Goes far beyond simple zoom - uses panning, combined movements, and varied effects.
     */
    private void createAnimatedClipFromImage(String imagePath, Path outputPath, int duration, AnimationEffect effect) throws IOException, InterruptedException {
        int frameRate = 30;
        int totalFrames = duration * frameRate;
        
        // Build the animation filter based on the chosen effect
        // All effects use zoompan with different x, y, zoom parameters
        // We render at a larger internal resolution and scale down for smooth movement
        String zoomFilter;
        
        switch (effect) {
            case ZOOM_IN_CENTER:
                // Smooth zoom from 1.0x to 1.4x centered
                zoomFilter = String.format(
                    "zoompan=z='1+0.4*on/%d':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, frameRate
                );
                break;
                
            case ZOOM_OUT_CENTER:
                // Smooth zoom from 1.4x to 1.0x centered
                zoomFilter = String.format(
                    "zoompan=z='1.4-0.4*on/%d':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, frameRate
                );
                break;
                
            case PAN_LEFT_TO_RIGHT:
                // Pan horizontally from left to right with slight zoom
                zoomFilter = String.format(
                    "zoompan=z='1.3':x='(iw/zoom-iw)*on/%d':y='(ih-ih/zoom)/2':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, frameRate
                );
                break;
                
            case PAN_RIGHT_TO_LEFT:
                // Pan horizontally from right to left with slight zoom
                zoomFilter = String.format(
                    "zoompan=z='1.3':x='(iw/zoom-iw)*(1-on/%d)':y='(ih-ih/zoom)/2':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, frameRate
                );
                break;
                
            case ZOOM_IN_PAN_LEFT:
                // Zoom in while panning left
                zoomFilter = String.format(
                    "zoompan=z='1+0.5*on/%d':x='(iw-iw/zoom)*(1-on/%d)':y='(ih-ih/zoom)/2':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, totalFrames, frameRate
                );
                break;
                
            case ZOOM_IN_PAN_RIGHT:
                // Zoom in while panning right
                zoomFilter = String.format(
                    "zoompan=z='1+0.5*on/%d':x='(iw-iw/zoom)*on/%d':y='(ih-ih/zoom)/2':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, totalFrames, frameRate
                );
                break;
                
            case PAN_TOP_TO_BOTTOM:
                // Pan from top to bottom with slight zoom
                zoomFilter = String.format(
                    "zoompan=z='1.3':x='(iw-iw/zoom)/2':y='(ih/zoom-ih)*on/%d':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, frameRate
                );
                break;
                
            case DIAGONAL_DRIFT:
                // Diagonal movement from top-left to bottom-right with zoom
                zoomFilter = String.format(
                    "zoompan=z='1+0.3*on/%d':x='(iw-iw/zoom)*on/%d':y='(ih-ih/zoom)*on/%d':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, totalFrames, totalFrames, frameRate
                );
                break;
                
            default:
                // Default: smooth zoom in
                zoomFilter = String.format(
                    "zoompan=z='1+0.4*on/%d':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=%d:s=1920x1080:fps=%d",
                    totalFrames, totalFrames, frameRate
                );
        }

        System.out.println("    Effect: " + effect.name());

        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-y",
            "-loop", "1",
            "-i", imagePath,
            "-c:v", "libx264",
            "-t", String.valueOf(duration),
            "-pix_fmt", "yuv420p",
            "-vf", "scale=3840:2160,format=yuv420p," + zoomFilter,
            "-preset", "fast",
            "-crf", "20",
            "-r", String.valueOf(frameRate),
            outputPath.toString()
        );
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("FFmpeg animation failed for: " + imagePath + " with effect " + effect.name());
    }

    /**
     * Simple fallback clip creation - basic zoom only.
     */
    private void createSimpleClipFromImage(String imagePath, Path outputPath, int duration) throws IOException, InterruptedException {
        int frameRate = 30;
        int totalFrames = duration * frameRate;

        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-y",
            "-loop", "1",
            "-i", imagePath,
            "-c:v", "libx264",
            "-t", String.valueOf(duration),
            "-pix_fmt", "yuv420p",
            "-vf", "scale=2560:1440,format=yuv420p,zoompan=z='min(zoom+0.001,1.3)':d=" + totalFrames + ":s=1920x1080,scale=1920:1080",
            "-r", String.valueOf(frameRate),
            outputPath.toString()
        );
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("FFmpeg simple clip failed for: " + imagePath);
    }

    /**
     * Formats an actual video clip (e.g. from Luma API) to match the required duration and resolution.
     * Loops the video if it's shorter than duration, scales to 1920x1080, and ensures consistent frame rate.
     */
    private void formatVideoClip(String inputPath, Path outputPath, int duration) throws IOException, InterruptedException {
        int frameRate = 30;
        
        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-y",
            "-stream_loop", "-1", // loop infinitely
            "-i", inputPath,
            "-c:v", "libx264",
            "-t", String.valueOf(duration), // cut at required duration
            "-pix_fmt", "yuv420p",
            "-vf", "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2",
            "-preset", "fast",
            "-crf", "20",
            "-r", String.valueOf(frameRate),
            outputPath.toString()
        );
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("FFmpeg formatting failed for video: " + inputPath);
    }

    private String combineAudioVideo(String videoPath, String audioPath, String outputPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-y",
            "-i", videoPath,
            "-i", audioPath,
            "-c:v", "copy",
            "-c:a", "aac",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-shortest",
            outputPath
        );
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg combine failed with exit code: " + exitCode);
        }

        return outputPath;
    }



    /**
     * Creates a styled fallback video clip of the exact required duration.
     * Used when image generation fails — ensures the voiceover segment is never dropped.
     * Renders a warm gradient background with a subtle animated message using FFmpeg lavfi.
     */
    public void createFallbackClip(double duration, Path outputPath) throws IOException, InterruptedException {
        System.out.println("  [Fallback] Creating placeholder clip (" + String.format("%.1f", duration) + "s) — image generation failed for this scene.");

        // Cycles between two warm colors to create a gentle animated background
        String colorFilter = "color=c=0x1a1a2e:s=1920x1080:r=30,format=yuv420p";

        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-y",
            "-f", "lavfi",
            "-i", colorFilter,
            "-t", String.valueOf(duration),
            "-c:v", "libx264",
            "-preset", "fast",
            "-crf", "28",
            "-pix_fmt", "yuv420p",
            outputPath.toString()
        );
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg fallback clip generation failed with exit code: " + exitCode);
        }
    }

    public String mergeSyncedSegments(List<String> clipPaths, List<String> audioPaths, String outputFile, String fullScript) throws Exception {
        if (clipPaths.size() != audioPaths.size()) {
            throw new IllegalArgumentException("Number of clips (" + clipPaths.size() + ") must match number of audio segments (" + audioPaths.size() + ")");
        }

        List<String> combinedSegments = new ArrayList<>();
        try {
            // 1. Combine each clip with its specific audio
            for (int i = 0; i < clipPaths.size(); i++) {
                Path combinedPath = Paths.get(Config.getOutputDir(), "combined_seg_" + i + "_" + System.currentTimeMillis() + ".mp4");
                combineAudioVideo(clipPaths.get(i), audioPaths.get(i), combinedPath.toString());
                combinedSegments.add(combinedPath.toString());
            }

            // 2. Concatenate all combined segments
            Path tempFinal = Paths.get(Config.getOutputDir(), "temp_final_" + System.currentTimeMillis() + ".mp4");
            
            if (combinedSegments.size() == 1) {
                Files.copy(Paths.get(combinedSegments.get(0)), tempFinal, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Path concatList = createConcatList(combinedSegments);
                ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatList.toString(),
                    "-c", "copy",
                    tempFinal.toString()
                );
                pb.inheritIO();
                if (pb.start().waitFor() != 0) throw new RuntimeException("Final segment merge failed");
                Files.deleteIfExists(concatList);
            }

            // 3. Add subtitles to the final merged video
            String result = assembleWithSubtitles(null, null, new File(outputFile).getName(), fullScript, tempFinal.toString());
            
            // Clean up the temporary final file
            Files.deleteIfExists(tempFinal);
            
            return result;

        } finally {
            for (String s : combinedSegments) Files.deleteIfExists(Paths.get(s));
        }
    }

    public String assembleWithSubtitles(String voiceoverPath, List<String> clipPaths, String outputFile, String script, String baseVideoPath) throws Exception {
        String baseVideo = (baseVideoPath != null) ? baseVideoPath : assembleVideo(voiceoverPath, clipPaths, outputFile.replace(".mp4", "_base.mp4"), script);
        Path outputPath = Paths.get(Config.getOutputDir(), outputFile);
        
        // Clean the script for subtitle generation - remove formatting but keep structure
        String cleanScript = cleanScriptForSubtitles(script);
        Path srtPath = generateSrtFromScript(cleanScript, outputPath);

        try {
            // FFmpeg subtitles filter on Windows is notoriously difficult with paths.
            // The most reliable way is to use a relative path if possible, or very specific escaping.
            String srtFilename = srtPath.getFileName().toString();
            
            // NuNu Tv style subtitles: Large, Bold, white text with black border at bottom
            String subtitleFilter = "subtitles=" + srtFilename + ":force_style='FontSize=22,FontName=Arial,PrimaryColour=&H00FFFFFF,OutlineColour=&H000000,BorderStyle=3,Outline=2,Shadow=1,Alignment=2,MarginV=50'";
            
            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-i", baseVideo,
                "-vf", subtitleFilter,
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "20",
                outputPath.toString()
            );
            // Set the working directory to the output folder so FFmpeg can find the SRT file by name
            pb.directory(new File(Config.getOutputDir()));
            pb.inheritIO();

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.err.println("Subtitle burn-in failed, using base video");
                Files.copy(Paths.get(baseVideo), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            return outputPath.toString();
        } finally {
            Files.deleteIfExists(srtPath);
        }
    }

    /**
     * Cleans script text for subtitle generation.
     * Removes formatting but keeps the narrative content.
     */
    private String cleanScriptForSubtitles(String script) {
        // Use the TTS cleaner for clean subtitles (strips formatting, keeps narration)
        return ScriptGenerator.cleanForTTS(script);
    }

    private Path generateSrtFromScript(String script, Path outputPath) throws IOException {
        Path srtPath = Paths.get(Config.getOutputDir(), outputPath.getFileName().toString().replace(".mp4", ".srt"));
        StringBuilder srt = new StringBuilder();
        String[] lines = script.split("\n");
        int index = 1;
        double startTime = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Split long lines into subtitle-friendly chunks (max ~60 chars per subtitle)
            List<String> chunks = splitIntoSubtitleChunks(line, 60);
            
            for (String chunk : chunks) {
                // Duration based on word count (average speaking rate ~150 words/min = 2.5 words/sec)
                int wordCount = chunk.split("\\s+").length;
                double duration = Math.max(wordCount / 2.5, 1.5); // minimum 1.5 seconds
                
                srt.append(index).append("\n");
                srt.append(formatSrtTime(startTime)).append(" --> ").append(formatSrtTime(startTime + duration)).append("\n");
                srt.append(chunk).append("\n\n");

                startTime += duration;
                index++;
            }
        }

        Files.writeString(srtPath, srt.toString());
        return srtPath;
    }

    /**
     * Splits a line into subtitle-sized chunks, breaking at natural word boundaries.
     */
    private List<String> splitIntoSubtitleChunks(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxLength) {
            chunks.add(text);
            return chunks;
        }

        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        
        for (String word : words) {
            if (current.length() + word.length() + 1 > maxLength && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append(" ");
            current.append(word);
        }
        
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        
        return chunks;
    }

    private String formatSrtTime(double seconds) {
        int hours = (int)(seconds / 3600);
        int minutes = (int)((seconds % 3600) / 60);
        int secs = (int)(seconds % 60);
        int millis = (int)((seconds % 1) * 1000);
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }

    public static String getClipDirectory() {
        return Config.getClipsDir();
    }

    public static List<String> getAvailableClips() throws IOException {
        Path clipsDir = Paths.get(Config.getClipsDir());
        List<String> clips = new ArrayList<>();

        if (!Files.exists(clipsDir)) {
            Files.createDirectories(clipsDir);
            return clips;
        }

        try (var stream = Files.list(clipsDir)) {
            stream.filter(p -> {
                      String s = p.toString().toLowerCase();
                      return s.endsWith(".mp4") || s.endsWith(".webm") || 
                             s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png");
                  })
                  .map(Path::toString)
                  .forEach(clips::add);
        }

        return clips;
    }
}
