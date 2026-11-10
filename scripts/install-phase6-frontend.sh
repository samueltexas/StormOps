#!/bin/bash
set -Eeuo pipefail

PROJECT="${STORMOPS_PROJECT:-$HOME/Projects/StormOps}"
FRONTEND="$PROJECT/frontend"

echo "===== STORMOPS FRONTEND INSTALLER ====="

if [[ ! -f "$FRONTEND/package.json" ]]; then
    echo "Missing frontend/package.json." >&2
    exit 1
fi

if [[ ! -f "$FRONTEND/tsconfig.json" ]]; then
    echo "Missing TypeScript configuration." >&2
    exit 1
fi

if [[ ! -d "$FRONTEND/node_modules" ]]; then
    echo "Missing node_modules. Run npm install first." >&2
    exit 1
fi

BACKUP="$PROJECT/.phase6-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP"

for file in \
    src/App.tsx \
    src/App.css \
    src/index.css \
    vite.config.ts
do
    if [[ -f "$FRONTEND/$file" ]]; then
        mkdir -p "$BACKUP/$(dirname "$file")"
        cp "$FRONTEND/$file" "$BACKUP/$file"
    fi
done

echo "Backup: $BACKUP"

cat > "$FRONTEND/vite.config.ts" <<'EOF'
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Keep API traffic on the local development server.
// The browser never needs direct cross-origin access to Spring Boot.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 15173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
    },
  },
});
EOF

cat > "$FRONTEND/src/App.tsx" <<'EOF'
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  AlertCircle,
  ArrowUpRight,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Database,
  ExternalLink,
  FlaskConical,
  LayoutDashboard,
  LoaderCircle,
  Play,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  X,
  XCircle,
} from 'lucide-react';
import './App.css';

type ExperimentStatus =
  | 'RECOVERED'
  | 'FAILED'
  | 'RUNNING'
  | 'PENDING'
  | string;

interface Experiment {
  id: string;
  startedAt: string;
  finishedAt: string | null;
  type: string;
  target: string;
  status: ExperimentStatus;
  recoveryMillis: number | null;
  detail: string | null;
}

interface ExperimentPage {
  page: number;
  size: number;
  total: number;
  items: Experiment[];
}

interface ApiHealth {
  status: string;
}

type View = 'overview' | 'experiments' | 'observability';

const PAGE_SIZE = 20;
const API_BASE = '/api/v1';

const EMPTY_PAGE: ExperimentPage = {
  page: 0,
  size: PAGE_SIZE,
  total: 0,
  items: [],
};

async function requestJson<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const response = await fetch(path, {
    cache: 'no-store',
    ...options,
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(
      `HTTP ${response.status}${message ? `: ${message.slice(0, 180)}` : ''}`,
    );
  }

  return response.json() as Promise<T>;
}

function formatDate(value: string | null): string {
  if (!value) return '—';

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) return '—';

  return new Intl.DateTimeFormat('en-GB', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function formatDuration(milliseconds: number | null): string {
  if (milliseconds === null || milliseconds < 0) return '—';

  if (milliseconds < 1000) {
    return `${milliseconds} ms`;
  }

  return `${(milliseconds / 1000).toFixed(2)} s`;
}

function statusClass(status: string): string {
  if (status === 'RECOVERED') return 'status-success';
  if (status === 'FAILED') return 'status-danger';
  if (status === 'RUNNING') return 'status-running';

  return 'status-neutral';
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`status-badge ${statusClass(status)}`}>
      <span className="status-dot" />
      {status}
    </span>
  );
}

function StatCard({
  label,
  value,
  hint,
  icon: Icon,
}: {
  label: string;
  value: string | number;
  hint: string;
  icon: typeof Activity;
}) {
  return (
    <article className="stat-card">
      <div className="stat-top">
        <span>{label}</span>
        <Icon size={19} strokeWidth={1.8} />
      </div>
      <strong>{value}</strong>
      <small>{hint}</small>
    </article>
  );
}

