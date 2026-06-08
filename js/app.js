// App — UI logic for Chess Xperia

// ── Constants ──────────────────────────────────────────────────────────────
const SYMBOLS = {
   1:'♙',  2:'♘',  3:'♗',  4:'♖',  5:'♕',  6:'♔',
  '-1':'♟','-2':'♞','-3':'♝','-4':'♜','-5':'♛','-6':'♚'
};

const WHITE_SYMBOLS = ['♔','♕','♖','♗','♘','♙'];
const BLACK_SYMBOLS = ['♚','♛','♜','♝','♞','♟'];

// ── State ──────────────────────────────────────────────────────────────────
let gameState   = createState();
let playerColor = W;        // which side the human plays
let gameMode    = 'ai';     // 'ai' | 'pvp'
let aiDepth     = 3;

let selectedSq  = null;
let selectedMoves = [];     // legal moves from selectedSq
let allLegalMovesCache = [];
let lastMove    = null;
let isFlipped   = false;
let isThinking  = false;
let undoStack   = [];       // [{state, lastMove, history}]
let moveHistory = [];       // [{san, color, number}]

// ── DOM refs ───────────────────────────────────────────────────────────────
const boardEl       = document.getElementById('board');
const statusEl      = document.getElementById('status');
const movesListEl   = document.getElementById('moves-list');
const thinkingBar   = document.getElementById('thinking-bar');
const promoOverlay  = document.getElementById('promo-overlay');
const promoChoices  = document.getElementById('promo-choices');
const gameoverOverlay = document.getElementById('gameover-overlay');
const gameoverIcon  = document.getElementById('gameover-icon');
const gameoverTitle = document.getElementById('gameover-title');
const gameoverSub   = document.getElementById('gameover-sub');
const capturedWhiteEl = document.getElementById('captured-white');
const capturedBlackEl = document.getElementById('captured-black');
const diffWhiteEl   = document.getElementById('diff-white');
const diffBlackEl   = document.getElementById('diff-black');

// ── Build board cells ──────────────────────────────────────────────────────
const cells = [];
for (let i = 0; i < 64; i++) {
  const cell = document.createElement('div');
  cell.className = 'cell ' + (((i >> 3) + i) % 2 === 0 ? 'light' : 'dark');
  cell.dataset.cell = i;

  const pieceEl = document.createElement('span');
  pieceEl.className = 'piece';
  cell.appendChild(pieceEl);

  boardEl.appendChild(cell);
  cells.push(cell);
}

// ── Coordinate helpers ─────────────────────────────────────────────────────
function cellToSq(i) {
  const vr = i >> 3, vc = i & 7;
  return isFlipped ? sq(7 - vr, 7 - vc) : sq(vr, vc);
}

function sqToCell(s) {
  const r = sqR(s), c = sqC(s);
  return isFlipped ? (7 - r) * 8 + (7 - c) : r * 8 + c;
}

// ── Board labels ───────────────────────────────────────────────────────────
function updateLabels() {
  const files = isFlipped ? 'hgfedcba' : 'abcdefgh';
  const ranks = isFlipped ? '12345678' : '87654321';

  document.querySelectorAll('.coord-file').forEach((el, i) => { el.textContent = files[i]; });
  document.querySelectorAll('.coord-rank').forEach((el, i) => { el.textContent = ranks[i]; });
}

// ── Render ─────────────────────────────────────────────────────────────────
function render() {
  const status = gameStatus(gameState);
  const inChk  = (status === 'check' || status === 'checkmate');
  const kingSq = inChk ? findKing(gameState.board, gameState.turn) : -1;
  const legalTargets = new Set(selectedMoves.map(m => m.to));

  for (let i = 0; i < 64; i++) {
    const s = cellToSq(i);
    const piece = gameState.board[s];
    const cell  = cells[i];
    const pieceEl = cell.firstChild;

    // Piece symbol
    const sym = SYMBOLS[piece] || SYMBOLS[String(piece)];
    pieceEl.textContent = sym || '';
    pieceEl.className = 'piece' + (piece > 0 ? ' white' : piece < 0 ? ' black' : '');

    // Square classes
    const isLight = (((s >> 3) + s) & 1) === 0;
    let cls = 'cell ' + (isLight ? 'light' : 'dark');

    if (s === selectedSq)                              cls += ' selected';
    else if (lastMove && (s === lastMove.from || s === lastMove.to)) cls += ' last-move';

    if (s === kingSq)                                  cls += ' in-check';

    if (legalTargets.has(s)) {
      cls += piece !== 0 ? ' legal-capture' : ' legal-move';
    }

    cell.className = cls;
  }

  renderStatus(status);
  renderCaptured();
  renderMoveList();
}

