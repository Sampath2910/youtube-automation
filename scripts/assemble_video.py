import os
import json
from moviepy import (
    ImageClip, AudioFileClip, VideoFileClip, 
    TextClip, CompositeVideoClip, concatenate_videoclips,
    ColorClip
)

def create_video(script_file='assets/script.json', voice_file='assets/voiceover.mp3', music_file=None):
    with open(script_file, 'r') as f:
        data = json.load(f)
    
    audio = AudioFileClip(voice_file)
    duration = audio.duration
    
    # Check for images in assets/images
    image_folder = 'assets/images'
    images = sorted([os.path.join(image_folder, img) for img in os.listdir(image_folder) if img.endswith(('.png', '.jpg', '.jpeg'))])
    
    if not images:
        print("No images found in assets/images. Creating a background color video.")
        # Create a simple colored background if no images exist
        clip = ColorClip(size=(1080, 1920), color=(100, 100, 255)).with_duration(duration)
    else:
        # Calculate duration per image
        img_duration = duration / len(images)
        # In MoviePy 2.x, resize is a method of the clip or a function
        clips = []
        for img in images:
            c = ImageClip(img).with_duration(img_duration)
            # Simple resize to fit 1080p width
            w, h = c.size
            factor = 1080 / w
            c = c.resized(factor)
            clips.append(c)
        clip = concatenate_videoclips(clips, method="compose")

    # Add background music if provided
    if music_file and os.path.exists(music_file):
        bg_music = AudioFileClip(music_file).with_volume_scaled(0.1).with_duration(duration)
        # Composite audio
        from moviepy.audio.AudioClip import CompositeAudioClip
        final_audio = CompositeAudioClip([audio, bg_music])
        clip = clip.with_audio(final_audio)
    else:
        clip = clip.with_audio(audio)

    # Output file
    output_path = f"output/{data['title'].replace(' ', '_')}.mp4"
    clip.write_videofile(output_path, fps=24, codec="libx264")
    print(f"Video saved to {output_path}")

if __name__ == "__main__":
    # Ensure directories exist
    os.makedirs('output', exist_ok=True)
    # For now, we assume images are placed in assets/images
    create_video()