function App() {
  const [view, setView] = useState<View>('overview');
  const [page, setPage] = useState(0);
  const [history, setHistory] = useState<ExperimentPage>(EMPTY_PAGE);
  const [apiStatus, setApiStatus] = useState<'UP' | 'DOWN' | 'CHECKING'>(
    'CHECKING',
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [selected, setSelected] = useState<Experiment | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [running, setRunning] = useState(false);
  const [executionResult, setExecutionResult] = useState<Experiment | null>(
    null,
  );

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);

    const [healthResult, historyResult] = await Promise.allSettled([
      requestJson<ApiHealth>('/actuator/health'),
      requestJson<ExperimentPage>(
        `${API_BASE}/experiments/page?page=${page}&size=${PAGE_SIZE}`,
      ),
    ]);

    if (healthResult.status === 'fulfilled') {
      setApiStatus(healthResult.value.status === 'UP' ? 'UP' : 'DOWN');
    } else {
      setApiStatus('DOWN');
    }

    if (historyResult.status === 'fulfilled') {
      setHistory(historyResult.value);
    } else {
      setError(
        historyResult.reason instanceof Error
          ? historyResult.reason.message
          : 'Unable to load experiment history.',
      );
    }

    setLoading(false);
  }, [page]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const filteredItems = useMemo(() => {
    const query = search.trim().toLowerCase();

    return history.items.filter((experiment) => {
      const matchesStatus =
        filter === 'ALL' || experiment.status === filter;

      const matchesSearch =
        !query ||
        [
          experiment.id,
          experiment.type,
          experiment.target,
          experiment.status,
        ]
          .join(' ')
          .toLowerCase()
          .includes(query);

      return matchesStatus && matchesSearch;
    });
  }, [history.items, search, filter]);

  // Statistics describe the currently loaded page, not all historical
  // experiments. Global totals require a dedicated aggregate API.
  const recovered = history.items.filter(
    (experiment) => experiment.status === 'RECOVERED',
  ).length;

  const failed = history.items.filter(
    (experiment) => experiment.status === 'FAILED',
  ).length;

  const recoveryValues = history.items
    .filter(
      (experiment) =>
        experiment.status === 'RECOVERED' &&
        experiment.recoveryMillis !== null,
    )
    .map((experiment) => experiment.recoveryMillis as number);

  const averageRecovery =
    recoveryValues.length > 0
      ? recoveryValues.reduce((sum, value) => sum + value, 0) /
        recoveryValues.length
      : null;

  const pageCount = Math.max(
    1,
    Math.ceil(history.total / PAGE_SIZE),
  );

  async function executeExperiment() {
    setRunning(true);
    setError(null);
    setExecutionResult(null);

    try {
      const result = await requestJson<Experiment>(
        `${API_BASE}/experiments/pod-termination`,
        {
          method: 'POST',
          headers: {
            'X-StormOps-Confirmation': 'TERMINATE_STORMOPS_DEMO_POD',
          },
        },
      );

      setExecutionResult(result);
      setConfirmOpen(false);
      setPage(0);
      await loadData();
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : 'The experiment could not be completed.',
      );
    } finally {
      setRunning(false);
    }
  }

  function renderHistory() {
    return (
      <section className="panel history-panel">
        <div className="panel-heading">
          <div>
            <h2>Experiment history</h2>
            <p>
              Recorded chaos experiments and Kubernetes recovery outcomes
            </p>
          </div>
          <span className="muted-count">
            {history.total} total records
          </span>
        </div>

        <div className="toolbar">
          <label className="search-field">
            <Search size={17} />
            <input
              aria-label="Search experiments"
              placeholder="Search experiments..."
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>

          <select
            aria-label="Filter by status"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          >
            <option value="ALL">All statuses</option>
            <option value="RECOVERED">Recovered</option>
            <option value="FAILED">Failed</option>
            <option value="RUNNING">Running</option>
            <option value="PENDING">Pending</option>
          </select>
        </div>

        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Experiment</th>
                <th>Target</th>
                <th>Status</th>
                <th>Recovery</th>
                <th>Started</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filteredItems.map((experiment) => (
                <tr key={experiment.id}>
                  <td>
                    <span className="table-primary">
                      {experiment.type.replaceAll('_', ' ')}
                    </span>
                    <span className="table-secondary mono">
                      {experiment.id.slice(0, 8)}
                    </span>
                  </td>
                  <td className="target-cell">{experiment.target}</td>
                  <td>
                    <StatusBadge status={experiment.status} />
                  </td>
                  <td>{formatDuration(experiment.recoveryMillis)}</td>
                  <td>{formatDate(experiment.startedAt)}</td>
                  <td>
                    <button
                      className="icon-button"
                      aria-label={`View experiment ${experiment.id}`}
                      onClick={() => setSelected(experiment)}
                    >
                      <ArrowUpRight size={17} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {!loading && filteredItems.length === 0 && (
            <div className="empty-state">
              <FlaskConical size={30} />
              <strong>No matching experiments</strong>
              <p>
                Run a controlled experiment or adjust your filters.
              </p>
            </div>
          )}
        </div>

        <div className="table-footer">
          <span>
            Page {page + 1} of {pageCount}
            {search || filter !== 'ALL'
              ? ' · Filters apply to this page'
              : ''}
          </span>
          <div className="pagination">
            <button
              className="icon-button"
              aria-label="Previous page"
              disabled={page === 0 || loading}
              onClick={() => setPage((current) => current - 1)}
            >
              <ChevronLeft size={18} />
            </button>
            <button
              className="icon-button"
              aria-label="Next page"
              disabled={page + 1 >= pageCount || loading}
              onClick={() => setPage((current) => current + 1)}
            >
              <ChevronRight size={18} />
            </button>
          </div>
        </div>
      </section>
    );
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-symbol">
            <Activity size={23} strokeWidth={2.5} />
          </div>
          <div>
            <strong>StormOps</strong>
            <span>RESILIENCE PLATFORM</span>
          </div>
        </div>

        <div className="sidebar-label">WORKSPACE</div>

        <nav className="navigation" aria-label="Main navigation">
          <button
            className={view === 'overview' ? 'nav-active' : ''}
            onClick={() => setView('overview')}
          >
            <LayoutDashboard size={19} />
            Overview
          </button>
          <button
            className={view === 'experiments' ? 'nav-active' : ''}
            onClick={() => setView('experiments')}
          >
            <FlaskConical size={19} />
            Experiments
          </button>
          <button
            className={view === 'observability' ? 'nav-active' : ''}
            onClick={() => setView('observability')}
          >
            <Activity size={19} />
            Observability
          </button>
        </nav>

        <div className="sidebar-bottom">
          <div className="environment-card">
            <div className="environment-heading">
              <ShieldCheck size={17} />
              Isolated environment
            </div>
            <strong>stormops-lab</strong>
            <span>Local Kubernetes · kind</span>
          </div>
          <div className="sidebar-version">
            StormOps Console <span>v0.3 preview</span>
          </div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace <span>/</span>
            <strong>
              {view === 'overview'
                ? 'Overview'
                : view === 'experiments'
                  ? 'Experiments'
                  : 'Observability'}
            </strong>
          </div>
          <div className="topbar-actions">
            <span
              className={`connection-pill ${
                apiStatus === 'UP' ? 'connected' : 'disconnected'
              }`}
            >
              <span className="status-dot" />
              API {apiStatus}
            </span>
            <button
              className="icon-button"
              aria-label="Refresh data"
              disabled={loading}
              onClick={() => void loadData()}
            >
              <RefreshCw size={18} className={loading ? 'spin' : ''} />
            </button>
          </div>
        </header>

        <div className="content-container">
          <div className="page-heading">
            <div>
              <div className="eyebrow">
                <span className="eyebrow-line" />
                CHAOS ENGINEERING
              </div>
              <h1>
                {view === 'overview'
                  ? 'Resilience overview'
                  : view === 'experiments'
                    ? 'Experiments'
                    : 'Observability'}
              </h1>
              <p>
                {view === 'overview'
                  ? 'Understand how your services behave under controlled failure.'
                  : view === 'experiments'
                    ? 'Explore experiment outcomes and recovery history.'
                    : 'Inspect monitoring tools and service health.'}
              </p>
            </div>

            {view !== 'observability' && (
              <button
                className="primary-button"
                disabled={apiStatus !== 'UP' || running}
                onClick={() => setConfirmOpen(true)}
              >
                <Play size={17} fill="currentColor" />
                Run experiment
              </button>
            )}
          </div>

          {error && (
            <div className="error-banner" role="alert">
              <AlertCircle size={19} />
              <span>{error}</span>
              <button
                className="icon-button"
                aria-label="Dismiss error"
                onClick={() => setError(null)}
              >
                <X size={17} />
              </button>
            </div>
          )}

          {executionResult && (
            <div className="success-banner">
              <CheckCircle2 size={19} />
              <span>
                Experiment {executionResult.id.slice(0, 8)} completed with
                status {executionResult.status}.
              </span>
              <button
                className="text-button"
                onClick={() => setSelected(executionResult)}
              >
                View details
              </button>
            </div>
          )}

          {view === 'overview' && (
            <>
              <div className="stats-grid">
                <StatCard
                  label="Total experiments"
                  value={history.total}
                  hint="All stored experiment records"
                  icon={FlaskConical}
                />
                <StatCard
                  label="Recovered"
                  value={recovered}
                  hint="Current page · successful recovery"
                  icon={CheckCircle2}
                />
                <StatCard
                  label="Failed"
                  value={failed}
                  hint="Current page · failed experiments"
                  icon={XCircle}
                />
                <StatCard
                  label="Avg. recovery"
                  value={formatDuration(averageRecovery)}
                  hint="Current page · recovered experiments"
                  icon={Clock3}
                />
              </div>

              <div className="overview-grid">
                <section className="panel spotlight-panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Controlled fault injection</h2>
                      <p>Test recovery in a restricted Kubernetes lab</p>
                    </div>
                    <ShieldCheck size={21} />
                  </div>
                  <div className="spotlight-body">
                    <div className="fault-icon">
                      <Server size={25} />
                    </div>
                    <div>
                      <strong>Pod termination</strong>
                      <p>
                        Delete one demo-service pod and observe Kubernetes
                        restoring the desired replica count.
                      </p>
                      <span className="scope-label">
                        TARGET: stormops-demo · NAMESPACE: stormops-lab
                      </span>
                    </div>
                  </div>
                  <div className="spotlight-footer">
                    <span>
                      <ShieldCheck size={16} />
                      Explicit confirmation required
                    </span>
                    <button
                      className="secondary-button"
                      disabled={apiStatus !== 'UP'}
                      onClick={() => setConfirmOpen(true)}
                    >
                      Configure test <ArrowUpRight size={16} />
                    </button>
                  </div>
                </section>

                <section className="panel health-panel">
                  <div className="panel-heading">
                    <div>
                      <h2>System connections</h2>
                      <p>Local development services</p>
                    </div>
                  </div>
                  <div className="health-row">
                    <span className="health-icon"><Server size={18} /></span>
                    <div>
                      <strong>Spring Boot API</strong>
                      <small>localhost:18080</small>
                    </div>
                    <StatusBadge
                      status={apiStatus === 'UP' ? 'RECOVERED' : 'FAILED'}
                    />
                  </div>
                  <div className="health-row">
                    <span className="health-icon"><Database size={18} /></span>
                    <div>
                      <strong>PostgreSQL</strong>
                      <small>Persistence via backend API</small>
                    </div>
                    <span className="health-note">
                      {error ? 'Unknown' : 'API-backed'}
                    </span>
                  </div>
                  <div className="health-row">
                    <span className="health-icon"><Activity size={18} /></span>
                    <div>
                      <strong>Grafana</strong>
                      <small>Local monitoring dashboard</small>
                    </div>
                    <a
                      className="external-link"
                      href="http://127.0.0.1:13000"
                      target="_blank"
                      rel="noreferrer"
                    >
                      Open <ExternalLink size={14} />
                    </a>
                  </div>
                </section>
              </div>

              {renderHistory()}
            </>
          )}

          {view === 'experiments' && renderHistory()}

          {view === 'observability' && (
            <div className="observability-grid">
              <section className="panel observability-card">
                <Activity size={27} />
                <h2>Grafana dashboards</h2>
                <p>
                  Open the existing StormOps Resilience Overview to
                  inspect Prometheus metrics and JVM telemetry.
                </p>
                <a
                  className="secondary-button"
                  href="http://127.0.0.1:13000"
                  target="_blank"
                  rel="noreferrer"
                >
                  Open Grafana <ExternalLink size={16} />
                </a>
              </section>

              <section className="panel observability-card">
                <Server size={27} />
                <h2>Backend health</h2>
                <p>
                  The console checks Spring Boot Actuator. This is not
                  an end-to-end service availability measurement.
                </p>
                <StatusBadge
                  status={apiStatus === 'UP' ? 'RECOVERED' : 'FAILED'}
                />
              </section>
            </div>
          )}

          <footer className="app-footer">
            <span>StormOps · Chaos Engineering & Resilience Platform</span>
            <span>Local development environment</span>
          </footer>
        </div>
      </main>

      {selected && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) {
              setSelected(null);
            }
          }}
        >
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label="Experiment details"
          >
            <div className="modal-header">
              <div>
                <span className="eyebrow">EXPERIMENT DETAILS</span>
                <h2>{selected.type.replaceAll('_', ' ')}</h2>
              </div>
              <button
                className="icon-button"
                aria-label="Close details"
                onClick={() => setSelected(null)}
              >
                <X size={20} />
              </button>
            </div>
            <StatusBadge status={selected.status} />
            <dl className="detail-list">
              <div><dt>Experiment ID</dt><dd className="mono">{selected.id}</dd></div>
              <div><dt>Target pod</dt><dd>{selected.target}</dd></div>
              <div><dt>Started</dt><dd>{formatDate(selected.startedAt)}</dd></div>
              <div><dt>Finished</dt><dd>{formatDate(selected.finishedAt)}</dd></div>
              <div><dt>Replica recovery</dt><dd>{formatDuration(selected.recoveryMillis)}</dd></div>
              <div><dt>Details</dt><dd>{selected.detail || '—'}</dd></div>
            </dl>
            <p className="modal-note">
              Recovery time measures restoration of desired Kubernetes
              replicas, not necessarily HTTP downtime.
            </p>
          </section>
        </div>
      )}

      {confirmOpen && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !running) {
              setConfirmOpen(false);
            }
          }}
        >
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label="Confirm pod termination"
          >
            <div className="modal-header">
              <div>
                <span className="eyebrow">SAFETY CONFIRMATION</span>
                <h2>Run pod termination?</h2>
              </div>
              <button
                className="icon-button"
                disabled={running}
                aria-label="Cancel experiment"
                onClick={() => setConfirmOpen(false)}
              >
                <X size={20} />
              </button>
            </div>

            <p className="modal-description">
              This will request deletion of one demo pod inside the
              StormOps Kubernetes lab. Kubernetes should replace it
              automatically. No other project is an intended target.
            </p>

            <div className="confirmation-scope">
              <div><span>Experiment</span><strong>POD_TERMINATION</strong></div>
              <div><span>Namespace</span><strong>stormops-lab</strong></div>
              <div><span>Deployment</span><strong>stormops-demo</strong></div>
            </div>

            <div className="modal-actions">
              <button
                className="secondary-button"
                disabled={running}
                onClick={() => setConfirmOpen(false)}
              >
                Cancel
              </button>
              <button
                className="danger-button"
                disabled={running}
                onClick={() => void executeExperiment()}
              >
                {running ? (
                  <LoaderCircle size={17} className="spin" />
                ) : (
                  <Play size={17} />
                )}
                {running ? 'Running experiment...' : 'Confirm and execute'}
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

