/**
 * Planta de Mineração: cliente de dados públicos. O site lê a matriz JSON da
 * main sem tokens; erros preservam um estado explícito, sem dados simulados.
 */
import { useCallback, useEffect, useState } from "react";

export const REPOSITORY_URL = "https://github.com/MarcosBrendonDePaula/mine-loader";
const REPOSITORY_API_URL = "https://api.github.com/repos/MarcosBrendonDePaula/mine-loader";
const COMPATIBILITY_URL = "https://raw.githubusercontent.com/MarcosBrendonDePaula/mine-loader/main/docs/compatibility.json";

export type CompatibilityStatus = "supported" | "manual_verification" | "degraded" | "blocked";

export type CompatibilityEntry = {
  status: CompatibilityStatus;
  evidence: string;
};

export type Runtime = {
  id: string;
  platform: string;
  minecraft: string;
  maturity: "baseline" | "experimental";
  tests: { passed: number; total: number };
};

export type Compatibility = {
  schema: number;
  title: string;
  updated_at: string;
  source: string;
  contract: { name: string; format_version: number; principle: string };
  runtimes: Runtime[];
  capabilities: Array<{ id: string; label: string; entries: Record<string, CompatibilityEntry> }>;
  verification: { command: string; scope: string };
  status_definitions: Record<CompatibilityStatus, string>;
};

export type RepositorySummary = {
  stargazers_count: number;
  forks_count: number;
  open_issues_count: number;
  default_branch: string;
  pushed_at: string;
  updated_at: string;
};

type LiveSnapshot = { compatibility?: Compatibility; repository?: RepositorySummary; fetchedAt: string };
type LiveState = { data?: LiveSnapshot; loading: boolean; error?: string };

let cachedSnapshot: LiveSnapshot | undefined;
let cachedAt = 0;
let inFlight: Promise<LiveSnapshot> | undefined;
const CACHE_MS = 60_000;

async function readJson<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    cache: "no-store",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) throw new Error(`A fonte respondeu ${response.status}`);
  return response.json() as Promise<T>;
}

async function fetchSnapshot(force = false): Promise<LiveSnapshot> {
  if (!force && cachedSnapshot && Date.now() - cachedAt < CACHE_MS) return cachedSnapshot;
  if (inFlight) return inFlight;

  inFlight = (async () => {
    const cacheKey = `?source=site&at=${Date.now()}`;
    const [compatibilityResult, repositoryResult] = await Promise.allSettled([
      readJson<Compatibility>(`${COMPATIBILITY_URL}${cacheKey}`),
      readJson<RepositorySummary>(REPOSITORY_API_URL),
    ]);
    const snapshot: LiveSnapshot = { fetchedAt: new Date().toISOString() };
    if (compatibilityResult.status === "fulfilled") snapshot.compatibility = compatibilityResult.value;
    if (repositoryResult.status === "fulfilled") snapshot.repository = repositoryResult.value;
    if (!snapshot.compatibility && !snapshot.repository) throw new Error("Não foi possível consultar o GitHub agora.");
    cachedSnapshot = snapshot;
    cachedAt = Date.now();
    return snapshot;
  })();

  try {
    return await inFlight;
  } finally {
    inFlight = undefined;
  }
}

export function useGitHubData(): LiveState & { refresh: () => Promise<void> } {
  const [state, setState] = useState<LiveState>(() => ({ data: cachedSnapshot, loading: !cachedSnapshot }));

  const refresh = useCallback(async () => {
    setState((current) => ({ ...current, loading: true, error: undefined }));
    try {
      const data = await fetchSnapshot(true);
      setState({ data, loading: false });
    } catch (error) {
      setState((current) => ({ ...current, loading: false, error: error instanceof Error ? error.message : "Falha ao consultar o GitHub." }));
    }
  }, []);

  useEffect(() => {
    if (cachedSnapshot && Date.now() - cachedAt < CACHE_MS) return;
    void refresh();
  }, [refresh]);

  return { ...state, refresh };
}

export function formatGitHubDate(value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short", year: "numeric" }).format(date);
}

export function statusLabel(status: CompatibilityStatus): string {
  return ({ supported: "Verificado", manual_verification: "Manual", degraded: "Degradado", blocked: "Recusado" })[status];
}
