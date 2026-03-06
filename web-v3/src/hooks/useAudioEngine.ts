import { useCallback, useEffect, useRef, useState } from "react";

const STORAGE_KEY = "ambonmud-audio";
const CROSSFADE_MS = 2000;
const LOOP_TAIL_MS = 200;

// Combat audio constants
const COMBAT_RATE = 1.12;
const NORMAL_RATE = 1.0;
const COMBAT_RAMP_MS = 1500;
const COMBAT_FILTER_FREQ = 180; // high-pass cutoff Hz — brightens/tightens the sound
const NORMAL_FILTER_FREQ = 10; // effectively bypassed
const LOW_HP_THRESHOLD = 0.25;
const PULSE_RATE_HZ = 1.8; // heartbeat-like pulse speed
const PULSE_DEPTH = 0.18; // volume dip amount (0 = no pulse, 1 = full mute)

interface AudioPrefs {
  enabled: boolean;
  musicVolume: number;
  ambientVolume: number;
}

function loadPrefs(): AudioPrefs {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<AudioPrefs>;
      return {
        enabled: typeof parsed.enabled === "boolean" ? parsed.enabled : false,
        musicVolume: typeof parsed.musicVolume === "number" ? parsed.musicVolume : 0.5,
        ambientVolume: typeof parsed.ambientVolume === "number" ? parsed.ambientVolume : 0.5,
      };
    }
  } catch { /* ignore */ }
  return { enabled: false, musicVolume: 0.5, ambientVolume: 0.5 };
}

function savePrefs(prefs: AudioPrefs) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch { /* ignore */ }
}

interface TrackState {
  source: AudioBufferSourceNode | null;
  gain: GainNode | null;
  filter: BiquadFilterNode | null;
  pulseGain: GainNode | null;
  url: string | null;
  buffer: AudioBuffer | null;
}

function emptyTrack(): TrackState {
  return { source: null, gain: null, filter: null, pulseGain: null, url: null, buffer: null };
}

interface CombatFxState {
  lfo: OscillatorNode | null;
  lfoGain: GainNode | null;
}

function emptyCombatFx(): CombatFxState {
  return { lfo: null, lfoGain: null };
}

async function fetchAudioBuffer(ctx: AudioContext, url: string): Promise<AudioBuffer> {
  const response = await fetch(url);
  const arrayBuffer = await response.arrayBuffer();
  return ctx.decodeAudioData(arrayBuffer);
}

function rampParam(ctx: AudioContext, param: AudioParam, to: number, durationMs: number) {
  const now = ctx.currentTime;
  param.cancelScheduledValues(now);
  param.setValueAtTime(param.value, now);
  param.linearRampToValueAtTime(to, now + durationMs / 1000);
}

function fadeGain(gain: GainNode, from: number, to: number, durationMs: number) {
  const now = gain.context.currentTime;
  gain.gain.setValueAtTime(from, now);
  gain.gain.linearRampToValueAtTime(to, now + durationMs / 1000);
}

export interface AudioEngine {
  enabled: boolean;
  musicVolume: number;
  ambientVolume: number;
  toggle: () => void;
  setMusicVolume: (v: number) => void;
  setAmbientVolume: (v: number) => void;
  playMusic: (url: string | null) => void;
  playAmbient: (url: string | null) => void;
  setCombatState: (inCombat: boolean, hpPercent: number) => void;
  stopAll: () => void;
}

