import { useCallback, useEffect, useRef, useState } from "react";
import type { AudioEngine } from "../hooks/useAudioEngine";
import { VolumeOnIcon, VolumeOffIcon, MusicNoteIcon, WavesIcon, SpeechBubbleIcon } from "./Icons";

interface AudioControlsProps {
  audio: AudioEngine;
  /**
   * Skinned (painted-gear) mode: the gear art is the affordance, so the hotspot
   * opens a panel with an explicit sound toggle + sliders, and a status badge
   * on the gear shows the current state. Without this, clicking would flip the
   * mute state with no visible feedback at all.
   */
  skinned?: boolean;
}

function VolumeSliders({ audio }: { audio: AudioEngine }) {
  return (
    <>
      <div className="audio-slider-row">
        <MusicNoteIcon className="audio-slider-icon" />
        <input
          type="range"
          className="audio-slider"
          min={0}
          max={100}
          value={Math.round(audio.musicVolume * 100)}
          onChange={(e) => audio.setMusicVolume(parseInt(e.target.value, 10) / 100)}
          title={`Music: ${Math.round(audio.musicVolume * 100)}%`}
          aria-label="Music volume"
        />
      </div>
      <div className="audio-slider-row">
        <WavesIcon className="audio-slider-icon" />
        <input
          type="range"
          className="audio-slider"
          min={0}
          max={100}
          value={Math.round(audio.ambientVolume * 100)}
          onChange={(e) => audio.setAmbientVolume(parseInt(e.target.value, 10) / 100)}
          title={`Ambient: ${Math.round(audio.ambientVolume * 100)}%`}
          aria-label="Ambient volume"
        />
      </div>
      <div className="audio-slider-row">
        <SpeechBubbleIcon className="audio-slider-icon" />
        <input
          type="range"
          className="audio-slider"
          min={0}
          max={100}
          value={Math.round(audio.voiceVolume * 100)}
          onChange={(e) => audio.setVoiceVolume(parseInt(e.target.value, 10) / 100)}
          title={`Voice: ${Math.round(audio.voiceVolume * 100)}%`}
          aria-label="Voice volume"
        />
      </div>
    </>
  );
}

export function AudioControls({ audio, skinned = false }: AudioControlsProps) {
  const [expanded, setExpanded] = useState(false);
  const controlsRef = useRef<HTMLDivElement | null>(null);

  const toggleExpanded = useCallback(() => {
    setExpanded((prev) => !prev);
  }, []);

  // Enabling audio also opens the sliders so the click visibly does something.
  const toggleAudio = useCallback(() => {
    if (!audio.enabled) setExpanded(true);
    audio.toggle();
  }, [audio]);

  // Close on Escape or click outside
  useEffect(() => {
    if (!expanded) return;

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setExpanded(false);
    };
    const onPointerDown = (e: PointerEvent) => {
      if (controlsRef.current && !controlsRef.current.contains(e.target as Node)) {
        setExpanded(false);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("pointerdown", onPointerDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("pointerdown", onPointerDown);
    };
  }, [expanded]);

  const panel = (
    <div className="audio-sliders" role="group" aria-label={skinned ? "Audio settings" : "Volume controls"}>
      {skinned && (
        <button
          type="button"
          className={`audio-master-row${audio.enabled ? " audio-master-on" : ""}`}
          onClick={audio.toggle}
          aria-pressed={audio.enabled}
        >
          {audio.enabled
            ? <VolumeOnIcon className="audio-slider-icon" />
            : <VolumeOffIcon className="audio-slider-icon" />
          }
          <span>{audio.enabled ? "Sound on" : "Sound off"}</span>
        </button>
      )}
      {audio.enabled && <VolumeSliders audio={audio} />}
    </div>
  );

  if (skinned) {
    return (
      <div className="audio-controls audio-controls-skinned" ref={controlsRef}>
        <button
          type="button"
          className="vskin-audio-hotspot"
          onClick={toggleExpanded}
          title={audio.enabled ? "Audio settings (sound on)" : "Audio settings (sound off)"}
          aria-label={audio.enabled ? "Audio settings (sound on)" : "Audio settings (sound off)"}
          aria-expanded={expanded}
        >
          <span
            className={`vskin-audio-badge${audio.enabled ? " vskin-audio-badge-on" : ""}`}
            aria-hidden="true"
          />
        </button>
        {expanded && panel}
      </div>
    );
  }

  return (
    <div className="audio-controls" ref={controlsRef}>
      <button
        type="button"
        className={`soft-button audio-toggle-btn${audio.enabled ? " audio-enabled" : ""}`}
        onClick={toggleAudio}
        title={audio.enabled ? "Mute audio" : "Enable audio"}
        aria-label={audio.enabled ? "Mute audio" : "Enable audio"}
        aria-pressed={audio.enabled}
      >
        {audio.enabled
          ? <VolumeOnIcon className="audio-toggle-icon" />
          : <VolumeOffIcon className="audio-toggle-icon" />
        }
      </button>

      {audio.enabled && (
        <button
          type="button"
          className="soft-button audio-expand-btn"
          onClick={toggleExpanded}
          title="Volume settings"
          aria-label={expanded ? "Hide volume sliders" : "Show volume sliders"}
          aria-expanded={expanded}
        >
          ▾
        </button>
      )}

      {audio.enabled && expanded && panel}
    </div>
  );
}
