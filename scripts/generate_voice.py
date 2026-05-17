import asyncio
import edge_tts
import json
import os

async def generate_voiceover(script_file='assets/script.json', output_file='assets/voiceover.mp3'):
    if not os.path.exists(script_file):
        print("Script file not found!")
        return

    with open(script_file, 'r') as f:
        data = json.load(f)
    
    text = data['script']
    
    # You can change the voice. 'en-US-GuyNeural' or 'en-US-AnaNeural' are good for kids content.
    voice = "en-US-AnaNeural" 
    
    communicate = edge_tts.Communicate(text, voice)
    await communicate.save(output_file)
    print(f"Voiceover saved to {output_file}")

if __name__ == "__main__":
    asyncio.run(generate_voiceover())
