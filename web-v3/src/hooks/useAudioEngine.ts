import { useCallback, useEffect, useRef, useState } from "react";

const STORAGE_KEY = "ambonmud-audio";
const CROSSFADE_MS = 2000;
const LOOP_TAIL_MS = 200;

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
  url: string | null;
  buffer: AudioBuffer | null;
}

function emptyTrack(): TrackState {
  return { source: null, gain: null, url: null, buffer: null };
}

async function fetchAudioBuffer(ctx: AudioContext, url: string): Promise<AudioBuffer> {
  const response = await fetch(url);
  const arrayBuffer = await response.arrayBuffer();
  return ctx.decodeAudioData(arrayBuffer);
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
  stopAll: () => void;
}

export function useAudioEngine(): AudioEngine {
  const [prefs, setPrefs] = useState<AudioPrefs>(loadPrefs);
  const ctxRef = useRef<AudioContext | null>(null);
  const musicRef = useRef<TrackState>(emptyTrack());
  const ambientRef = useRef<TrackState>(emptyTrack());
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
      fadeGain(g, g.gain.value, 0, fadeDuration);
      setTimeout(() => {
        try { src.stop(); } catch { /* already stopped */ }
        try { g.disconnect(); } catch { /* ok */ }
      }, fadeDuration + 50);
    }
    track.source = null;
    track.gain = null;
    track.url = null;
    track.buffer = null;
  }, []);

  const startTrack = useCallback(async (
    trackRef: React.RefObject<TrackState>,
    url: string | null,
    volume: number,
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

    const gainNode = ctx.createGain();
    gainNode.connect(ctx.destination);
    gainNode.gain.setValueAtTime(0, ctx.currentTime);

    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    // Smooth loop: set loop end slightly before the actual end to avoid click
    if (buffer.duration > LOOP_TAIL_MS / 1000) {
      source.loopEnd = buffer.duration - LOOP_TAIL_MS / 1000;
    }
    source.connect(gainNode);
    source.start(0);

    const targetVolume = prefsRef.current.enabled ? volume : 0;
    fadeGain(gainNode, 0, targetVolume, CROSSFADE_MS);

    track.source = source;
    track.gain = gainNode;
    track.url = url;
    track.buffer = buffer;
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
    startTrack(musicRef, url, prefsRef.current.musicVolume);
  }, [startTrack]);

  const playAmbient = useCallback((url: string | null) => {
    startTrack(ambientRef, url, prefsRef.current.ambientVolume);
  }, [startTrack]);

  const stopAll = useCallback(() => {
    if (musicRef.current) stopTrack(musicRef.current, 500);
    if (ambientRef.current) stopTrack(ambientRef.current, 500);
    Object.assign(musicRef, { current: emptyTrack() });
    Object.assign(ambientRef, { current: emptyTrack() });
  }, [stopTrack]);

  const toggle = useCallback(() => {
    const next = !prefsRef.current.enabled;
    updatePrefs({ enabled: next });
    // Resume AudioContext on first enable (browser autoplay policy)
    if (next && ctxRef.current?.state === "suspended") {
      ctxRef.current.resume();
    }
  }, [updatePrefs]);

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
    return () => {
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
    stopAll,
  };
}
