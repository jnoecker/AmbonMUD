import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { parseGmcp } from "../utils";

interface UseMudSocketOptions {
  onTextMessage: (text: string) => void;
  onGmcpMessage: (pkg: string, data: unknown) => void;
  onOpen?: () => void;
  onClose?: () => void;
  onError?: () => void;
}

export function useMudSocket(options: UseMudSocketOptions) {
  const socketRef = useRef<WebSocket | null>(null);
  const optionsRef = useRef(options);

  const [connected, setConnected] = useState(false);
  const [liveMessage, setLiveMessage] = useState("Disconnected.");

  useEffect(() => {
    optionsRef.current = options;
  }, [options]);

  const wsUrl = useMemo(() => {
    const scheme = window.location.protocol === "https:" ? "wss" : "ws";
    return `${scheme}://${window.location.host}/ws`;
  }, []);

  const connect = useCallback(() => {
    const existing = socketRef.current;
    if (existing && (existing.readyState === WebSocket.OPEN || existing.readyState === WebSocket.CONNECTING)) return;

    const ws = new WebSocket(wsUrl);
    socketRef.current = ws;

    ws.addEventListener("open", () => {
      setConnected(true);
      setLiveMessage("Connected.");
      optionsRef.current.onOpen?.();
    });

    ws.addEventListener("message", (event) => {
      if (typeof event.data !== "string") return;
      const gmcp = parseGmcp(event.data);
      if (gmcp) {
        optionsRef.current.onGmcpMessage(gmcp.pkg, gmcp.data);
        return;
      }
      optionsRef.current.onTextMessage(event.data);
    });

    ws.addEventListener("close", () => {
      if (socketRef.current === ws) socketRef.current = null;
      setConnected(false);
      setLiveMessage("Connection closed.");
      optionsRef.current.onClose?.();
    });

    ws.addEventListener("error", () => {
      setLiveMessage("Connection error.");
      optionsRef.current.onError?.();
    });
  }, [wsUrl]);

  const disconnect = useCallback(() => {
    const ws = socketRef.current;
    if (!ws) return;
    socketRef.current = null;
    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.close();
  }, []);

  const reconnect = useCallback(() => {
    disconnect();
    window.setTimeout(() => connect(), 120);
  }, [connect, disconnect]);

  const sendLine = useCallback((line: string): boolean => {
    const ws = socketRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    ws.send(line);
    return true;
  }, []);

  return {
    connected,
    liveMessage,
    connect,
    disconnect,
    reconnect,
    sendLine,
  };
}

