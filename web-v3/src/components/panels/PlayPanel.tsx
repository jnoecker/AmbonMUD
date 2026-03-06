import { PixiCanvas } from "../../canvas/PixiCanvas";

interface PlayPanelProps {
  preLogin: boolean;
}

export function PlayPanel({ preLogin }: PlayPanelProps) {
  return (
    <section className="panel panel-play" aria-label="Gameplay console">
      {preLogin && (
        <section className="prelogin-banner" aria-label="Login guidance">
          <p className="prelogin-banner-title">Welcome back. Your session is connected.</p>
          <p className="prelogin-banner-text">Log in through the modal to begin your journey. World and character panels are accessible from the action bar below.</p>
        </section>
      )}
      <div className="terminal-card">
        <PixiCanvas />
      </div>
    </section>
  );
}
