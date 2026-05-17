import subprocess
import sys
import os

def run_pipeline(topic):
    print(f"--- Starting Pipeline for: {topic} ---")
    
    # 1. Generate Script
    print("\n[Step 1/3] Generating Script...")
    subprocess.run([sys.executable, "scripts/generate_script.py", topic], check=True)
    
    # 2. Generate Voiceover
    print("\n[Step 2/3] Generating Voiceover...")
    subprocess.run([sys.executable, "scripts/generate_voice.py"], check=True)
    
    # 3. Assemble Video
    print("\n[Step 3/4] Assembling Video...")
    subprocess.run([sys.executable, "scripts/assemble_video.py"], check=True)
    
    # 4. Upload to YouTube
    print("\n[Step 4/4] Uploading to YouTube...")
    try:
        subprocess.run([sys.executable, "scripts/youtube_upload.py"], check=True)
    except Exception as e:
        print(f"Upload failed (likely missing credentials): {e}")
        print("Note: You need 'client_secrets.json' to upload automatically.")
    
    print("\n--- Pipeline Completed! ---")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python run_all.py 'Your Story Topic'")
    else:
        topic = sys.argv[1]
        run_pipeline(topic)
