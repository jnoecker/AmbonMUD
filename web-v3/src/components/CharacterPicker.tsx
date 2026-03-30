interface CharacterPickerProps {
  characters: string[];
  onSelect: (name: string) => void;
  onNewCharacter: () => void;
}

export function CharacterPicker({ characters, onSelect, onNewCharacter }: CharacterPickerProps) {
  return (
    <div className="character-picker-overlay" role="dialog" aria-modal="true" aria-label="Choose a character">
      <div className="character-picker">
        <h2 className="character-picker-title">Welcome back</h2>
        <p className="character-picker-subtitle">Choose a character to continue</p>
        <div className="character-picker-list">
          {characters.map((name) => (
            <button
              key={name}
              type="button"
              className="character-picker-btn"
              onClick={() => onSelect(name)}
            >
              {name}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="character-picker-new"
          onClick={onNewCharacter}
        >
          Log in as a different character
        </button>
      </div>
    </div>
  );
}
