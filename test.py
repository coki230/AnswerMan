import asyncio
from google import genai
from google.genai import types

client = genai.Client(api_key="AIzaSyCl8_v210rmIdix-cHyz03xwR0k1NKlpU4")


with open('/Users/coki/Desktop/截屏2026-05-25 11.28.58.png', 'rb') as f:
    image_bytes = f.read()
response = client.models.generate_content(
    model='gemini-3.5-flash',
    contents=[
        types.Part.from_bytes(
            data=image_bytes,
            mime_type='image/jpeg',
        ),
        '回答图片上的问题.'
    ]
)

print(response.text)