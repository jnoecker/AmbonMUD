import { useCallback, useState } from "react";
import type { AudioEngine } from "../hooks/useAudioEngine";
import { VolumeOnIcon, VolumeOffIcon, MusicNoteIcon, WavesIcon } from "./Icons";

interface AudioControlsProps {
  audio: AudioEngine;
}

export function AudioControls({ audio }: AudioControlsProps) {
  const [expanded, setExpanded] = useState(false);

  const toggleExpanded = useCallback(() => {
    setExpanded((prev) => !prev);
  }, []);

  return (
    <div className="audio-controls">
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
        <div className="audio-sliders">
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
