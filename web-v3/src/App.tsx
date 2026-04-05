import { GameStateProvider } from "./state/GameStateProvider";
import { AppShell } from "./components/AppShell";
import "@xterm/xterm/css/xterm.css";
import "./styles.css";

function App() {
  return (
    <GameStateProvider>
      <AppShell />
    </GameStateProvider>
  );
}

export default App;
