import { useCallback, useEffect, useRef, useState } from "react";
import type { AudioEngine } from "../hooks/useAudioEngine";
import { VolumeOnIcon, VolumeOffIcon, MusicNoteIcon, WavesIcon } from "./Icons";

interface AudioControlsProps {
  audio: AudioEngine;
}

export function AudioControls({ audio }: AudioControlsProps) {
  const [expanded, setExpanded] = useState(false);
  const controlsRef = useRef<HTMLDivElement | null>(null);

  const toggleExpanded = useCallback(() => {
    setExpanded((prev) => !prev);
  }, []);

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

  return (
    <div className="audio-controls" ref={controlsRef}>
      <button
        type="button"
        className={`soft-button audio-toggle-btn${audio.enabled ? " audio-enabled" : ""}`}
        onClick={audio.toggle}
        title={audio.enabled ? "Mute audio" : "Enable audio"}
        aria-label={audio.enabled ? "Mute audio" : "Enable audio"}
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
          aria-label="Toggle volume sliders"
          aria-expanded={expanded}
        >
          ▾
        </button>
      )}

      {audio.enabled && expanded && (
        <div className="audio-sliders" role="group" aria-label="Volume controls">
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
        </div>
      )}
    </div>
  );
}
