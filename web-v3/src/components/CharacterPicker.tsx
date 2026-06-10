import { useCallback, useState } from "react";
import { ArtScene } from "../canvas/ArtScene";
import { ART_FIT_LANDSCAPE, useArtImage, useMediaFits } from "../canvas/loginArtFit";

interface CharacterPickerProps {
  characters: string[];
  /** Painted welcome-back scene (`login_picker_bg` from Server.Assets); null → CSS dialog. */
  backgroundImage: string | null;
  onSelect: (name: string) => void;
  onRemoveCharacter: (name: string) => void;
  onNewCharacter: () => void;
}

export function CharacterPicker({ characters, backgroundImage, onSelect, onRemoveCharacter, onNewCharacter }: CharacterPickerProps) {
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const artFits = useMediaFits(ART_FIT_LANDSCAPE);
  // Gated on the image actually loading — failed art falls back to the CSS dialog.
  const art = useArtImage(artFits ? backgroundImage : null);

  const handleRemove = useCallback((name: string, e: React.MouseEvent | React.PointerEvent | React.KeyboardEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (confirmRemove === name) {
      onRemoveCharacter(name);
      setConfirmRemove(null);
    } else {
      setConfirmRemove(name);
    }
  }, [confirmRemove, onRemoveCharacter]);

  const entries = characters.map((name) => (
    <div key={name} className="character-picker-entry">
      <button
        type="button"
        className="character-picker-btn"
        onClick={() => onSelect(name)}
      >
        <span className="character-picker-btn-name">{name}</span>
        <span className="character-picker-btn-arrow" aria-hidden="true">&#x203A;</span>
      </button>
      <button
        type="button"
        className={`character-picker-remove${confirmRemove === name ? " character-picker-remove--confirm" : ""}`}
        onPointerDown={(e) => handleRemove(name, e)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") handleRemove(name, e);
        }}
        onBlur={() => setConfirmRemove(null)}
        title={confirmRemove === name ? "Click again to forget" : `Forget ${name}`}
        aria-label={confirmRemove === name ? `Confirm forget ${name}` : `Forget ${name}`}
      >
        {confirmRemove === name ? "Forget?" : "×"}
      </button>
    </div>
  ));

  if (art) {
    return (
      <ArtScene
        url={art}
        stageClass="login-art-stage--book login-art-stage--picker"
        label="Welcome back — choose a character"
      >
        <h1 className="sr-only">Welcome back — choose one of your saved characters or create a new one</h1>
        <div className="character-picker-list login-art-picker-list lpk-list">
          {entries}
        </div>
        <button
          type="button"
          className="login-art-hotspot lpk-create"
          onClick={onNewCharacter}
          title="Create a New Character"
          aria-label="Create a new character"
        />
      </ArtScene>
    );
  }

  return (
    <div className="character-picker-overlay" role="dialog" aria-modal="true" aria-label="Choose a character">
      <div className="character-picker">
        <h2 className="character-picker-title">Welcome to AmbonMUD</h2>
        <p className="character-picker-subtitle">Choose a character or create a new one</p>

        <div className="character-picker-list">
          {entries}
        </div>

        <div className="character-picker-divider">
          <span className="character-picker-divider-line" />
          <span className="character-picker-divider-text">or</span>
          <span className="character-picker-divider-line" />
        </div>

        <button
          type="button"
          className="character-picker-new"
          onClick={onNewCharacter}
        >
          Create a new character
        </button>
      </div>
    </div>
  );
}
