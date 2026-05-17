package com.youtube.automation;

public class CharacterConfig {
    // Main character description for consistency
    public static final String MAIN_CHARACTER_NAME = "Sammy";
    public static final String MAIN_CHARACTER_DESCRIPTION = "a cute 5-year-old boy with big expressive eyes, curly brown hair, wearing a bright green t-shirt with a small star on it and blue shorts";
    
    // Artistic style for the channel (NuNu Tv style: 3D, Pixar/Disney inspired)
    public static final String CHANNEL_ART_STYLE = "3D Pixar style animation, Disney-inspired character design, vibrant colors, 4k render, Unreal Engine 5 style, high detail, expressive facial expressions, soft studio lighting";
    
    // Animation frame suffixes to create more realistic movement
    public static final String[] MOVEMENT_POSES = {
        "waving and smiling at camera",
        "talking happily with hands moving",
        "looking surprised with big expressive eyes",
        "walking forward with a happy face",
        "laughing with eyes closed and hands up",
        "pointing at something interesting",
        "nodding and smiling",
        "tilting head curiously"
    };

    public static String getCharacterPromptPrefix() {
        return "Character: " + MAIN_CHARACTER_NAME + ", " + MAIN_CHARACTER_DESCRIPTION + ". ";
    }
    
    public static String getPosePrompt(int frameIndex) {
        return "Pose: " + MOVEMENT_POSES[frameIndex % MOVEMENT_POSES.length] + ". ";
    }
    
    public static String getFullStylePrompt() {
        return "Style: " + CHANNEL_ART_STYLE + ". ";
    }
}
