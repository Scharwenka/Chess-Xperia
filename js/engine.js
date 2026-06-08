// Chess Engine
// Board: 64 squares, index = row*8 + col
// Row 0 = rank 8 (black back rank), Row 7 = rank 1 (white back rank)
// Pieces: positive=white, negative=black
// 0=empty, ±1=Pawn, ±2=Knight, ±3=Bishop, ±4=Rook, ±5=Queen, ±6=King

const P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6;
const W = 1, BL = -1;

function sq(r, c)   { return r * 8 + c; }
function sqR(s)     { return Math.floor(s / 8); }
function sqC(s)     { return s % 8; }
function inBounds(r, c) { return r >= 0 && r < 8 && c >= 0 && c < 8; }
function clr(p)     { return p > 0 ? W : p < 0 ? BL : 0; }
function typ(p)     { return Math.abs(p); }

const ROOK_DIRS   = [[-1,0],[1,0],[0,-1],[0,1]];
const BISHOP_DIRS = [[-1,-1],[-1,1],[1,-1],[1,1]];
const ALL_DIRS    = [[-1,0],[1,0],[0,-1],[0,1],[-1,-1],[-1,1],[1,-1],[1,1]];
const KNIGHT_OFF  = [[-2,-1],[-2,1],[-1,-2],[-1,2],[1,-2],[1,2],[2,-1],[2,1]];

function createState() {
  const board = new Array(64).fill(0);
  const back = [R, N, B, Q, K, B, N, R];
  for (let c = 0; c < 8; c++) {
    board[sq(0, c)] = -back[c];
    board[sq(1, c)] = -P;
    board[sq(6, c)] =  P;
    board[sq(7, c)] =  back[c];
  }
  return {
    board,
    turn: W,
    castling: { wK: true, wQ: true, bK: true, bQ: true },
    ep: null,       // en passant target square
    halfmoves: 0,
    fullmoves: 1
  };
}

function isAttacked(board, s, byColor) {
  const r = sqR(s), c = sqC(s);

  // Knight
  for (const [dr, dc] of KNIGHT_OFF) {
    const nr = r + dr, nc = c + dc;
    if (inBounds(nr, nc) && board[sq(nr, nc)] === byColor * N) return true;
  }

  // Rook / Queen (ranks & files)
  for (const [dr, dc] of ROOK_DIRS) {
    for (let i = 1; i < 8; i++) {
      const nr = r + dr * i, nc = c + dc * i;
      if (!inBounds(nr, nc)) break;
      const p = board[sq(nr, nc)];
      if (p !== 0) {
        if (clr(p) === byColor && (typ(p) === R || typ(p) === Q)) return true;
        break;
      }
    }
  }

  // Bishop / Queen (diagonals)
  for (const [dr, dc] of BISHOP_DIRS) {
    for (let i = 1; i < 8; i++) {
      const nr = r + dr * i, nc = c + dc * i;
      if (!inBounds(nr, nc)) break;
      const p = board[sq(nr, nc)];
      if (p !== 0) {
        if (clr(p) === byColor && (typ(p) === B || typ(p) === Q)) return true;
        break;
      }
    }
  }

  // King
  for (const [dr, dc] of ALL_DIRS) {
    const nr = r + dr, nc = c + dc;
    if (inBounds(nr, nc) && board[sq(nr, nc)] === byColor * K) return true;
  }

  // Pawn — white pawns at row r+1 attack row r, black pawns at row r-1 attack row r
  const pawnDir = byColor === W ? 1 : -1;
  for (const dc of [-1, 1]) {
    const nr = r + pawnDir, nc = c + dc;
    if (inBounds(nr, nc) && board[sq(nr, nc)] === byColor * P) return true;
  }

  return false;
}

function findKing(board, color) {
  const king = color * K;
  return board.indexOf(king);
}

function isInCheck(board, color) {
  const ks = findKing(board, color);
  return ks !== -1 && isAttacked(board, ks, -color);
}

