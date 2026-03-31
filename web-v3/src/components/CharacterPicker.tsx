import { useCallback, useState } from "react";

interface CharacterPickerProps {
  characters: string[];
  onSelect: (name: string) => void;
  onRemoveCharacter: (name: string) => void;
  onNewCharacter: () => void;
}

export function CharacterPicker({ characters, onSelect, onRemoveCharacter, onNewCharacter }: CharacterPickerProps) {
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);

  const handleRemove = useCallback((name: string, e: React.MouseEvent | React.PointerEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (confirmRemove === name) {
      onRemoveCharacter(name);
      setConfirmRemove(null);
    } else {
      setConfirmRemove(name);
    }
  }, [confirmRemove, onRemoveCharacter]);

  return (
    <div className="character-picker-overlay" role="dialog" aria-modal="true" aria-label="Choose a character">
      <div className="character-picker">
        <h2 className="character-picker-title">Welcome to AmbonMUD</h2>
        <p className="character-picker-subtitle">Choose a character or create a new one</p>

        <div className="character-picker-list">
          {characters.map((name) => (
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
                onBlur={() => setConfirmRemove(null)}
                title={confirmRemove === name ? "Click again to forget" : `Forget ${name}`}
                aria-label={confirmRemove === name ? `Confirm forget ${name}` : `Forget ${name}`}
              >
                {confirmRemove === name ? "Forget?" : "\u00D7"}
              </button>
            </div>
          ))}
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
