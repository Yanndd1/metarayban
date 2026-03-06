#!/usr/bin/env python3
"""
MetaRayBan Voice Assistant
==========================
Uses Ray-Ban Meta V1 glasses as Bluetooth audio I/O with Claude as brain.

Architecture:
  [Ray-Ban Meta] --BT A2DP/HFP--> [Windows PC]
       ^                              |
       |                         sounddevice (capture mic)
       |                              |
       |                         VAD (energy-based, auto-calibrated)
       |                              |
       |                         faster-whisper (ASR, French)
       |                              |
       |                         Claude API (LLM)
       |                              |
       |                         EdgeTTS (fr-FR-HenriNeural)
       |                              |
       +--------- BT audio <--- sounddevice (playback)

Usage:
  python main.py                    # Start with config.yaml
  python main.py --list-devices     # List audio devices
  python main.py -c my_config.yaml  # Custom config
"""

import argparse
import asyncio
import io
import sys
import tempfile
import time
from pathlib import Path

import edge_tts
import miniaudio
import numpy as np
import sounddevice as sd
import yaml
from anthropic import Anthropic
from faster_whisper import WhisperModel


class VoiceAssistant:
    def __init__(self, config_path: str = "config.yaml"):
        with open(config_path, encoding="utf-8") as f:
            self.config = yaml.safe_load(f)

        audio_cfg = self.config["audio"]
        self.sample_rate = audio_cfg["sample_rate"]
        self.input_device = audio_cfg.get("input_device")
        self.output_device = audio_cfg.get("output_device")
        self.vad_sensitivity = audio_cfg.get("vad_sensitivity", 2.5)
        self.silence_duration = audio_cfg.get("silence_duration", 1.5)
        self.vad_threshold = 500  # will be calibrated at startup

        # ASR
        asr_cfg = self.config["asr"]
        print(f"Loading ASR model '{asr_cfg['model']}'...")
        self.whisper = WhisperModel(
            asr_cfg["model"],
            device=asr_cfg.get("device", "cpu"),
            compute_type=asr_cfg.get("compute_type", "int8"),
        )
        self.asr_language = asr_cfg.get("language", "fr")

        # LLM
        self.client = Anthropic()
        self.llm_model = self.config["llm"]["model"]
        self.max_tokens = self.config["llm"].get("max_tokens", 512)
        self.system_prompt = self.config["llm"]["system_prompt"]
        self.conversation: list[dict] = []

        # TTS
        self.tts_voice = self.config["tts"]["voice"]
        self.tts_rate = self.config["tts"].get("rate", "+0%")
        self.tts_volume = self.config["tts"].get("volume", "+0%")

        self.mode = self.config.get("mode", "vad")

    # ─── Audio device helpers ──────────────────────────────────────────

    @staticmethod
    def list_devices():
        """Print all available audio devices."""
        print("\n=== Audio Devices ===")
        devices = sd.query_devices()
        for i, d in enumerate(devices):
            direction = []
            if d["max_input_channels"] > 0:
                direction.append("IN")
            if d["max_output_channels"] > 0:
                direction.append("OUT")
            marker = " <-- default" if i in (
                sd.default.device[0], sd.default.device[1]
            ) else ""
            print(f"  [{i}] {d['name']} ({'/'.join(direction)}){marker}")
        print()

    def _resolve_device(self, device_spec, kind: str) -> int | None:
        """Resolve device spec (index, name substring, or None for default).
        kind should be 'Input' or 'Output' to filter by direction."""
        if device_spec is None:
            return None
        if isinstance(device_spec, int):
            return device_spec
        if isinstance(device_spec, str):
            devices = sd.query_devices()
            is_input = kind.lower() == "input"
            for i, d in enumerate(devices):
                if device_spec.lower() in d["name"].lower():
                    # Filter by direction
                    if is_input and d["max_input_channels"] == 0:
                        continue
                    if not is_input and d["max_output_channels"] == 0:
                        continue
                    print(f"  {kind} device: [{i}] {d['name']}")
                    return i
            print(f"  WARNING: No {kind.lower()} device matching '{device_spec}', using default")
        return None

    # ─── VAD (energy-based, auto-calibrated) ───────────────────────────

    def _record_callback(self, dev: int | None, duration: float) -> np.ndarray:
        """Record audio using callback API (required for WDM-KS BT devices)."""
        import threading
        frames = []
        total_samples = int(duration * self.sample_rate)
        collected = 0
        done = threading.Event()

        def callback(indata, frame_count, time_info, status):
            nonlocal collected
            if status:
                pass  # ignore xruns during calibration
            frames.append(indata[:, 0].copy())
            collected += len(indata)
            if collected >= total_samples:
                done.set()
                raise sd.CallbackStop()

        stream = sd.InputStream(
            device=dev, channels=1, samplerate=self.sample_rate,
            dtype="int16", blocksize=480, callback=callback, latency="high",
        )
        stream.start()
        done.wait(timeout=duration + 3)
        stream.stop()
        stream.close()
        return np.concatenate(frames) if frames else np.array([], dtype=np.int16)

    def calibrate_noise(self, duration: float = 2.0):
        """Measure ambient noise to set VAD threshold."""
        print("Calibration du bruit ambiant (restez silencieux)...")
        dev = self._resolve_device(self.input_device, "Input")
        audio = self._record_callback(dev, duration)
        noise_level = np.abs(audio.astype(np.float64)).mean()
        self.vad_threshold = max(noise_level * self.vad_sensitivity, 200)
        print(f"  Bruit: {noise_level:.0f} | Seuil VAD: {self.vad_threshold:.0f}")

    def _is_speech(self, frame: np.ndarray) -> bool:
        """Check if audio frame contains speech (energy-based)."""
        energy = np.abs(frame.astype(np.float64)).mean()
        return energy > self.vad_threshold

    # ─── Recording ─────────────────────────────────────────────────────

    def listen_vad(self) -> np.ndarray | None:
        """Listen for speech using VAD, record until silence. Returns int16 mono.
        Uses callback API for WDM-KS BT device compatibility."""
        import threading

        frame_ms = 30
        frame_size = int(self.sample_rate * frame_ms / 1000)
        silence_frames_needed = int(self.silence_duration * 1000 / frame_ms)
        min_speech_frames = 3

        dev = self._resolve_device(self.input_device, "Input")

        # State shared with callback
        pre_buffer: list[np.ndarray] = []
        frames: list[np.ndarray] = []
        state = {"phase": "waiting", "speech_count": 0, "silence_count": 0, "total": 0}
        done = threading.Event()

        def callback(indata, frame_count, time_info, status):
            frame = indata[:, 0].copy()

            if state["phase"] == "waiting":
                # Phase 1: Wait for speech onset
                pre_buffer.append(frame)
                if len(pre_buffer) > 10:
                    pre_buffer.pop(0)

                if self._is_speech(frame):
                    state["speech_count"] += 1
                    if state["speech_count"] >= min_speech_frames:
                        state["phase"] = "recording"
                        frames.extend(pre_buffer)
                else:
                    state["speech_count"] = 0

            elif state["phase"] == "recording":
                # Phase 2: Record until silence
                frames.append(frame)
                state["total"] += 1

                if not self._is_speech(frame):
                    state["silence_count"] += 1
                    if state["silence_count"] >= silence_frames_needed:
                        done.set()
                        raise sd.CallbackStop()
                else:
                    state["silence_count"] = 0

                # Max 30s
                if state["total"] > int(30 * 1000 / frame_ms):
                    done.set()
                    raise sd.CallbackStop()

        stream = sd.InputStream(
            device=dev, channels=1, samplerate=self.sample_rate,
            dtype="int16", blocksize=frame_size, callback=callback, latency="high",
        )
        stream.start()
        print("... en ecoute ...", end="", flush=True)

        # Wait for speech detection + recording to complete (max 60s)
        done.wait(timeout=60)
        stream.stop()
        stream.close()

        if not frames:
            print(" (timeout)")
            return None

        print(" parole detectee!", flush=True)
        audio = np.concatenate(frames)
        duration = len(audio) / self.sample_rate
        print(f"  Enregistre: {duration:.1f}s")

        if duration < 0.3:
            return None
        return audio

    def listen_push(self) -> np.ndarray | None:
        """Push-to-talk: press Enter to start, Enter to stop."""
        dev = self._resolve_device(self.input_device, "Input")
        input("  Appuyez sur Entree pour parler...")

        frames = []
        recording = True

        def callback(indata, frame_count, time_info, status):
            if recording:
                frames.append(indata[:, 0].copy())

        stream = sd.InputStream(
            device=dev,
            channels=1,
            samplerate=self.sample_rate,
            dtype="int16",
            callback=callback,
        )
        stream.start()
        print("  Enregistrement... (Entree pour arreter)")
        input()
        recording = False
        stream.stop()
        stream.close()

        if not frames:
            return None
        audio = np.concatenate(frames)
        duration = len(audio) / self.sample_rate
        print(f"  Enregistre: {duration:.1f}s")
        return audio

    # ─── ASR ───────────────────────────────────────────────────────────

    def transcribe(self, audio_int16: np.ndarray) -> str:
        """Transcribe int16 audio to text using faster-whisper."""
        # Convert int16 to float32 normalized [-1, 1]
        audio_f32 = audio_int16.astype(np.float32) / 32768.0

        segments, info = self.whisper.transcribe(
            audio_f32,
            language=self.asr_language,
            beam_size=5,
            vad_filter=True,  # built-in silero VAD filter
        )
        text = " ".join(s.text for s in segments).strip()
        return text

    # ─── LLM ──────────────────────────────────────────────────────────

    def ask_claude(self, text: str) -> str:
        """Send text to Claude, return response."""
        self.conversation.append({"role": "user", "content": text})

        response = self.client.messages.create(
            model=self.llm_model,
            max_tokens=self.max_tokens,
            system=self.system_prompt,
            messages=self.conversation,
        )

        reply = response.content[0].text
        self.conversation.append({"role": "assistant", "content": reply})

        # Keep conversation manageable (last 20 messages)
        if len(self.conversation) > 20:
            self.conversation = self.conversation[-16:]

        return reply

    # ─── TTS ──────────────────────────────────────────────────────────

    async def speak(self, text: str):
        """Convert text to speech and play through output device."""
        communicate = edge_tts.Communicate(
            text,
            self.tts_voice,
            rate=self.tts_rate,
            volume=self.tts_volume,
        )

        # Collect MP3 data
        mp3_data = b""
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                mp3_data += chunk["data"]

        if not mp3_data:
            print("  (TTS: pas de donnees audio)")
            return

        # Decode MP3 to PCM using miniaudio
        decoded = miniaudio.decode(mp3_data, output_format=miniaudio.SampleFormat.SIGNED16)
        samples = np.frombuffer(decoded.samples, dtype=np.int16)

        # Convert to float32 for sounddevice
        audio_f32 = samples.astype(np.float32) / 32768.0

        # Handle stereo -> mono if needed
        if decoded.nchannels == 2:
            audio_f32 = audio_f32.reshape(-1, 2).mean(axis=1)

        # Play through output device using callback API (required for WDM-KS BT)
        import threading
        dev = self._resolve_device(self.output_device, "Output")

        # Resample to device sample rate if needed
        if decoded.sample_rate != self.sample_rate:
            # Simple linear resampling
            ratio = self.sample_rate / decoded.sample_rate
            indices = np.arange(0, len(audio_f32), 1 / ratio).astype(int)
            indices = indices[indices < len(audio_f32)]
            audio_f32 = audio_f32[indices]

        playback_pos = 0
        play_done = threading.Event()

        def play_callback(outdata, frame_count, time_info, status):
            nonlocal playback_pos
            end = playback_pos + frame_count
            if end <= len(audio_f32):
                outdata[:, 0] = audio_f32[playback_pos:end]
            else:
                remaining = len(audio_f32) - playback_pos
                if remaining > 0:
                    outdata[:remaining, 0] = audio_f32[playback_pos:]
                outdata[remaining:, 0] = 0
                play_done.set()
                raise sd.CallbackStop()
            playback_pos = end

        out_stream = sd.OutputStream(
            device=dev, channels=1, samplerate=self.sample_rate,
            dtype="float32", blocksize=480, callback=play_callback, latency="high",
        )
        out_stream.start()
        play_done.wait(timeout=len(audio_f32) / self.sample_rate + 3)
        out_stream.stop()
        out_stream.close()

    # ─── Main loop ────────────────────────────────────────────────────

    async def run(self):
        """Main assistant loop."""
        print("=" * 50)
        print("  MetaRayBan Voice Assistant")
        print("  Mode:", self.mode.upper())
        print("=" * 50)
        print()

        self.list_devices()
        self.calibrate_noise()
        print()
        print("Pret! Parlez dans vos lunettes Ray-Ban Meta.")
        print("Ctrl+C pour quitter.\n")

        listen_fn = self.listen_vad if self.mode == "vad" else self.listen_push

        while True:
            try:
                # 1. Listen
                audio = listen_fn()
                if audio is None or len(audio) < self.sample_rate * 0.3:
                    continue

                # 2. Transcribe
                t0 = time.time()
                text = self.transcribe(audio)
                asr_time = time.time() - t0

                if not text or len(text.strip()) < 2:
                    print("  (pas de parole detectee)")
                    continue

                print(f"\n  Vous ({asr_time:.1f}s): {text}")

                # 3. Think (Claude)
                t0 = time.time()
                reply = self.ask_claude(text)
                llm_time = time.time() - t0
                print(f"  Assistant ({llm_time:.1f}s): {reply}")

                # 4. Speak (TTS + playback)
                await self.speak(reply)
                print()

            except KeyboardInterrupt:
                print("\n\nAu revoir!")
                break
            except Exception as e:
                print(f"\n  ERREUR: {e}")
                import traceback
                traceback.print_exc()


def main():
    parser = argparse.ArgumentParser(description="MetaRayBan Voice Assistant")
    parser.add_argument(
        "-c", "--config",
        default="config.yaml",
        help="Path to config.yaml (default: config.yaml)"
    )
    parser.add_argument(
        "--list-devices",
        action="store_true",
        help="List audio devices and exit"
    )
    parser.add_argument(
        "--mode",
        choices=["vad", "push"],
        help="Override mode: vad (continuous) or push (push-to-talk)"
    )
    args = parser.parse_args()

    if args.list_devices:
        VoiceAssistant.list_devices()
        return

    config_path = Path(args.config)
    if not config_path.exists():
        # Try relative to script directory
        config_path = Path(__file__).parent / args.config
    if not config_path.exists():
        print(f"Config not found: {args.config}")
        sys.exit(1)

    assistant = VoiceAssistant(str(config_path))
    if args.mode:
        assistant.mode = args.mode

    asyncio.run(assistant.run())


if __name__ == "__main__":
    main()
