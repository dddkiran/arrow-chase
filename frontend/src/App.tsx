import { useEffect, useMemo, useState } from 'react';
import { useGame } from './game/useGame';
import { openBets, settledBets, tileViews, type Notice } from './game/state';
import { formatMoney } from './game/format';
import { Board } from './components/Board';
import { BetList } from './components/BetList';
import { StakeControl, validateStake } from './components/StakeControl';
import { WinFeed } from './components/WinFeed';

const NOTICE_MS = 3_500;

export function App() {
  const { state, placeBet, dismissNotice } = useGame();
  const [stake, setStake] = useState('1.00');
  const { game, board } = state;

  const tiles = useMemo(() => tileViews(state), [state]);

  if (!game || !board) {
    return (
      <main className="loading">
        <h1>Arrow Chase</h1>
        <p>{state.connection === 'reconnecting' ? 'Server unreachable, retrying…' : 'Connecting…'}</p>
      </main>
    );
  }

  const stakeCheck = validateStake(stake, game.minStakeCents, game.maxStakeCents, state.balanceCents);
  const canBet = state.connection === 'open' && 'cents' in stakeCheck;

  const onSelect = (row: number, col: number) => {
    if ('cents' in stakeCheck) {
      placeBet(row, col, stakeCheck.cents);
    }
  };

  return (
    <div className="app">
      <header>
        <h1>
          Arrow Chase <span className="tag">live</span>
        </h1>
        <div className="stats">
          <span title="Players connected right now">👥 {board.playersOnline}</span>
          <span className={`conn ${state.connection}`}>{state.connection === 'open' ? 'connected' : 'reconnecting…'}</span>
          <span className="who">{state.name}</span>
          <span className="balance" aria-label="Balance">
            {formatMoney(state.balanceCents)}
          </span>
        </div>
      </header>

      <main>
        <div className="board-wrap">
          <Board game={game} board={board} tiles={tiles} onSelect={onSelect} disabled={!canBet} />
          <p className="legend">
            The arrow moves one block every {(game.stepMillis / 1000).toFixed(1)}s. A bet wins if the arrow enters
            your block within the next {game.betWindowSteps} moves. Your multiplier is locked when the server
            confirms (🔒). Dimmed blocks are out of reach and closed.
          </p>
        </div>

        <aside>
          <StakeControl
            value={stake}
            onChange={setStake}
            minStakeCents={game.minStakeCents}
            maxStakeCents={game.maxStakeCents}
            balanceCents={state.balanceCents}
          />
          <BetList
            open={openBets(state)}
            pending={Object.values(state.pending)}
            settled={settledBets(state).slice(0, 10)}
            step={board.step}
          />
          <WinFeed wins={state.feed} />
        </aside>
      </main>

      <div className="toasts" role="status" aria-live="polite">
        {state.notices.map((n) => (
          <Toast key={n.id} notice={n} onDone={dismissNotice} />
        ))}
      </div>
    </div>
  );
}

function Toast({ notice, onDone }: { notice: Notice; onDone: (id: number) => void }) {
  useEffect(() => {
    const timer = setTimeout(() => onDone(notice.id), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [notice.id, onDone]);
  return <div className={`toast ${notice.kind}`}>{notice.text}</div>;
}
