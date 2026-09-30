import React, { useState, useCallback, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import SafetyScene3D, { HideMeshLabels } from './vendor/components/SafetyScene3D.jsx';
const scenarios = new Set(['fire-explosion', 'gas-leak-confined-space', 'machinery-safety', 'working-at-height', 'electrical-hazard']);
const actions = new Set(['ALERT', 'RETREAT', 'ASSESS', 'ISOLATE', 'PROTECT', 'EVACUATE']);

// Embedded WebViews can resolve percentage/vh heights to zero during native layout.
// innerHeight reflects the measured viewport, including subsequent rotations.
function syncViewportHeight() {
  if (window.innerHeight > 0) document.documentElement.style.height = window.innerHeight + 'px';
}
syncViewportHeight();
window.addEventListener('resize', syncViewportHeight);

function markFailure(reason) {
  document.body.dataset.sceneError = String(reason?.message || reason || 'error');
  const status = document.getElementById('status');
  if (status) status.textContent = '3D failed to load.';
}

window.addEventListener('error', event => markFailure(event.error || event.message));
window.addEventListener('unhandledrejection', event => markFailure(event.reason));

class Boundary extends React.Component {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error) { markFailure(error || 'React scene error'); }
  render() { return this.state.failed ? null : this.props.children; }
}
function App() {
  const params = new URLSearchParams(location.search);
  const [state, setState] = useState({ scenario: params.get('scene'), action: null, paused: false, reset: 0 });
  useEffect(() => {
    document.body.dataset.sceneAction = state.action?.kind || 'NONE';
    document.body.dataset.scenePaused = String(state.paused);
  }, [state]);
  useEffect(() => {
    // One-way, typed command surface. No native JS bridge or network access.
    window.jaagrukScene = command => {
      if (!command || typeof command !== 'object') return;
      setState(old => ({ ...old,
        action: actions.has(command.action) ? { kind: command.action, safe: true } : null,
        paused: command.paused === true,
        reset: Number.isSafeInteger(command.reset) ? command.reset : old.reset,
      }));
    };
    return () => { delete window.jaagrukScene; };
  }, []);
  const ready = useCallback(value => {
    const status = document.getElementById('status');
    if (status) status.dataset.ready = String(value);
    if (document.body) document.body.dataset.sceneReady = String(value);
  }, []);
  if (!scenarios.has(state.scenario)) return <div role="alert">Unknown training environment</div>;
  return <HideMeshLabels><SafetyScene3D key={state.reset} scenarioId={state.scenario}
    action={state.action} paused={state.paused} onReadyChange={ready} /></HideMeshLabels>;
}
createRoot(document.getElementById('root')).render(<Boundary><App /></Boundary>);