function renderStatus(status) {
  const turnName = gameState.turn === W ? 'Blancs' : 'Noirs';
  let text = '', cls = '';

  if (isThinking) {
    text = 'L\'IA réfléchit…'; cls = 'thinking';
  } else {
    switch (status) {
      case 'playing':    text = `Tour des ${turnName}`;              break;
      case 'check':      text = `Échec ! Tour des ${turnName}`;       cls = 'check'; break;
      case 'checkmate':
      case 'stalemate':
      case 'draw-50move': text = endText(status).title;              cls = 'danger'; break;
    }
  }
  statusEl.textContent = text;
  statusEl.className = cls;
  thinkingBar.className = isThinking ? 'active' : '';
}

function endText(status) {
  const loser  = gameState.turn === W ? 'Blancs' : 'Noirs';
  const winner = gameState.turn === W ? 'Noirs' : 'Blancs';
  if (status === 'checkmate') return { icon:'♟', title:`Échec et mat !`, sub:`${winner} gagnent !` };
  if (status === 'stalemate') return { icon:'🤝', title:`Pat !`, sub:`Match nul – position de blocage.` };
  return { icon:'🤝', title:`Match nul !`, sub:`Règle des 50 coups.` };
}

function renderCaptured() {
  const initial = { 1:8, 2:2, 3:2, 4:2, 5:1, 6:1 };
  const count = (sign) => {
    const c = {};
    gameState.board.forEach(p => {
      if (p !== 0 && Math.sign(p) === sign) c[Math.abs(p)] = (c[Math.abs(p)] || 0) + 1;
    });
    return c;
  };
  const wc = count(1), bc = count(-1);

  const capturedByBlack = [];
  const capturedByWhite = [];
  let wMat = 0, bMat = 0;
  const vals = { 1:1, 2:3, 3:3, 4:5, 5:9, 6:0 };

  for (let t = 1; t <= 5; t++) {
    const wLost = initial[t] - (wc[t] || 0);
    const bLost = initial[t] - (bc[t] || 0);
    for (let j = 0; j < wLost; j++) capturedByBlack.push(WHITE_SYMBOLS[6 - t]);
    for (let j = 0; j < bLost; j++) capturedByWhite.push(BLACK_SYMBOLS[6 - t]);
    wMat += (wc[t] || 0) * vals[t];
    bMat += (bc[t] || 0) * vals[t];
  }

  capturedWhiteEl.textContent = capturedByWhite.join('');
  capturedBlackEl.textContent = capturedByBlack.join('');

  const diff = wMat - bMat;
  diffWhiteEl.textContent = diff > 0 ? `+${diff}` : '';
  diffBlackEl.textContent = diff < 0 ? `+${-diff}` : '';
}

function renderMoveList() {
  let html = '';
  for (let i = 0; i < moveHistory.length; i += 2) {
    const wm = moveHistory[i];
    const bm = moveHistory[i + 1];
    const num = wm.number;
    const wCls = (i === moveHistory.length - 1 && !bm) ? 'move-w current' : 'move-w';
    const bCls = (bm && i + 1 === moveHistory.length - 1) ? 'move-b current' : 'move-b';
    html += `<div class="move-row">
      <span class="move-num">${num}.</span>
      <span class="${wCls}">${wm.san}</span>
      ${bm ? `<span class="${bCls}">${bm.san}</span>` : ''}
    </div>`;
  }
  movesListEl.innerHTML = html;
  movesListEl.scrollTop = movesListEl.scrollHeight;
}

// ── Input handling ─────────────────────────────────────────────────────────
boardEl.addEventListener('click', e => {
  const cellEl = e.target.closest('.cell');
  if (!cellEl) return;

  const i = parseInt(cellEl.dataset.cell, 10);
  const s = cellToSq(i);

  const status = gameStatus(gameState);
  if (status !== 'playing' && status !== 'check') return;
  if (isThinking) return;
  if (gameMode === 'ai' && gameState.turn !== playerColor) return;

  handleSquareClick(s);
});

function handleSquareClick(s) {
  if (selectedSq !== null) {
    // Try to move there
    const moves = selectedMoves.filter(m => m.to === s);

    if (moves.length === 1) {
      executeMove(moves[0]);
      return;
    }
    if (moves.length > 1) {
      // Multiple promos
      showPromoDialog(moves);
      return;
    }

    // Reselect own piece
    if (gameState.board[s] !== 0 && clr(gameState.board[s]) === gameState.turn) {
      selectSquare(s);
      return;
    }

    // Deselect
    selectedSq    = null;
    selectedMoves = [];
    render();
    return;
  }

  // Select
  if (gameState.board[s] !== 0 && clr(gameState.board[s]) === gameState.turn) {
    selectSquare(s);
  }
}

function selectSquare(s) {
  selectedSq    = s;
  selectedMoves = allLegalMovesCache.filter(m => m.from === s);
  render();
}