// Generate pseudo-legal moves for piece at square s
function pseudoMoves(board, s, turn, ep) {
  const piece = board[s];
  if (!piece || clr(piece) !== turn) return [];
  const t = typ(piece);
  const moves = [];
  const r = sqR(s), c = sqC(s);

  const push = (to, flags) => moves.push(Object.assign({ from: s, to }, flags));

  if (t === P) {
    const d = turn === W ? -1 : 1;
    const startRow = turn === W ? 6 : 1;
    const promRow  = turn === W ? 0 : 7;

    // Forward
    if (inBounds(r + d, c) && board[sq(r + d, c)] === 0) {
      const fwd = sq(r + d, c);
      if (r + d === promRow) {
        for (const pp of [Q, R, B, N]) push(fwd, { promo: pp * turn });
      } else {
        push(fwd, null);
        if (r === startRow && board[sq(r + 2 * d, c)] === 0) {
          push(sq(r + 2 * d, c), { doublePush: true });
        }
      }
    }

    // Diagonal captures
    for (const dc of [-1, 1]) {
      if (!inBounds(r + d, c + dc)) continue;
      const ts = sq(r + d, c + dc);
      if (clr(board[ts]) === -turn) {
        if (r + d === promRow) {
          for (const pp of [Q, R, B, N]) push(ts, { promo: pp * turn });
        } else {
          push(ts, null);
        }
      } else if (ts === ep) {
        push(ts, { epCapture: true });
      }
    }

  } else if (t === N) {
    for (const [dr, dc] of KNIGHT_OFF) {
      const nr = r + dr, nc = c + dc;
      if (inBounds(nr, nc) && clr(board[sq(nr, nc)]) !== turn) {
        push(sq(nr, nc), null);
      }
    }
  } else if (t === B || t === R || t === Q) {
    const dirs = t === B ? BISHOP_DIRS : t === R ? ROOK_DIRS : ALL_DIRS;
    for (const [dr, dc] of dirs) {
      for (let i = 1; i < 8; i++) {
        const nr = r + dr * i, nc = c + dc * i;
        if (!inBounds(nr, nc)) break;
        const ts = sq(nr, nc);
        if (clr(board[ts]) === turn) break;
        push(ts, null);
        if (board[ts] !== 0) break;
      }
    }
  } else if (t === K) {
    for (const [dr, dc] of ALL_DIRS) {
      const nr = r + dr, nc = c + dc;
      if (inBounds(nr, nc) && clr(board[sq(nr, nc)]) !== turn) {
        push(sq(nr, nc), null);
      }
    }
  }

  return moves;
}

function applyMove(state, move) {
  const board = state.board.slice();
  const piece = board[move.from];
  const t = typ(piece);
  const color = clr(piece);
  const isCapture = board[move.to] !== 0 || move.epCapture;

  board[move.to] = move.promo !== undefined ? move.promo : piece;
  board[move.from] = 0;

  let ep = null;
  const castling = Object.assign({}, state.castling);

  if (move.epCapture) {
    const capRow = sqR(move.to) + (color === W ? 1 : -1);
    board[sq(capRow, sqC(move.to))] = 0;
  }

  if (move.doublePush) {
    ep = sq(sqR(move.from) + (color === W ? -1 : 1), sqC(move.from));
  }

  // Castling: move the rook
  if (t === K) {
    const dc = sqC(move.to) - sqC(move.from);
    if (dc === 2) {  // kingside
      const row = sqR(move.from);
      board[sq(row, 5)] = board[sq(row, 7)];
      board[sq(row, 7)] = 0;
    } else if (dc === -2) {  // queenside
      const row = sqR(move.from);
      board[sq(row, 3)] = board[sq(row, 0)];
      board[sq(row, 0)] = 0;
    }
    if (color === W) { castling.wK = false; castling.wQ = false; }
    else             { castling.bK = false; castling.bQ = false; }
  }

  // Revoke castling rights if rook moves or is captured
  if (move.from === sq(7, 7) || move.to === sq(7, 7)) castling.wK = false;
  if (move.from === sq(7, 0) || move.to === sq(7, 0)) castling.wQ = false;
  if (move.from === sq(0, 7) || move.to === sq(0, 7)) castling.bK = false;
  if (move.from === sq(0, 0) || move.to === sq(0, 0)) castling.bQ = false;

  return {
    board,
    turn: -state.turn,
    castling,
    ep,
    halfmoves: (t === P || isCapture) ? 0 : state.halfmoves + 1,
    fullmoves: color === BL ? state.fullmoves + 1 : state.fullmoves
  };
}

