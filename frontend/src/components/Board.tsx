import { useRef } from 'react';
import type { GameInfo, StepMessage } from '../game/protocol';
import type { TileView } from '../game/state';
import { formatMultiplier } from '../game/format';

const ROTATION = { RIGHT: 0, DOWN: 90, LEFT: 180, UP: 270 } as const;

interface BoardProps {
  game: GameInfo;
  board: StepMessage;
  tiles: TileView[][];
  onSelect: (row: number, col: number) => void;
  disabled: boolean;
}

/** Keeps a running angle so a turn always rotates the short way (UP -> RIGHT is +90, not -270). */
function useShortestRotation(target: number): number {
  const angle = useRef(target);
  const current = angle.current;
  const delta = ((((target - current) % 360) + 540) % 360) - 180;
  angle.current = current + delta;
  return angle.current;
}

export function Board({ game, board, tiles, onSelect, disabled }: BoardProps) {
  const { arrow } = board;
  const rotation = useShortestRotation(ROTATION[arrow.direction]);
  return (
    <div
      className="board"
      style={{ gridTemplateColumns: `repeat(${game.cols}, 1fr)`, aspectRatio: `${game.cols} / ${game.rows}` }}
    >
      {tiles.flat().map((tile) => (
        <Tile
          key={`${tile.row}-${tile.col}`}
          tile={tile}
          step={board.step}
          disabled={disabled}
          onSelect={onSelect}
        />
      ))}
      <div className="arrow-layer" aria-hidden="true">
        <div
          className="arrow"
          style={{
            width: `${100 / game.cols}%`,
            height: `${100 / game.rows}%`,
            transform: `translate(${arrow.col * 100}%, ${arrow.row * 100}%)`,
            transitionDuration: `${Math.round(game.stepMillis * 0.85)}ms`,
          }}
        >
          <svg className="arrow-glyph" viewBox="0 0 24 24" style={{ transform: `rotate(${rotation}deg)` }}>
            <path d="M3 10h11V5l7 7-7 7v-5H3z" />
          </svg>
        </div>
      </div>
    </div>
  );
}

interface TileProps {
  tile: TileView;
  step: number;
  disabled: boolean;
  onSelect: (row: number, col: number) => void;
}

export function Tile({ tile, step, disabled, onSelect }: TileProps) {
  const closed = tile.multiplierX100 <= 0;
  const locked = tile.open[0];
  const classes = ['tile'];
  if (closed) classes.push('closed');
  if (tile.open.length) classes.push('has-bet');
  if (tile.pending.length) classes.push('is-pending');
  if (tile.justSettled === 'WON') classes.push('flash-win');
  if (tile.justSettled === 'LOST') classes.push('flash-loss');
  if (tile.trailIndex !== null) classes.push(`trail-${Math.min(tile.trailIndex, 5)}`);

  const label = closed
    ? `Block ${tile.row + 1}-${tile.col + 1}, closed`
    : `Block ${tile.row + 1}-${tile.col + 1}, ${formatMultiplier(tile.multiplierX100)}`;

  return (
    <button
      type="button"
      className={classes.join(' ')}
      aria-label={label}
      disabled={disabled || closed}
      onClick={() => onSelect(tile.row, tile.col)}
    >
      <span className="live">{closed ? '–' : formatMultiplier(tile.multiplierX100)}</span>
      {locked && (
        <span className="lock" title="Multiplier locked for your bet">
          🔒 {formatMultiplier(locked.lockedMultiplierX100)}
          {tile.open.length > 1 && <span className="count"> +{tile.open.length - 1}</span>}
        </span>
      )}
      {locked && <span className="steps-left">{Math.max(0, locked.expiresAtStep - step)}</span>}
      {!locked && tile.pending.length > 0 && <span className="lock pending">locking…</span>}
    </button>
  );
}
