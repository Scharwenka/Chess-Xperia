// Chess AI — minimax with alpha-beta pruning + piece-square tables

const PIECE_VAL = [0, 100, 320, 330, 500, 900, 20000];

// Piece-square tables: white perspective, sq index 0=a8 … 63=h1
const PST = {
  1: [ // Pawn
     0,  0,  0,  0,  0,  0,  0,  0,
    50, 50, 50, 50, 50, 50, 50, 50,
    10, 10, 20, 30, 30, 20, 10, 10,
     5,  5, 10, 25, 25, 10,  5,  5,
     0,  0,  0, 20, 20,  0,  0,  0,
     5, -5,-10,  0,  0,-10, -5,  5,
     5, 10, 10,-20,-20, 10, 10,  5,
     0,  0,  0,  0,  0,  0,  0,  0
  ],
  2: [ // Knight
   -50,-40,-30,-30,-30,-30,-40,-50,
   -40,-20,  0,  0,  0,  0,-20,-40,
   -30,  0, 10, 15, 15, 10,  0,-30,
   -30,  5, 15, 20, 20, 15,  5,-30,
   -30,  0, 15, 20, 20, 15,  0,-30,
   -30,  5, 10, 15, 15, 10,  5,-30,
   -40,-20,  0,  5,  5,  0,-20,-40,
   -50,-40,-30,-30,-30,-30,-40,-50
  ],
  3: [ // Bishop
   -20,-10,-10,-10,-10,-10,-10,-20,
   -10,  0,  0,  0,  0,  0,  0,-10,
   -10,  0,  5, 10, 10,  5,  0,-10,
   -10,  5,  5, 10, 10,  5,  5,-10,
   -10,  0, 10, 10, 10, 10,  0,-10,
   -10, 10, 10, 10, 10, 10, 10,-10,
   -10,  5,  0,  0,  0,  0,  5,-10,
   -20,-10,-10,-10,-10,-10,-10,-20
  ],
  4: [ // Rook
     0,  0,  0,  0,  0,  0,  0,  0,
     5, 10, 10, 10, 10, 10, 10,  5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
     0,  0,  0,  5,  5,  0,  0,  0
  ],
  5: [ // Queen
   -20,-10,-10, -5, -5,-10,-10,-20,
   -10,  0,  0,  0,  0,  0,  0,-10,
   -10,  0,  5,  5,  5,  5,  0,-10,
    -5,  0,  5,  5,  5,  5,  0, -5,
     0,  0,  5,  5,  5,  5,  0, -5,
   -10,  5,  5,  5,  5,  5,  0,-10,
   -10,  0,  5,  0,  0,  0,  0,-10,
   -20,-10,-10, -5, -5,-10,-10,-20
  ],
  6: [ // King (middlegame)
   -30,-40,-40,-50,-50,-40,-40,-30,
   -30,-40,-40,-50,-50,-40,-40,-30,
   -30,-40,-40,-50,-50,-40,-40,-30,
   -30,-40,-40,-50,-50,-40,-40,-30,
   -20,-30,-30,-40,-40,-30,-30,-20,
   -10,-20,-20,-20,-20,-20,-20,-10,
    20, 20,  0,  0,  0,  0, 20, 20,
    20, 30, 10,  0,  0, 10, 30, 20
  ]
};

function evaluate(state) {
  let score = 0;
  for (let s = 0; s < 64; s++) {
    const p = state.board[s];
    if (!p) continue;
    const c = p > 0 ? 1 : -1;
    const t = Math.abs(p);
    score += c * PIECE_VAL[t];
    // PST bonus: mirror table for black (flip rows)
    const pstIdx = c === 1 ? s : (7 - sqR(s)) * 8 + sqC(s);
    if (PST[t]) score += c * PST[t][pstIdx];
  }
  return score;
}

// MVV-LVA move ordering (captures first, high-value victims / low-value attackers)
function orderMoves(moves, board) {
  return moves.slice().sort((a, b) => {
    const va = board[a.to] !== 0 ? PIECE_VAL[Math.abs(board[a.to])] - PIECE_VAL[Math.abs(board[a.from])] / 10 : -1;
    const vb = board[b.to] !== 0 ? PIECE_VAL[Math.abs(board[b.to])] - PIECE_VAL[Math.abs(board[b.from])] / 10 : -1;
    return vb - va;
  });
}

function minimax(state, depth, alpha, beta, maximizing) {
  if (depth === 0) return evaluate(state);

  const moves = legalMoves(state);
  if (moves.length === 0) {
    return isInCheck(state.board, state.turn)
      ? (maximizing ? -90000 + depth : 90000 - depth)
      : 0;
  }

  const ordered = orderMoves(moves, state.board);

  if (maximizing) {
    let best = -Infinity;
    for (const m of ordered) {
      best = Math.max(best, minimax(applyMove(state, m), depth - 1, alpha, beta, false));
      alpha = Math.max(alpha, best);
      if (beta <= alpha) break;
    }
    return best;
  } else {
    let best = Infinity;
    for (const m of ordered) {
      best = Math.min(best, minimax(applyMove(state, m), depth - 1, alpha, beta, true));
      beta = Math.min(beta, best);
      if (beta <= alpha) break;
    }
    return best;
  }
}

function getBestMove(state, depth) {
  depth = depth || 3;
  const moves = legalMoves(state);
  if (!moves.length) return null;

  const ordered = orderMoves(moves, state.board);
  let best = null;
  let bestScore = state.turn === W ? -Infinity : Infinity;

  for (const m of ordered) {
    const score = minimax(applyMove(state, m), depth - 1, -Infinity, Infinity, state.turn === BL);
    if (state.turn === W ? score > bestScore : score < bestScore) {
      bestScore = score;
      best = m;
    }
  }

  // Among equal-score moves add tiny randomness to avoid repetitive play
  const equalMoves = ordered.filter(m => {
    const score = minimax(applyMove(state, m), depth - 1, -Infinity, Infinity, state.turn === BL);
    return Math.abs(score - bestScore) < 5;
  });
  if (equalMoves.length > 1) {
    best = equalMoves[Math.floor(Math.random() * Math.min(equalMoves.length, 3))];
  }

  return best;
}