// ── Execute a move ─────────────────────────────────────────────────────────
function executeMove(move) {
  const preMoveState = gameState;
  const san = moveToSAN(gameState, move);

  undoStack.push({ state: gameState, lastMove, history: moveHistory.slice() });

  lastMove  = move;
  gameState = applyMove(gameState, move);
  selectedSq    = null;
  selectedMoves = [];
  allLegalMovesCache = legalMoves(gameState);

  moveHistory.push({
    san,
    color:  preMoveState.turn,
    number: preMoveState.fullmoves
  });

  render();

  const status = gameStatus(gameState);
  if (status === 'checkmate' || status === 'stalemate' || status === 'draw-50move') {
    setTimeout(() => showGameOver(status), 600);
    return;
  }

  if (gameMode === 'ai' && gameState.turn !== playerColor) {
    setTimeout(makeAIMove, 250);
  }
}

// ── AI ─────────────────────────────────────────────────────────────────────
function makeAIMove() {
  isThinking = true;
  renderStatus(gameStatus(gameState));

  // Run after a frame so UI updates first
  setTimeout(() => {
    const move = getBestMove(gameState, aiDepth);
    isThinking = false;

    if (move) {
      executeMove(move);
    } else {
      render();
    }
  }, 30);
}

// ── Promotion dialog ───────────────────────────────────────────────────────
function showPromoDialog(moves) {
  const pieceMap = { 5: '♕♛', 4: '♖♜', 3: '♗♝', 2: '♘♞' };
  promoChoices.innerHTML = '';

  for (const [pieceType, syms] of Object.entries(pieceMap)) {
    const move = moves.find(m => Math.abs(m.promo) === parseInt(pieceType, 10));
    if (!move) continue;

    const btn = document.createElement('div');
    btn.className = 'promo-piece';
    btn.textContent = gameState.turn === W ? syms[0] : syms[1];
    btn.addEventListener('click', () => {
      closePromoDialog();
      executeMove(move);
    });
    promoChoices.appendChild(btn);
  }

  promoOverlay.classList.add('open');
}

function closePromoDialog() {
  promoOverlay.classList.remove('open');
}

// ── Game over ──────────────────────────────────────────────────────────────
function showGameOver(status) {
  const { icon, title, sub } = endText(status);
  gameoverIcon.textContent  = icon;
  gameoverTitle.textContent = title;
  gameoverSub.textContent   = sub;
  gameoverOverlay.classList.add('open');
}

function closeGameover() {
  gameoverOverlay.classList.remove('open');
}

// ── New game ───────────────────────────────────────────────────────────────
function newGame() {
  closeGameover();
  gameState  = createState();
  selectedSq = null;
  selectedMoves = [];
  allLegalMovesCache = legalMoves(gameState);
  lastMove   = null;
  undoStack  = [];
  moveHistory = [];
  isThinking  = false;
  render();
  updateLabels();

  // If AI plays white (player chose black), trigger AI first move
  if (gameMode === 'ai' && playerColor === BL) {
    setTimeout(makeAIMove, 400);
  }
}

// ── Undo ───────────────────────────────────────────────────────────────────
function undoMove() {
  if (undoStack.length === 0) return;
  if (isThinking) return;

  // Undo one extra step when playing vs AI so the human's turn is restored
  const steps = (gameMode === 'ai' && undoStack.length >= 2) ? 2 : 1;

  for (let i = 0; i < steps && undoStack.length > 0; i++) {
    const prev = undoStack.pop();
    gameState   = prev.state;
    lastMove    = prev.lastMove;
    moveHistory = prev.history;
  }

  selectedSq    = null;
  selectedMoves = [];
  allLegalMovesCache = legalMoves(gameState);
  render();
}

// ── Button wiring ──────────────────────────────────────────────────────────
document.getElementById('btn-new').addEventListener('click', newGame);

document.getElementById('btn-undo').addEventListener('click', undoMove);

document.getElementById('btn-flip').addEventListener('click', () => {
  isFlipped = !isFlipped;
  render();
  updateLabels();
});

document.getElementById('btn-mode').addEventListener('click', () => {
  gameMode = gameMode === 'ai' ? 'pvp' : 'ai';
  document.getElementById('btn-mode').textContent = gameMode === 'ai' ? 'vs IA' : 'vs Humain';
  newGame();
});

document.getElementById('btn-side').addEventListener('click', () => {
  if (gameMode !== 'ai') return;
  playerColor = -playerColor;
  document.getElementById('btn-side').textContent =
    playerColor === W ? 'Jouer Noirs' : 'Jouer Blancs';
  newGame();
});

document.getElementById('diff-select').addEventListener('change', e => {
  aiDepth = parseInt(e.target.value, 10);
});

document.getElementById('btn-play-again').addEventListener('click', newGame);
promoOverlay.addEventListener('click', e => { if (e.target === promoOverlay) closePromoDialog(); });
gameoverOverlay.addEventListener('click', e => { if (e.target === gameoverOverlay) closeGameover(); });

// ── Service Worker registration ────────────────────────────────────────────
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

// ── Init ───────────────────────────────────────────────────────────────────
allLegalMovesCache = legalMoves(gameState);
updateLabels();
render();
