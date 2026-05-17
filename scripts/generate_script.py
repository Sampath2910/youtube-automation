import os
import json
from anthropic import Anthropic
from dotenv import load_dotenv

load_dotenv()

def generate_kids_story(topic):
    client = Anthropic(api_key=os.getenv("ANTHROPIC_API_KEY"))
    
    prompt = f"""Write a short, engaging, and educational kids story about '{topic}'. 
    The story should be suitable for a 1-minute YouTube Short or Instagram Reel.
    Format the output as a JSON object with:
    1. 'title': A catchy title.
    2. 'script': The full narration text.
    3. 'scenes': A list of scene descriptions for image generation.
    4. 'tags': 5 relevant hashtags.
    """
    
    message = client.messages.create(
        model="claude-3-haiku-20240307",
        max_tokens=1000,
        messages=[
            {"role": "user", "content": prompt}
        ]
    )
    
    # Extract the JSON part from the response
    response_text = message.content[0].text
    # Basic JSON extraction logic
    start = response_text.find('{')
    end = response_text.rfind('}') + 1
    data = json.loads(response_text[start:end])
    
    with open('assets/script.json', 'w') as f:
        json.dump(data, f, indent=4)
    
    print(f"Script generated for: {topic}")
    return data

if __name__ == "__main__":
    import sys
    topic = sys.argv[1] if len(sys.argv) > 1 else "A curious little elephant in the jungle"
    generate_kids_story(topic)
