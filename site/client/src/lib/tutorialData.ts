/**
 * Planta de Mineração: leitura auditável dos tutoriais canónicos na main.
 * Nenhum texto de guia é simulado no frontend quando a fonte está indisponível.
 */
import { useCallback, useEffect, useState } from "react";

const TUTORIALS_ROOT = "https://raw.githubusercontent.com/MarcosBrendonDePaula/mine-loader/main/docs/tutorials/";
export const TUTORIALS_INDEX_URL = `${TUTORIALS_ROOT}index.json`;

export type TutorialIndexItem = {
  id: string;
  index: string;
  title: string;
  description: string;
  minutes: number;
  evidence: "block_geometry" | "item_registry" | "menu_grid" | "lua_flow";
  document: string;
};

export type TutorialIndex = {
  schema: 1;
  kind: "mine_loader_tutorial_index";
  title: string;
  updated_at: string;
  contract: { name: string; format_version: number; principle: string };
  page: { index: string; eyebrow: string; title: string; summary: string; intro: { label: string; title: string; lead: string; paragraphs: string[] }; callout: { title: string; text: string } };
  items: TutorialIndexItem[];
};

export type TutorialSection = {
  id: string;
  title: string;
  layout: "split" | "code" | "table";
  lead?: string;
  paragraphs?: string[];
  code?: { language: string; value: string };
  table?: { headers: string[]; rows: string[][] };
  callout?: { tone: "default" | "warning" | "proof"; title: string; text: string };
};

export type TutorialDocument = {
  schema: 1;
  kind: "mine_loader_tutorial";
  id: string;
  index: string;
  eyebrow: string;
  title: string;
  summary: string;
  updated_at: string;
  sources: string[];
  contracts: { permissions: string[]; capabilities: string[]; limits: string[] };
  outcome: { icon: "blocks" | "box" | "panels" | "code"; label: string; title: string; text: string };
  evidence: { kind: TutorialIndexItem["evidence"]; label: string; meta: string; items?: string[]; rows?: string[][]; selected_slot?: number; stages?: string[][] };
  sections: TutorialSection[];
};

type LiveState<T> = { data?: T; loading: boolean; error?: string };
const CACHE_MS = 60_000;
let indexCache: TutorialIndex | undefined;
let indexCachedAt = 0;
let indexInFlight: Promise<TutorialIndex> | undefined;
const documentCache = new Map<string, { data: TutorialDocument; cachedAt: number }>();
const documentInFlight = new Map<string, Promise<TutorialDocument>>();

async function readJson(url: string): Promise<unknown> {
  const response = await fetch(url, { cache: "no-store", headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`A fonte respondeu ${response.status}`);
  return response.json();
}

function isIndex(value: unknown): value is TutorialIndex {
  const data = value as Partial<TutorialIndex> | undefined;
  return data?.schema === 1 && data.kind === "mine_loader_tutorial_index" && Array.isArray(data.items) && !!data.page;
}

function isDocument(value: unknown, id: string): value is TutorialDocument {
  const data = value as Partial<TutorialDocument> | undefined;
  return data?.schema === 1 && data.kind === "mine_loader_tutorial" && data.id === id && Array.isArray(data.sections) && !!data.outcome && !!data.evidence;
}

async function loadIndex(force = false) {
  if (!force && indexCache && Date.now() - indexCachedAt < CACHE_MS) return indexCache;
  if (indexInFlight) return indexInFlight;
  indexInFlight = (async () => {
    const value = await readJson(`${TUTORIALS_INDEX_URL}?source=site&at=${Date.now()}`);
    if (!isIndex(value)) throw new Error("O índice de tutoriais não segue o schema publicado.");
    indexCache = value;
    indexCachedAt = Date.now();
    return value;
  })();
  try { return await indexInFlight; } finally { indexInFlight = undefined; }
}

async function loadDocument(id: string, force = false) {
  const cached = documentCache.get(id);
  if (!force && cached && Date.now() - cached.cachedAt < CACHE_MS) return cached.data;
  const active = documentInFlight.get(id);
  if (active) return active;
  const promise = (async () => {
    const index = await loadIndex(force);
    const item = index.items.find((candidate) => candidate.id === id);
    if (!item || !item.document.startsWith("docs/tutorials/") || !item.document.endsWith(".json")) throw new Error("O tutorial solicitado não está publicado no índice.");
    const filename = item.document.slice("docs/tutorials/".length);
    const value = await readJson(`${TUTORIALS_ROOT}${filename}?source=site&at=${Date.now()}`);
    if (!isDocument(value, id)) throw new Error("O documento do tutorial não segue o schema publicado.");
    documentCache.set(id, { data: value, cachedAt: Date.now() });
    return value;
  })();
  documentInFlight.set(id, promise);
  try { return await promise; } finally { documentInFlight.delete(id); }
}

function useLiveData<T>(loader: (force?: boolean) => Promise<T>) {
  const [state, setState] = useState<LiveState<T>>({ loading: true });
  const refresh = useCallback(async () => {
    setState((current) => ({ ...current, loading: true, error: undefined }));
    try { setState({ data: await loader(true), loading: false }); }
    catch (error) { setState({ loading: false, error: error instanceof Error ? error.message : "Falha ao consultar a fonte pública." }); }
  }, [loader]);
  useEffect(() => { void refresh(); }, [refresh]);
  return { ...state, refresh };
}

const indexLoader = (force?: boolean) => loadIndex(force);
export function useTutorialIndex() { return useLiveData(indexLoader); }
export function useTutorialDocument(id: string) {
  const loader = useCallback((force?: boolean) => loadDocument(id, force), [id]);
  return useLiveData(loader);
}

export function tutorialRawUrl(document: string) {
  return `${TUTORIALS_ROOT}${document.replace("docs/tutorials/", "")}`;
}
