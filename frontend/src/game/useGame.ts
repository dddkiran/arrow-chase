import { useCallback, useEffect, useReducer, useRef } from 'react';
import type { PlaceBetCommand, ServerMessage } from './protocol';
import { initialState, reducer, type GameState } from './state';

const PLAYER_ID_KEY = 'arrow-chase.playerId';
const NAME_KEY = 'arrow-chase.name';
const MAX_BACKOFF_MS = 5_000;

/** Storage can be unavailable (private mode, blocked site data); the game still works, just without memory. */
function remembered(key: string, create: () => string): string {
  try {
    const existing = localStorage.getItem(key);
    if (existing) return existing;
    const value = create();
    localStorage.setItem(key, value);
    return value;
  } catch {
    return create();
  }
}

function newId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

const ANIMALS = ['Fox', 'Owl', 'Lynx', 'Hare', 'Wolf', 'Kite', 'Orca', 'Yak', 'Ibis', 'Mole'];

function randomName(): string {
  return `${ANIMALS[Math.floor(Math.random() * ANIMALS.length)]}-${Math.floor(100 + Math.random() * 900)}`;
}

function socketUrl(playerId: string, name: string): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const query = new URLSearchParams({ playerId, name });
  return `${scheme}://${location.host}/ws?${query}`;
}

/**
 * Owns the WebSocket. Reconnects with backoff; after every (re)connect the server's welcome snapshot replaces
 * local state, and any bet still waiting for confirmation is re-sent with the same requestId. The server treats a
 * repeated requestId as the same bet, so a flaky network can never double-charge.
 */
export function useGame(): {
  state: GameState;
  placeBet: (row: number, col: number, stakeCents: number) => void;
  dismissNotice: (id: number) => void;
} {
  const [state, dispatch] = useReducer(reducer, initialState);
  const socketRef = useRef<WebSocket | null>(null);
  const pendingRef = useRef(state.pending);
  pendingRef.current = state.pending;

  useEffect(() => {
    const playerId = remembered(PLAYER_ID_KEY, newId);
    const name = remembered(NAME_KEY, randomName);
    let attempt = 0;
    let stopped = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;

    const connect = () => {
      const socket = new WebSocket(socketUrl(playerId, name));
      socketRef.current = socket;

      socket.onmessage = (event) => {
        const message = JSON.parse(event.data as string) as ServerMessage;
        dispatch({ type: 'server', message });
        if (message.type === 'welcome') {
          attempt = 0;
          for (const p of Object.values(pendingRef.current)) {
            socket.send(JSON.stringify({ type: 'placeBet', ...p } satisfies PlaceBetCommand));
          }
        }
      };
      socket.onclose = () => {
        if (stopped) return;
        dispatch({ type: 'connection', status: 'reconnecting' });
        const delay = Math.min(MAX_BACKOFF_MS, 500 * 2 ** attempt++);
        retryTimer = setTimeout(connect, delay);
      };
    };

    connect();
    return () => {
      stopped = true;
      clearTimeout(retryTimer);
      socketRef.current?.close();
    };
  }, []);

  const placeBet = useCallback((row: number, col: number, stakeCents: number) => {
    const bet = { requestId: newId(), row, col, stakeCents };
    dispatch({ type: 'betRequested', bet });
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'placeBet', ...bet } satisfies PlaceBetCommand));
    }
    // Otherwise it stays pending and is sent after the next welcome.
  }, []);

  const dismissNotice = useCallback((id: number) => dispatch({ type: 'dismissNotice', id }), []);

  return { state, placeBet, dismissNotice };
}