function legalMoves(state) {
  const { board, turn, castling, ep } = state;
  const moves = [];

  for (let s = 0; s < 64; s++) {
    if (clr(board[s]) === turn) {
      for (const m of pseudoMoves(board, s, turn, ep)) {
        moves.push(m);
      }
    }
  }

  // Castling
  const baseRow = turn === W ? 7 : 0;
  const opp = -turn;
  const ksRight = turn === W ? castling.wK : castling.bK;
  const qsRight = turn === W ? castling.wQ : castling.bQ;

  if (ksRight &&
      board[sq(baseRow, 5)] === 0 && board[sq(baseRow, 6)] === 0 &&
      !isAttacked(board, sq(baseRow, 4), opp) &&
      !isAttacked(board, sq(baseRow, 5), opp) &&
      !isAttacked(board, sq(baseRow, 6), opp)) {
    moves.push({ from: sq(baseRow, 4), to: sq(baseRow, 6) });
  }

  if (qsRight &&
      board[sq(baseRow, 3)] === 0 && board[sq(baseRow, 2)] === 0 && board[sq(baseRow, 1)] === 0 &&
      !isAttacked(board, sq(baseRow, 4), opp) &&
      !isAttacked(board, sq(baseRow, 3), opp) &&
      !isAttacked(board, sq(baseRow, 2), opp)) {
    moves.push({ from: sq(baseRow, 4), to: sq(baseRow, 2) });
  }

  // Filter moves that leave own king in check
  return moves.filter(m => {
    const next = applyMove(state, m);
    return !isInCheck(next.board, turn);
  });
}

function gameStatus(state) {
  const moves = legalMoves(state);
  if (moves.length === 0) {
    return isInCheck(state.board, state.turn) ? 'checkmate' : 'stalemate';
  }
  if (state.halfmoves >= 100) return 'draw-50move';
  if (isInCheck(state.board, state.turn)) return 'check';
  return 'playing';
}

function moveToSAN(state, move) {
  const piece = state.board[move.from];
  const t = typ(piece);
  const c = clr(piece);

  // Castling
  if (t === K) {
    const dc = sqC(move.to) - sqC(move.from);
    if (dc === 2)  return 'O-O';
    if (dc === -2) return 'O-O-O';
  }

  const isCapture = state.board[move.to] !== 0 || move.epCapture;
  const pieceNames = ['', '', 'N', 'B', 'R', 'Q', 'K'];
  let san = t !== P ? pieceNames[t] : '';

  // Disambiguation for non-pawn pieces
  if (t !== P) {
    const ambig = legalMoves(state).filter(m =>
      m.from !== move.from &&
      state.board[m.from] === piece &&
      m.to === move.to
    );
    if (ambig.length > 0) {
      const sameFile = ambig.some(m => sqC(m.from) === sqC(move.from));
      const sameRank = ambig.some(m => sqR(m.from) === sqR(move.from));
      if (!sameFile) {
        san += 'abcdefgh'[sqC(move.from)];
      } else if (!sameRank) {
        san += '87654321'[sqR(move.from)];
      } else {
        san += 'abcdefgh'[sqC(move.from)] + '87654321'[sqR(move.from)];
      }
    }
  } else if (isCapture) {
    san += 'abcdefgh'[sqC(move.from)];
  }

  if (isCapture) san += 'x';
  san += 'abcdefgh'[sqC(move.to)] + '87654321'[sqR(move.to)];
  if (move.promo !== undefined) san += '=' + pieceNames[typ(move.promo)];

  // Check / checkmate suffix
  const nextState = applyMove(state, move);
  const nextMoves = legalMoves(nextState);
  if (nextMoves.length === 0 && isInCheck(nextState.board, nextState.turn)) {
    san += '#';
  } else if (isInCheck(nextState.board, nextState.turn)) {
    san += '+';
  }

  return san;
}
