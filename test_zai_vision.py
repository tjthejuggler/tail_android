#!/usr/bin/env python3
"""
Test script to verify Z.ai vision API calls work correctly.
Uses the same request format as our Android app's VisionProcessingService.
Uses only Python stdlib (no pip install needed).

Usage:
    python3 test_zai_vision.py
"""

import base64
import json
import sys
import urllib.request
import urllib.error

# ── Configuration ────────────────────────────────────────────────────
API_KEY = "41b28a65f2e74e4aa09b3cae62101d0f.Mi8KZUuATcfaxKkD"
BASE_URL = "https://api.z.ai/api/coding/paas/v4"
MODEL = "glm-4.6v"
IMAGE_PATH = "app/src/main/assets/banana.jpeg"

# Build the full endpoint URL (same logic as VisionProcessingService.buildEndpointUrl)
ENDPOINT = f"{BASE_URL}/chat/completions"

# ── System prompt (same as VisionProcessingService.SYSTEM_PROMPT_TEMPLATE) ──
SYSTEM_PROMPT = """You are an advanced, context-aware habit tracking assistant specializing in image recognition, nutritional analysis, and structured metadata extraction.

### USER CONTEXT & DIETARY RULES:
(No custom dietary rules specified. Use general nutritional knowledge.)

### PROCESSING INSTRUCTIONS:
1. First, classify the primary subject of the provided image into one of the following categories:
   - "FOOD_MEAL": The image contains a dish, snack, beverage, or food item.
   - "NON_FOOD_HABIT": The image depicts a non-food habit activity (e.g., book, gym equipment, task list).
   - "UNCERTAIN_OTHER": The image does not clearly depict a trackable habit or meal.

2. If the category is "FOOD_MEAL", perform a granular breakdown adhering strictly to any User Dietary Rules above:
   - Identify the meal/snack name.
   - Estimate ingredients and portion sizes.
   - Calculate estimated calories and primary macronutrients (Protein, Carbs, Fats).
   - Summarize the item in 1-2 concise sentences for a habit log entry.

3. Format the response strictly as valid, raw JSON matching the JSON Schema provided below. Do not wrap in markdown code blocks, and do not add conversational text.

### JSON OUTPUT SCHEMA:
{
  "classification": "FOOD_MEAL" | "NON_FOOD_HABIT" | "UNCERTAIN_OTHER",
  "confidence_score": 0.0 to 1.0,
  "food_data": {
    "title": "String (Name of meal)",
    "summary": "String (Short description)",
    "is_vegan_verified": boolean,
    "estimated_calories": number,
    "macronutrients": {
      "protein_grams": number,
      "carbs_grams": number,
      "fat_grams": number
    },
    "ingredients_detected": ["String"],
    "health_notes": "String or null"
  },
  "non_food_data": {
    "detected_activity": "String or null",
    "suggested_action": "String or null"
  },
  "processing_notes": "String"
}"""


def encode_image(path):
    """Read and base64-encode an image file."""
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def main():
    print(f"🔗 Endpoint: {ENDPOINT}")
    print(f"🤖 Model: {MODEL}")
    print(f"🍌 Image: {IMAGE_PATH}")
    print()

    # Encode the image
    print("Encoding image…")
    b64 = encode_image(IMAGE_PATH)
    data_url = f"data:image/jpeg;base64,{b64}"
    print(f"  Image size: {len(b64)} bytes (base64)")
    print()

    # Build request body (same format as VisionProcessingService.buildRequestBody)
    payload = {
        "model": MODEL,
        "messages": [
            {
                "role": "system",
                "content": SYSTEM_PROMPT,
            },
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": "Analyse the image below.\nCurrent local datetime: 2026-08-08 17:30",
                    },
                    {
                        "type": "image_url",
                        "image_url": {"url": data_url},
                    },
                ],
            },
        ],
        "temperature": 0.2,
        "max_tokens": 1000,
    }

    body_json = json.dumps(payload).encode("utf-8")

    req = urllib.request.Request(
        ENDPOINT,
        data=body_json,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {API_KEY}",
        },
        method="POST",
    )

    # Send the request
    print("Sending request to Z.ai…")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            status = resp.status
            response_text = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        status = e.code
        response_text = e.read().decode("utf-8")
    except Exception as e:
        print(f"❌ Request failed: {e}")
        sys.exit(1)

    print(f"HTTP Status: {status}")
    print()

    if status not in (200, 201):
        print(f"❌ Error response:")
        print(response_text[:2000])
        sys.exit(1)

    # Parse the response
    data = json.loads(response_text)

    # Extract content (same logic as VisionProcessingService.extractAssistantContent)
    choices = data.get("choices", [])
    if not choices:
        print("❌ No choices in response")
        print(json.dumps(data, indent=2)[:2000])
        sys.exit(1)

    message = choices[0].get("message", {})
    content = message.get("content")

    if isinstance(content, list):
        # Concatenate text parts
        text_parts = []
        for part in content:
            if part.get("type") == "text":
                text_parts.append(part.get("text", ""))
        content = "\n".join(text_parts)

    print("─── Raw LLM Response ───")
    print(content)
    print()

    # Try to parse as JSON (same logic as VisionProcessingService.parseVisionResult)
    # Strip markdown code fences if present
    json_str = content.strip()
    if json_str.startswith("```"):
        parts = json_str.split("```")
        if len(parts) >= 3:
            json_str = parts[1]
            json_str = json_str.replace("json", "", 1).replace("JSON", "", 1)
        json_str = json_str.strip()

    try:
        result = json.loads(json_str)
        print("─── Parsed Result ───")
        print(json.dumps(result, indent=2))
        print()

        classification = result.get("classification", "UNKNOWN")
        confidence = result.get("confidence_score", 0)
        food_data = result.get("food_data")

        if classification == "FOOD_MEAL" and food_data:
            print(f"✅ SUCCESS!")
            print(f"   Classification: {classification}")
            print(f"   Confidence: {confidence * 100:.0f}%")
            print(f"   Title: {food_data.get('title', 'N/A')}")
            print(f"   Calories: {food_data.get('estimated_calories', 'N/A')} kcal")
            macros = food_data.get("macronutrients", {})
            print(f"   Protein: {macros.get('protein_grams', 'N/A')}g")
            print(f"   Carbs: {macros.get('carbs_grams', 'N/A')}g")
            print(f"   Fat: {macros.get('fat_grams', 'N/A')}g")
        else:
            print(f"⚠️  Got response but classification was {classification}")
            print(f"   Notes: {result.get('processing_notes', 'N/A')}")
    except json.JSONDecodeError as e:
        print(f"⚠️  Could not parse as JSON: {e}")
        print("The endpoint works, but the model didn't return valid JSON.")

    # Show token usage
    usage = data.get("usage", {})
    if usage:
        print()
        print(f"─── Token Usage ───")
        print(f"   Prompt: {usage.get('prompt_tokens', 'N/A')}")
        print(f"   Completion: {usage.get('completion_tokens', 'N/A')}")
        print(f"   Total: {usage.get('total_tokens', 'N/A')}")


if __name__ == "__main__":
    main()