export function useAudioEngine(): AudioEngine {
  const [prefs, setPrefs] = useState<AudioPrefs>(loadPrefs);
  const ctxRef = useRef<AudioContext | null>(null);
  const musicRef = useRef<TrackState>(emptyTrack());
  const ambientRef = useRef<TrackState>(emptyTrack());
  const combatFxRef = useRef<CombatFxState>(emptyCombatFx());
  const combatActiveRef = useRef(false);
  const prefsRef = useRef(prefs);
  useEffect(() => { prefsRef.current = prefs; });

  // Cache fetched buffers so re-entering a room doesn't re-download
  const bufferCache = useRef<Map<string, AudioBuffer>>(new Map());

  const getCtx = useCallback((): AudioContext | null => {
    if (ctxRef.current) return ctxRef.current;
    try {
      ctxRef.current = new AudioContext();
      return ctxRef.current;
    } catch {
      return null;
    }
  }, []);

  const stopTrack = useCallback((track: TrackState, fadeDuration = CROSSFADE_MS) => {
    if (track.source && track.gain) {
      const src = track.source;
      const g = track.gain;
      const f = track.filter;
      const pg = track.pulseGain;
      fadeGain(g, g.gain.value, 0, fadeDuration);
      setTimeout(() => {
        try { src.stop(); } catch { /* already stopped */ }
        try { g.disconnect(); } catch { /* ok */ }
        try { f?.disconnect(); } catch { /* ok */ }
        try { pg?.disconnect(); } catch { /* ok */ }
      }, fadeDuration + 50);
    }
    track.source = null;
    track.gain = null;
    track.filter = null;
    track.pulseGain = null;
    track.url = null;
    track.buffer = null;
  }, []);

  const startTrack = useCallback(async (
    trackRef: React.RefObject<TrackState>,
    url: string | null,
    volume: number,
    isMusic: boolean,
  ) => {
    const track = trackRef.current;
    if (!track) return;

    // Same URL already playing — nothing to do
    if (url === track.url) return;

    // Fade out old track
    if (track.source) {
      stopTrack(track);
    }

    if (!url) {
      track.url = null;
      return;
    }

    const ctx = getCtx();
    if (!ctx) return;
    if (ctx.state === "suspended") {
      try { await ctx.resume(); } catch { return; }
    }

    let buffer = bufferCache.current.get(url);
    if (!buffer) {
      try {
        buffer = await fetchAudioBuffer(ctx, url);
        bufferCache.current.set(url, buffer);
      } catch {
        return;
      }
    }

    // Check that another call hasn't already replaced this track
    if (trackRef.current !== track) return;

    // Build audio graph: source → filter → pulseGain → gain → destination
    // For music tracks, filter + pulseGain are used by combat effects.
    // For ambient tracks, filter/pulseGain are created but left neutral.
    const gainNode = ctx.createGain();
    gainNode.connect(ctx.destination);
    gainNode.gain.setValueAtTime(0, ctx.currentTime);

    const pulseGain = ctx.createGain();
    pulseGain.gain.setValueAtTime(1, ctx.currentTime);
    pulseGain.connect(gainNode);

    const filter = ctx.createBiquadFilter();
    filter.type = "highpass";
    filter.frequency.setValueAtTime(
      isMusic && combatActiveRef.current ? COMBAT_FILTER_FREQ : NORMAL_FILTER_FREQ,
      ctx.currentTime,
    );
    filter.Q.setValueAtTime(0.7, ctx.currentTime);
    filter.connect(pulseGain);

    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    if (buffer.duration > LOOP_TAIL_MS / 1000) {
      source.loopEnd = buffer.duration - LOOP_TAIL_MS / 1000;
    }
    // Apply combat playback rate if music and already in combat
    if (isMusic && combatActiveRef.current) {
      source.playbackRate.setValueAtTime(COMBAT_RATE, ctx.currentTime);
    }
    source.connect(filter);
    source.start(0);

    const targetVolume = prefsRef.current.enabled ? volume : 0;
    fadeGain(gainNode, 0, targetVolume, CROSSFADE_MS);

    track.source = source;
    track.gain = gainNode;
    track.filter = filter;
    track.pulseGain = pulseGain;
    track.url = url;
    track.buffer = buffer;

    // If music started mid-combat with low HP, connect the existing LFO
    if (isMusic && combatActiveRef.current) {
      const fx = combatFxRef.current;
      if (fx.lfoGain && pulseGain) {
        try { fx.lfoGain.connect(pulseGain.gain); } catch { /* ok */ }
      }
    }
  }, [getCtx, stopTrack]);

  const updatePrefs = useCallback((update: Partial<AudioPrefs>) => {
    setPrefs((prev) => {
      const next = { ...prev, ...update };
      savePrefs(next);
      return next;
    });
  }, []);

  // Apply volume changes to live tracks
  useEffect(() => {
    const musicGain = musicRef.current?.gain;
    if (musicGain) {
      const target = prefs.enabled ? prefs.musicVolume : 0;
      fadeGain(musicGain, musicGain.gain.value, target, 300);
    }
    const ambientGain = ambientRef.current?.gain;
    if (ambientGain) {
      const target = prefs.enabled ? prefs.ambientVolume : 0;
      fadeGain(ambientGain, ambientGain.gain.value, target, 300);
    }
  }, [prefs.enabled, prefs.musicVolume, prefs.ambientVolume]);

  const playMusic = useCallback((url: string | null) => {
    startTrack(musicRef, url, prefsRef.current.musicVolume, true);
  }, [startTrack]);

  const playAmbient = useCallback((url: string | null) => {
    startTrack(ambientRef, url, prefsRef.current.ambientVolume, false);
  }, [startTrack]);

  // ── Combat effects ──────────────────────────────────────────

  const stopPulseLfo = useCallback(() => {
    const fx = combatFxRef.current;
    if (fx.lfo) {
      try { fx.lfo.stop(); } catch { /* ok */ }
      try { fx.lfo.disconnect(); } catch { /* ok */ }
    }
    if (fx.lfoGain) {
      try { fx.lfoGain.disconnect(); } catch { /* ok */ }
    }
    combatFxRef.current = emptyCombatFx();
    // Reset pulse gain to 1.0 so volume returns to normal
    const ctx = ctxRef.current;
    const pg = musicRef.current?.pulseGain;
    if (ctx && pg) {
      rampParam(ctx, pg.gain, 1.0, 400);
    }
  }, []);

  const startPulseLfo = useCallback(() => {
    const ctx = ctxRef.current;
    const pg = musicRef.current?.pulseGain;
    if (!ctx || !pg) return;

    // Already running
    if (combatFxRef.current.lfo) return;

    // LFO: oscillator → lfoGain → pulseGain.gain (AudioParam modulation)
    // The LFO output oscillates around 0, and lfoGain scales the depth.
    // We offset pulseGain to (1 - depth/2) so the LFO dips from 1.0 down to (1-depth).
    const lfo = ctx.createOscillator();
    lfo.type = "sine";
    lfo.frequency.setValueAtTime(PULSE_RATE_HZ, ctx.currentTime);

    const lfoGain = ctx.createGain();
    lfoGain.gain.setValueAtTime(PULSE_DEPTH / 2, ctx.currentTime);

    lfo.connect(lfoGain);
    lfoGain.connect(pg.gain);
    lfo.start();

    // Set pulseGain base value to center the oscillation below 1.0
    pg.gain.setValueAtTime(1 - PULSE_DEPTH / 2, ctx.currentTime);

    combatFxRef.current = { lfo, lfoGain };
  }, []);

  const setCombatState = useCallback((inCombat: boolean, hpPercent: number) => {
    if (!prefsRef.current.enabled) return;

    const wasInCombat = combatActiveRef.current;
    combatActiveRef.current = inCombat;

    const ctx = ctxRef.current;
    const music = musicRef.current;

    if (inCombat && !wasInCombat) {
      // Entering combat — speed up + filter
      if (ctx && music?.source) {
        rampParam(ctx, music.source.playbackRate, COMBAT_RATE, COMBAT_RAMP_MS);
      }
      if (ctx && music?.filter) {
        rampParam(ctx, music.filter.frequency, COMBAT_FILTER_FREQ, COMBAT_RAMP_MS);
      }
    } else if (!inCombat && wasInCombat) {
      // Leaving combat — restore normal
      if (ctx && music?.source) {
        rampParam(ctx, music.source.playbackRate, NORMAL_RATE, COMBAT_RAMP_MS);
      }
      if (ctx && music?.filter) {
        rampParam(ctx, music.filter.frequency, NORMAL_FILTER_FREQ, COMBAT_RAMP_MS);
      }
      stopPulseLfo();
      return;
    }

    // Low-HP pulse (only during combat)
    if (inCombat && hpPercent <= LOW_HP_THRESHOLD) {
      startPulseLfo();
    } else {
      stopPulseLfo();
    }
  }, [startPulseLfo, stopPulseLfo]);

  // ── Lifecycle ───────────────────────────────────────────────

  const stopAll = useCallback(() => {
    stopPulseLfo();
    combatActiveRef.current = false;
    if (musicRef.current) stopTrack(musicRef.current, 500);
    if (ambientRef.current) stopTrack(ambientRef.current, 500);
    Object.assign(musicRef, { current: emptyTrack() });
    Object.assign(ambientRef, { current: emptyTrack() });
  }, [stopTrack, stopPulseLfo]);

  const toggle = useCallback(() => {
    const next = !prefsRef.current.enabled;
    updatePrefs({ enabled: next });
    if (next && ctxRef.current?.state === "suspended") {
      ctxRef.current.resume();
    }
    // If disabling, also kill combat effects
    if (!next) {
      stopPulseLfo();
      combatActiveRef.current = false;
    }
  }, [updatePrefs, stopPulseLfo]);

  const setMusicVolume = useCallback((v: number) => {
    updatePrefs({ musicVolume: Math.max(0, Math.min(1, v)) });
  }, [updatePrefs]);

  const setAmbientVolume = useCallback((v: number) => {
    updatePrefs({ ambientVolume: Math.max(0, Math.min(1, v)) });
  }, [updatePrefs]);

  // Cleanup on unmount
  useEffect(() => {
    const music = musicRef;
    const ambient = ambientRef;
    const ctx = ctxRef;
    const combatFx = combatFxRef;
    return () => {
      if (combatFx.current.lfo) try { combatFx.current.lfo.stop(); } catch { /* ok */ }
      if (music.current?.source) try { music.current.source.stop(); } catch { /* ok */ }
      if (ambient.current?.source) try { ambient.current.source.stop(); } catch { /* ok */ }
      if (ctx.current) try { ctx.current.close(); } catch { /* ok */ }
    };
  }, []);

  return {
    enabled: prefs.enabled,
    musicVolume: prefs.musicVolume,
    ambientVolume: prefs.ambientVolume,
    toggle,
    setMusicVolume,
    setAmbientVolume,
    playMusic,
    playAmbient,
    setCombatState,
    stopAll,
  };
}
