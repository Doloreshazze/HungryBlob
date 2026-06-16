import wave
import math
import struct
import os

sample_rate = 44100

def generate_wav(filename, duration, generate_sample):
    print(f"Generating {filename}...")
    num_samples = int(sample_rate * duration)
    with wave.open(filename, 'w') as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        for i in range(num_samples):
            t = float(i) / sample_rate
            sample = generate_sample(t, num_samples, i)
            # Clamp to 16-bit int
            clamped = max(-32768, min(32767, int(sample * 32767)))
            wav_file.writeframes(struct.pack('h', clamped))

def eat_sound(t, num_samples, i):
    # Short pop, sine wave with frequency envelope (pitch drop)
    freq = 600 - 400 * (t / 0.1) # Drops from 600Hz to 200Hz
    envelope = math.exp(-t * 20)
    return math.sin(2 * math.pi * freq * t) * envelope * 0.5

def split_sound(t, num_samples, i):
    # Short upward chirp
    freq = 300 + 800 * (t / 0.15) # Rises from 300Hz to 1100Hz
    envelope = math.exp(-t * 15)
    return math.sin(2 * math.pi * freq * t) * envelope * 0.5

def chase_sound(t, num_samples, i):
    # Low frequency pulsing/buzz
    freq = 80 + 20 * math.sin(2 * math.pi * 10 * t) # Tremolo
    envelope = math.exp(-t * 5)
    # Square wave
    val = 1.0 if math.sin(2 * math.pi * freq * t) > 0 else -1.0
    return val * envelope * 0.3

def bgm_sound(t, num_samples, i):
    # Simple loopable melody for bgm (2 seconds loop)
    # Bass line + simple arpeggio
    notes = [220.0, 261.63, 329.63, 261.63]
    note_idx = int((t % 2.0) / 0.5) % 4
    freq1 = notes[note_idx]
    freq2 = freq1 / 2.0
    
    env1 = math.exp(-((t % 0.5) * 4))
    env2 = 1.0
    
    val1 = math.sin(2 * math.pi * freq1 * t) * env1 * 0.2
    val2 = math.sin(2 * math.pi * freq2 * t) * env2 * 0.15
    return val1 + val2

def main():
    target_dir = r"C:\Users\79270\.gemini\antigravity\scratch\HungryBlob\app\src\main\res\raw"
    os.makedirs(target_dir, exist_ok=True)
    
    generate_wav(os.path.join(target_dir, "eat.wav"), 0.15, eat_sound)
    generate_wav(os.path.join(target_dir, "split.wav"), 0.2, split_sound)
    generate_wav(os.path.join(target_dir, "chase.wav"), 0.5, chase_sound)
    generate_wav(os.path.join(target_dir, "bgm.wav"), 4.0, bgm_sound)
    print("All sounds generated.")

if __name__ == "__main__":
    main()