export default App;
EOF

cat > "$FRONTEND/src/index.css" <<'EOF'
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700;800&family=Space+Grotesk:wght@400;500;600;700&display=swap');

:root {
  font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, sans-serif;
  color: #e7ecf5;
  background: #080d16;
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
}

* { box-sizing: border-box; }

body { margin: 0; min-width: 320px; }

button, input, select { font: inherit; }

button { cursor: pointer; }

button:disabled { cursor: not-allowed; opacity: .55; }

a { color: inherit; text-decoration: none; }

button:focus-visible,
input:focus-visible,
select:focus-visible,
a:focus-visible {
  outline: 2px solid #62d8c5;
  outline-offset: 3px;
}

::selection { background: #1c746d; color: white; }
EOF

cat > "$FRONTEND/src/App.css" <<'EOF'
#root { min-height: 100vh; }

.app-shell {
  display: flex;
  min-height: 100vh;
  background:
    radial-gradient(ellipse 55% 30% at 70% -15%, #14333b44, transparent),
    #080d16;
}

.sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  width: 254px;
  min-width: 254px;
  height: 100vh;
  padding: 30px 16px 20px;
  border-right: 1px solid #1d2938;
  background: #0b111c;
}

.brand { display: flex; align-items: center; gap: 12px; padding: 0 12px; }

.brand-symbol {
  display: grid;
  place-items: center;
  width: 41px;
  height: 41px;
  color: #081a1a;
  background: #66e2cf;
  border-radius: 12px;
}

.brand strong {
  display: block;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 21px;
  letter-spacing: -.7px;
}

.brand span { display: block; margin-top: 3px; font-size: 9px; font-weight: 800; letter-spacing: 1.3px; color: #76879b; }

.sidebar-label { margin: 58px 15px 17px; color: #64758a; font-size: 10px; font-weight: 800; letter-spacing: 1.5px; }

.navigation { display: grid; gap: 7px; }

.navigation button {
  display: flex;
  align-items: center;
  gap: 13px;
  width: 100%;
  padding: 13px 15px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: #93a1b5;
  text-align: left;
  font-weight: 600;
  font-size: 13px;
}

.navigation button:hover { background: #152232; color: #f4f8fd; }
.navigation button.nav-active { color: #7de9d5; background: #12302f; border-color: #25524e; }

.sidebar-bottom { margin-top: auto; }

.environment-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid #24403f;
  border-radius: 12px;
  background: #102422;
}

.environment-heading { display: flex; align-items: center; gap: 8px; color: #74d9c5; font-size: 11px; font-weight: 700; }
.environment-card strong { font-size: 14px; }
.environment-card > span { color: #879e9f; font-size: 11px; }
.sidebar-version { display: flex; justify-content: space-between; gap: 6px; margin: 25px 4px 0; font-size: 10px; color: #68778a; }

.main-content { flex: 1; min-width: 0; }

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 76px;
  padding: 0 42px;
  border-bottom: 1px solid #1d2938;
}

.breadcrumb { display: flex; gap: 12px; color: #74859a; font-size: 12px; }
.breadcrumb span { color: #445266; }
.breadcrumb strong { color: #d9e3ef; }
.topbar-actions { display: flex; align-items: center; gap: 16px; }

.connection-pill {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 12px;
  border: 1px solid #3b3340;
  border-radius: 99px;
  color: #f0b6ae;
  background: #251b23;
  font-size: 11px;
  font-weight: 800;
}

.connection-pill.connected { color: #8ce8cc; background: #102b26; border-color: #24584c; }
.status-dot { display: inline-block; width: 7px; height: 7px; flex-shrink: 0; border-radius: 50%; background: currentColor; }

.icon-button {
  display: inline-grid;
  place-items: center;
  width: 35px;
  height: 35px;
  border: 1px solid #2b3749;
  border-radius: 8px;
  background: #141d2a;
  color: #b9c8d8;
}

.icon-button:hover:not(:disabled) { color: white; border-color: #53716f; }
.content-container { max-width: 1540px; margin: 0 auto; padding: 43px 42px 30px; }

.page-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 32px; }
.eyebrow { display: flex; align-items: center; gap: 9px; color: #69dbc8; font-size: 10px; font-weight: 800; letter-spacing: 2px; }
.eyebrow-line { width: 18px; height: 2px; background: #69dbc8; }

h1, h2, p { margin-top: 0; }

h1 {
  margin: 12px 0 9px;
  font-family: 'Space Grotesk', sans-serif;
  font-size: clamp(27px, 3vw, 39px);
  letter-spacing: -1.5px;
}

.page-heading p { margin-bottom: 0; color: #8a9aad; font-size: 13px; }

.primary-button, .secondary-button, .danger-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 12px 16px;
  border-radius: 9px;
  font-size: 12px;
  font-weight: 800;
}

.primary-button { border: 1px solid #68e3d0; color: #08201d; background: #69dfcc; }
.primary-button:hover:not(:disabled) { background: #a0f5e5; }
.secondary-button { border: 1px solid #354657; background: #192533; color: #d7e4ee; }
.secondary-button:hover:not(:disabled) { background: #263747; }
.danger-button { border: 1px solid #e77e78; background: #d55f5a; color: white; }
.danger-button:hover:not(:disabled) { background: #ed746e; }

.stats-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin-bottom: 19px; }

.stat-card, .panel {
  border: 1px solid #253142;
  border-radius: 13px;
  background: #101925;
}

.stat-card { min-height: 156px; padding: 20px; }
.stat-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: #8c9caf; font-size: 12px; font-weight: 600; }
.stat-top svg { color: #65d6c3; }
.stat-card > strong { display: block; margin-top: 22px; font-family: 'Space Grotesk', sans-serif; font-size: 31px; letter-spacing: -1px; }
.stat-card > small { display: block; margin-top: 5px; color: #78899c; font-size: 10px; }

.overview-grid { display: grid; grid-template-columns: 1.25fr 1fr; gap: 19px; margin-bottom: 19px; }
.panel { padding: 23px; min-width: 0; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; }
.panel-heading h2 { margin: 0 0 6px; font-family: 'Space Grotesk', sans-serif; font-size: 16px; letter-spacing: -.3px; }
.panel-heading p { margin: 0; color: #7f90a5; font-size: 11px; line-height: 1.6; }
.panel-heading > svg { color: #68d8c6; }
.spotlight-body { display: flex; align-items: flex-start; gap: 17px; padding: 32px 0 28px; }
.fault-icon { display: grid; place-items: center; width: 53px; height: 53px; flex-shrink: 0; border: 1px solid #24524d; border-radius: 12px; color: #6bdcc8; background: #122e2c; }
.spotlight-body strong { display: block; margin: 2px 0 8px; font-size: 14px; }
.spotlight-body p { max-width: 440px; margin: 0 0 14px; color: #9babbc; font-size: 12px; line-height: 1.7; }
.scope-label { color: #68cbb9; font-size: 9px; font-weight: 800; letter-spacing: .7px; }
.spotlight-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 18px; border-top: 1px solid #253142; }
.spotlight-footer > span { display: flex; align-items: center; gap: 7px; color: #8496a8; font-size: 10px; }
.spotlight-footer > span svg { color: #69d9c6; }

.health-row { display: flex; align-items: center; gap: 12px; padding: 20px 0; border-bottom: 1px solid #233041; }
.health-row:last-child { border-bottom: none; padding-bottom: 0; }
.health-icon { display: grid; place-items: center; width: 37px; height: 37px; border-radius: 9px; background: #1b2b39; color: #8cb2c5; }
.health-row > div { display: grid; gap: 4px; flex: 1; min-width: 0; }
.health-row strong { font-size: 12px; }
.health-row small { color: #8191a4; font-size: 10px; }
.health-note { color: #8fa4b6; font-size: 10px; }
.external-link { display: flex; align-items: center; gap: 5px; color: #77ddcc; font-size: 11px; font-weight: 700; }

.history-panel { padding-bottom: 0; }
.muted-count { white-space: nowrap; color: #8497a8; font-size: 11px; }
.toolbar { display: flex; justify-content: space-between; gap: 12px; margin: 23px 0 16px; }
.search-field { display: flex; align-items: center; gap: 9px; width: min(350px, 100%); padding: 0 12px; border: 1px solid #304052; border-radius: 8px; color: #8496aa; background: #0d1520; }
.search-field input { width: 100%; min-width: 0; height: 39px; border: none; outline: none; background: transparent; color: #edf4fc; font-size: 12px; }
.search-field input::placeholder { color: #6c7c90; }
.toolbar select { padding: 0 13px; border: 1px solid #304052; border-radius: 8px; background: #0d1520; color: #d7e3ef; font-size: 12px; }
.table-scroll { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; text-align: left; }
th { padding: 14px 12px; border-bottom: 1px solid #2a3747; color: #8292a6; font-size: 10px; font-weight: 800; letter-spacing: .4px; }
td { padding: 16px 12px; border-bottom: 1px solid #202d3b; color: #b9c7d7; font-size: 11px; }
.table-primary, .table-secondary { display: block; }
.table-primary { color: #e0e9f2; font-size: 11px; font-weight: 800; }
.table-secondary { margin-top: 6px; color: #75879c; font-size: 10px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.target-cell { max-width: 230px; overflow-wrap: anywhere; }
.status-badge { display: inline-flex; align-items: center; gap: 7px; padding: 7px 10px; border: 1px solid #3a4655; border-radius: 6px; color: #a7b6c7; background: #1a2634; font-size: 9px; font-weight: 800; }
.status-success { color: #85e6bd; border-color: #225544; background: #112c24; }
.status-danger { color: #f4a7a2; border-color: #633b3b; background: #311d23; }
.status-running { color: #f0cc86; border-color: #655132; background: #342a1b; }
.empty-state { display: grid; justify-items: center; gap: 11px; padding: 45px 10px; color: #8296a8; text-align: center; }
.empty-state strong { color: #d9e4ee; font-size: 13px; }
.empty-state p { margin: 0; font-size: 11px; }
.table-footer { display: flex; align-items: center; justify-content: space-between; padding: 16px 0; color: #8b9caf; font-size: 11px; }
.pagination { display: flex; gap: 7px; }
.pagination .icon-button { width: 30px; height: 30px; }

.observability-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.observability-card { display: flex; flex-direction: column; align-items: flex-start; gap: 17px; min-height: 260px; }
.observability-card > svg { color: #6fdec9; }
.observability-card h2 { margin: 0; font-size: 19px; }
.observability-card p { max-width: 430px; margin: 0; color: #91a2b4; font-size: 13px; line-height: 1.7; }
.observability-card .secondary-button { margin-top: auto; }

.error-banner, .success-banner { display: flex; align-items: center; gap: 11px; padding: 13px 16px; margin-bottom: 20px; border: 1px solid #77433f; border-radius: 9px; background: #2b1b20; color: #f1bab4; font-size: 12px; }
.error-banner > span, .success-banner > span { flex: 1; overflow-wrap: anywhere; }
.success-banner { border-color: #28614c; background: #132b25; color: #9ce6c8; }
.text-button { padding: 5px; border: none; background: none; color: #a1f2d8; font-size: 11px; font-weight: 800; }

.modal-backdrop { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 20px; background: #02060bdc; backdrop-filter: blur(6px); }
.modal { width: min(540px, 100%); max-height: 90vh; overflow-y: auto; padding: 27px; border: 1px solid #3a4b5c; border-radius: 15px; background: #121d2a; box-shadow: 0 30px 100px #0008; }
.modal-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; margin-bottom: 22px; }
.modal-header h2 { margin: 10px 0 0; font-family: 'Space Grotesk', sans-serif; font-size: 22px; }
.detail-list { display: grid; gap: 0; margin: 20px 0; }
.detail-list > div { display: grid; grid-template-columns: 135px minmax(0, 1fr); gap: 10px; padding: 13px 0; border-bottom: 1px solid #2a394a; }
.detail-list dt { color: #8597aa; font-size: 11px; }
.detail-list dd { margin: 0; color: #e0eaf4; font-size: 11px; overflow-wrap: anywhere; }
.modal-note, .modal-description { color: #9babbc; font-size: 12px; line-height: 1.7; }
.confirmation-scope { display: grid; gap: 13px; margin: 24px 0; padding: 18px; border: 1px solid #2a4150; border-radius: 9px; background: #0c1722; }
.confirmation-scope > div { display: flex; justify-content: space-between; gap: 12px; font-size: 11px; }
.confirmation-scope span { color: #8496a8; }
.confirmation-scope strong { color: #e2eaf2; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; }
.app-footer { display: flex; justify-content: space-between; gap: 10px; padding: 30px 0 0; color: #63758a; font-size: 10px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 1200px) {
  .stats-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .overview-grid { grid-template-columns: 1fr; }
}

@media (max-width: 760px) {
  .app-shell { display: block; }
  .sidebar { position: static; width: 100%; height: auto; min-width: 0; padding: 16px; }
  .sidebar-label, .sidebar-bottom { display: none; }
  .navigation { display: flex; margin-top: 18px; overflow-x: auto; }
  .navigation button { width: auto; white-space: nowrap; }
  .topbar { height: 60px; padding: 0 18px; }
  .content-container { padding: 28px 18px; }
  .page-heading { align-items: flex-start; flex-direction: column; }
  .stats-grid { gap: 10px; }
  .stat-card { min-height: 135px; padding: 15px; }
  .stat-card > strong { font-size: 26px; }
  .observability-grid { grid-template-columns: 1fr; }
  .spotlight-footer { align-items: flex-start; flex-direction: column; }
  .toolbar { flex-wrap: wrap; }
  .toolbar select { min-height: 39px; }
  .app-footer { flex-direction: column; }
}

@media (max-width: 420px) {
  .stats-grid { grid-template-columns: 1fr; }
  .detail-list > div { grid-template-columns: 1fr; }
}
EOF

cd "$FRONTEND"

echo ""
echo "===== BUILD VERIFICATION ====="

npm run build

echo ""
echo "===== PHASE 6 FRONTEND INSTALLED ====="
echo "Frontend: $FRONTEND"
echo "Backup: $BACKUP"
echo "Start: cd \"$FRONTEND\" && npm run dev"
